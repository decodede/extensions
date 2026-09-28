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
    private const val API_ATTEMPTS = 1
    private const val MEDIA_ATTEMPTS = 2
    private const val PAGE_TIMEOUT_SECONDS = 25L
    /**
     * The site answers a section read in 5-20s from a datacentre and slower
     * still over mobile data, so a 15s ceiling was cutting live requests off
     * before they finished. This is sized to the slowest observed response
     * rather than to a round number.
     */
    private const val API_TIMEOUT_SECONDS = 20L
    private const val BLOCK_FLOOR_BYTES = 8192
    private const val CACHE_NEVER = 0
    private const val BACKOFF_MS = 800L

    /**
     * CloudStream fans every rail out at once (APIRepository maps mainPage with
     * async, unbounded), so this is the only thing bounding real HTTP. Measured
     * against the live site, 56 section reads fill 55/56 at 24-wide in 2.2s and at
     * 32-wide in 0.9s; at 8-wide the same run managed 1/56 in 37s, because the
     * site queues rather than rewarding a narrow gate. 40-wide regressed, so 32
     * is the widest setting that did not push back.
     *
     * It also sets the home budget. 56 requests at 32-wide is 2 waves, and at the
     * 20s ceiling above that is 40s, inside the 60s getMainPageTimeoutMs with
     * room for provider discovery. At 24-wide the worst case was 45s, which left
     * no margin at all and blanked the screen on a slow link. The margin is
     * derived from waves x timeout, not padding.
     */
    private const val MAX_IN_FLIGHT = 32
    private val gate = Semaphore(MAX_IN_FLIGHT)
    private val categoryLocks = ConcurrentHashMap<String, Mutex>()

    val browserHeaders: Map<String, String> = mapOf(
        "User-Agent" to BROWSER_UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9,id;q=0.8",
        // No Cache-Control/Pragma here: NiceHttp's requestCreator forces
        // Cache-Control: max-age=0 on every call and overwrites anything set.
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

    suspend fun getHtml(
        path: String,
        slug: String? = null,
        cacheMinutes: Int = CACHE_NEVER,
        attempts: Int = PAGE_ATTEMPTS,
    ): String? {
        val url = absolute(path)
        return withRetry("getHtml $url", attempts) {
            val response = app.get(
                url,
                headers = headersFor(api = false, lang = NunoDramaStore.language()),
                referer = base().trimEnd('/') + "/",
                cookies = siteCookie(slug),
                cacheTime = cacheMinutes,
                cacheUnit = TimeUnit.MINUTES,
                timeout = PAGE_TIMEOUT_SECONDS,
            )
            if (response.code !in 200..299) {
                Log.w(TAG, "getHtml $url -> HTTP ${response.code} server=${response.headers["server"]} cf-mitigated=${response.headers["cf-mitigated"]}")
                null
            } else {
                val body = response.text
                if (isBlocked(body)) {
                    Log.w(TAG, "getHtml $url -> BLOCKED: ${body.take(160).replace('\n', ' ')}")
                    null
                } else {
                    body
                }
            }
        }
    }

    suspend fun getSection(slug: String, category: String, page: Int, cursor: String?): SectionDto? {
        val target = buildString {
            append(absolute("/api/section/$slug/$category"))
            append("?page=").append(page.coerceAtLeast(1))
            if (!cursor.isNullOrBlank()) append("&next=").append(urlEncode(cursor))
        }
        return withRetry("getSection $slug/$category p$page", API_ATTEMPTS) {
            val response = app.get(
                target,
                headers = headersFor(api = true, lang = NunoDramaStore.language()),
                referer = absolute("/platform/$slug"),
                cookies = siteCookie(slug),
                cacheTime = CACHE_NEVER,
                timeout = API_TIMEOUT_SECONDS,
            )
            if (response.code !in 200..299) {
                Log.w(TAG, "section $slug/$category p$page -> HTTP ${response.code}")
                return@withRetry null
            }
            val body = response.text
            if (isBlocked(body)) {
                // A blocked body is a distinct failure from a timeout and must say so.
                // Swallowing it here is what made 55 of 56 rails look identical.
                Log.w(TAG, "section $slug/$category p$page -> BLOCKED: ${body.take(160).replace('\n', ' ')}")
                null
            } else {
                try {
                    json.decodeFromString<SectionDto>(body)
                } catch (e: Exception) {
                    Log.w(TAG, "section $slug/$category p$page -> BAD JSON: ${e.javaClass.simpleName}: ${body.take(160).replace('\n', ' ')}")
                    null
                }
            }
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
