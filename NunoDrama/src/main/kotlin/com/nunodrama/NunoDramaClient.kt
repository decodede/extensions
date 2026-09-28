package com.nunodrama

import android.util.Log
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object NunoDramaClient {

    private const val TAG = "NunoDrama"

    internal val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    private const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private const val PAGE_ATTEMPTS = 2
    private const val API_ATTEMPTS = 2
    private const val MEDIA_ATTEMPTS = 2
    private const val PAGE_TIMEOUT_SECONDS = 12L
    private const val API_TIMEOUT_SECONDS = 8L
    private const val BLOCK_FLOOR_BYTES = 8192
    private const val CACHE_NEVER = 0
    private const val BACKOFF_MS = 800L

    /**
     * The site sits behind Cloudflare and starts cancelling connections well
     * before it rate limits politely, so every request in the extension passes
     * through one gate. CloudStream may fan out dozens of rails at once; this
     * is what keeps the total in flight low regardless of who asked.
     */
    private const val MAX_IN_FLIGHT = 3
    private val gate = Semaphore(MAX_IN_FLIGHT)
    private val categoryLocks = ConcurrentHashMap<String, Mutex>()

    val browserHeaders: Map<String, String> = mapOf(
        "User-Agent" to BROWSER_UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9,id;q=0.8",
        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1",
    )

    private val apiHeaders: Map<String, String> = mapOf(
        "User-Agent" to BROWSER_UA,
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "en-US,en;q=0.9,id;q=0.8",
        "X-Requested-With" to "XMLHttpRequest",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "same-origin",
    )

    private val mediaHeaders: Map<String, String> = mapOf(
        "User-Agent" to BROWSER_UA,
        "Accept" to "*/*",
        "Accept-Language" to "en-US,en;q=0.9,id;q=0.8",
    )

    private val blockMarkers = listOf(
        "no-js ie6",
        "id=\"cf-error-details\"",
        "cf-browser-verification",
        "/cdn-cgi/challenge-platform/",
        "<title>Just a moment",
        "cf_chl_opt",
    )
    private val errorTitle = Regex("""<title>[^<]*\|\s*[45]\d\d""", RegexOption.IGNORE_CASE)

    fun base(): String = NunoDramaStore.base()

    fun siteCookie(slug: String? = null): Map<String, String> = buildMap {
        put(LANG_COOKIE, NunoDramaStore.language())
        slug?.takeIf { it.isNotBlank() }?.let { put(PLATFORM_COOKIE, it) }
    }

    fun playbackHeaders(): Map<String, String> = mediaHeaders

    fun categoryLock(slug: String): Mutex = categoryLocks.getOrPut(slug) { Mutex() }

    private fun headersFor(api: Boolean, lang: String): Map<String, String> =
        (if (api) apiHeaders else browserHeaders) +
            ("Accept-Language" to if (lang == LANG_ID) "id-ID,id;q=0.9,en;q=0.8" else "en-US,en;q=0.9,id;q=0.8")

    private fun isBlocked(body: String): Boolean {
        if (body.isEmpty()) return true
        if (body.length > BLOCK_FLOOR_BYTES) return false
        return blockMarkers.any { body.contains(it, ignoreCase = true) } || errorTitle.containsMatchIn(body)
    }

    /**
     * Retries transient failures only. A cancelled coroutine is not transient:
     * the caller has already moved on, and retrying there is what turns one
     * cancellation into a request storm.
     */
    suspend fun <T> withRetry(label: String, attempts: Int, block: suspend () -> T?): T? {
        repeat(attempts) { attempt ->
            if (attempt > 0) {
                delay(BACKOFF_MS * attempt)
                if (!currentCoroutineContext().isActive) return null
            }
            val result = try {
                gate.withPermit { block() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!currentCoroutineContext().isActive) return null
                Log.w(TAG, "$label attempt ${attempt + 1}/$attempts failed: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
            if (result != null) return result
        }
        return null
    }

    suspend fun getHtml(path: String, slug: String? = null, cacheMinutes: Int = CACHE_NEVER): String? {
        val url = absolute(path)
        return withRetry("getHtml $url", PAGE_ATTEMPTS) {
            val body = app.get(
                url,
                headers = headersFor(api = false, lang = NunoDramaStore.language()),
                referer = base().trimEnd('/') + "/",
                cookies = siteCookie(slug),
                cacheTime = cacheMinutes,
                cacheUnit = TimeUnit.MINUTES,
                timeout = PAGE_TIMEOUT_SECONDS,
            ).text
            if (isBlocked(body)) null else body
        }
    }

    suspend fun getSection(slug: String, category: String, page: Int, cursor: String?): SectionDto? {
        val target = buildString {
            append(absolute("/api/section/$slug/$category"))
            append("?page=").append(page.coerceAtLeast(1))
            if (!cursor.isNullOrBlank()) append("&next=").append(urlEncode(cursor))
        }
        return withRetry("getSection $slug/$category p$page", API_ATTEMPTS) {
            val body = app.get(
                target,
                headers = headersFor(api = true, lang = NunoDramaStore.language()),
                referer = absolute("/platform/$slug"),
                cookies = siteCookie(slug),
                cacheTime = CACHE_NEVER,
                timeout = API_TIMEOUT_SECONDS,
            ).text
            if (isBlocked(body)) null else runCatching { json.decodeFromString<SectionDto>(body) }.getOrNull()
        }
    }

    suspend fun search(slug: String, query: String): List<DramaDto> {
        val target = absolute("/api/search/$slug") + "?q=" + urlEncode(query)
        return withRetry("search $slug", API_ATTEMPTS) {
            val body = app.get(
                target,
                headers = headersFor(api = true, lang = NunoDramaStore.language()),
                referer = absolute("/search/$slug"),
                cookies = siteCookie(slug),
                cacheTime = CACHE_NEVER,
                timeout = API_TIMEOUT_SECONDS,
            ).text
            if (isBlocked(body)) null else runCatching { json.decodeFromString<SectionDto>(body) }.getOrNull()?.dramas
        }.orEmpty()
    }

    suspend fun getText(url: String, headers: Map<String, String>): String? = withRetry("getText $url", MEDIA_ATTEMPTS) {
        app.get(
            url,
            headers = headers,
            cacheTime = CACHE_NEVER,
            cacheUnit = TimeUnit.MINUTES,
            timeout = PAGE_TIMEOUT_SECONDS,
        ).text
    }

    fun absolute(path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val root = base().trimEnd('/')
        return if (path.startsWith('/')) root + path else "$root/$path"
    }

    fun urlEncode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    suspend fun <T, R> mapBounded(
        items: List<T>,
        parallelism: Int,
        transform: suspend (T) -> R,
    ): List<R?> = coroutineScope {
        val local = Semaphore(parallelism.coerceAtLeast(1))
        items.map { item ->
            async {
                local.withPermit {
                    try {
                        transform(item)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        }.map { it.await() }
    }
}
