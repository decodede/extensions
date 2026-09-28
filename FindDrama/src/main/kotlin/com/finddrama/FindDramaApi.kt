package com.finddrama

import android.util.Log
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Serializable
private data class DramaList(
    val items: List<DramaDto> = emptyList(),
)

object FindDramaApi {
    private const val TAG = "FindDrama"

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    internal val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val gate = Semaphore(Gl.MAX_IN_FLIGHT)
    private val inflight = ConcurrentHashMap<String, Mutex>()

    fun seriesPublic(sourceId: Int, page: Int): String =
        "${Gl.api()}/series?page=$page&limit=${Gl.PAGE_SIZE_PUBLIC}&sources=$sourceId"

    fun seriesLatest(page: Int): String =
        "${Gl.api()}/series?page=$page&limit=${Gl.PAGE_SIZE_PUBLIC}"

    fun seriesSearch(query: String, page: Int): String =
        "${Gl.api()}/series?q=${encode(query)}&page=$page&limit=${Gl.PAGE_SIZE_PUBLIC}"

    fun randomSource(sourceId: Int, offset: Int): String =
        "${Gl.api()}/random?limit=${Gl.PAGE_SIZE_RANDOM}&offset=$offset&source=$sourceId"

    fun trending(): String = "${Gl.api()}/trending?limit=${Gl.PAGE_SIZE_PUBLIC}"

    fun detail(slug: String): String = "${Gl.api()}/watch/${encode(slug)}"

    fun episodes(dramaId: String): String = "${Gl.api()}/drama/$dramaId/episodes"

    fun proxy(target: String): String = "${Gl.api()}/proxy?url=${encode(target)}"

    fun asset(path: String): String = Gl.site() + path

    fun absolute(url: String): String = when {
        url.startsWith("http://") || url.startsWith("https://") -> url
        url.startsWith("/") -> Gl.site() + url
        else -> "${Gl.site()}/$url"
    }

    suspend fun fetchPage(url: String, sourceScoped: Boolean = false): SeriesPage? =
        requestJson(url, "page", sourceScoped) { text ->
            json.decodeFromString<SeriesPage>(text)
        }

    suspend fun fetchDetail(slug: String): DramaDto? = requestJson(detail(slug), "detail") { text ->

        val element = json.parseToJsonElement(text).jsonObject
        if (element.containsKey("items")) {
            json.decodeFromJsonElement<DramaList>(element).items.firstOrNull()
        } else {
            json.decodeFromJsonElement<DramaDto>(element)
        }
    }

    suspend fun fetchEpisodes(dramaId: String): EpisodePage? =
        requestJson(episodes(dramaId), "episodes") { text ->
            val element = json.parseToJsonElement(text)
            if (element is JsonArray) {
                EpisodePage(items = json.decodeFromJsonElement<List<EpisodeDto>>(element))
            } else {
                json.decodeFromJsonElement<EpisodePage>(element)
            }
        }

    suspend fun fetchHtml(path: String): String? = withGate("html $path", Gl.HTTP_ATTEMPTS) {
        val response = app.get(
            asset(path),
            headers = pageHeaders(),
            referer = "${Gl.site()}/",
            cacheTime = 0,
            timeout = Gl.TIMEOUT_SECONDS,
        )
        if (response.code !in 200..299) null else response.text.takeIf { it.isNotBlank() }
    }

    suspend fun fetchText(url: String, headers: Map<String, String> = mediaHeaders()): String? =
        withGate("text $url", Gl.HTTP_ATTEMPTS) {
            val response = app.get(
                url,
                headers = headers,
                cacheTime = 0,
                cacheUnit = TimeUnit.MINUTES,
                timeout = Gl.MEDIA_TIMEOUT_SECONDS,
            )
            if (response.code !in 200..299) null else response.text
        }

    class ForbiddenSource : Exception("source is not publicly listable")

    private suspend fun <T> requestJson(
        url: String,
        label: String,
        sourceScoped: Boolean = false,
        parse: (String) -> T,
    ): T? = withGate(label + " $url", Gl.HTTP_ATTEMPTS) {
        val response = app.get(
            url,
            headers = apiHeaders(),
            referer = "${Gl.site()}/",
            cacheTime = 0,
            timeout = Gl.TIMEOUT_SECONDS,
        )
        if (response.code == 403) {
            if (sourceScoped) throw ForbiddenSource()
            Log.w(TAG, "$label -> HTTP 403")
            return@withGate null
        }
        if (response.code !in 200..299) {
            Log.w(TAG, "$label -> HTTP ${response.code}")
            return@withGate null
        }
        runCatching { parse(response.text) }
            .onFailure { Log.w(TAG, "$label -> parse failed: ${it.javaClass.simpleName}") }
            .getOrNull()
    }

    private suspend fun <T> withGate(
        label: String,
        attempts: Int,
        block: suspend () -> T?,
    ): T? {
        repeat(attempts) { attempt ->
            if (attempt > 0) {
                delay(Gl.BACKOFF_MS * attempt)
                if (!currentCoroutineContext().isActive) return null
            }
            val result = try {
                gate.withPermit { block() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is ForbiddenSource) throw e
                if (!currentCoroutineContext().isActive) return null
                Log.w(TAG, "$label attempt ${attempt + 1}/$attempts: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
            if (result != null) return result
        }
        return null
    }

    suspend fun <T> once(key: String, block: suspend () -> T?): T? {
        val lock = inflight.computeIfAbsent(key) { Mutex() }
        return try {
            lock.withLock { block() }
        } finally {
            inflight.remove(key, lock)
        }
    }
}
