package com.reelfren

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder

object ReelFrenClient {
    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private const val TIMEOUT_MS = 20_000L
    private const val RETRIES = 2

    private val cfKiller by lazy { CloudflareKiller() }

    fun apiBase(): String = ReelFrenStore.apiBase()

    fun homeUrl(slug: String, category: String): String {
        val base = apiBase() + "/api/home?provider=" + query(slug)
        return if (category.isEmpty()) base else base + "&category=" + query(category)
    }

    fun detailUrl(slug: String, id: String): String =
        apiBase() + "/api/detail?provider=" + query(slug) + "&id=" + query(id)

    fun videoUrl(slug: String, id: String, episode: Int): String =
        apiBase() + "/api/video?provider=" + query(slug) + "&id=" + query(id) + "&ep=" + episode

    fun searchUrl(slug: String, term: String): String =
        apiBase() + "/api/search?q=" + query(term) + "&provider=" + query(slug)

    suspend fun get(url: String): String? {
        var challengeSeen = false
        for (attempt in 0 until RETRIES) {
            val body = withTimeoutOrNull(TIMEOUT_MS) {
                runCatching {
                    val response = app.get(url, headers = headers())
                    val text = response.text
                    if (response.code in 200..299 && text.isNotBlank() && !isChallenge(text)) {
                        text
                    } else {
                        if (response.code == 403 || response.code == 503 || isChallenge(text)) {
                            challengeSeen = true
                        }
                        null
                    }
                }.getOrNull()
            }
            if (!body.isNullOrBlank()) return body
            if (attempt < RETRIES - 1) delay(350L)
        }
        if (!challengeSeen) return null
        return withTimeoutOrNull(30_000L) {
            runCatching {
                val response = app.get(url, headers = headers(), interceptor = cfKiller)
                if (response.code in 200..299) response.text else null
            }.getOrNull()?.takeIf { it.isNotBlank() && !isChallenge(it) }
        }
    }

    suspend fun home(slug: String, category: String): List<HomeItem> {
        val body = get(homeUrl(slug, category)) ?: return emptyList()
        return ReelFrenParse.homeItems(body)
    }

    suspend fun detail(slug: String, id: String): DetailInfo? {
        val body = get(detailUrl(slug, id)) ?: return null
        return ReelFrenParse.detail(body)
    }

    suspend fun playback(slug: String, id: String, episode: Int): PlaybackInfo? {
        val body = get(videoUrl(slug, id, episode)) ?: return null
        return ReelFrenParse.playback(body)
    }

    suspend fun search(slug: String, term: String): List<HomeItem> {
        val body = get(searchUrl(slug, term)) ?: return emptyList()
        return ReelFrenParse.homeItems(body)
    }

    suspend fun providers(): List<String> {
        val body = get(apiBase() + "/api/home") ?: return emptyList()
        return ReelFrenParse.homeItems(body).map { it.provider }.filter { it.isNotEmpty() }.distinct()
    }

    fun absUrl(value: String): String {
        val t = value.trim()
        if (t.isEmpty()) return ""
        if (t.startsWith("http")) return t
        if (t.startsWith("//")) return "https:$t"
        return apiBase() + if (t.startsWith("/")) t else "/$t"
    }

    fun query(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun headers(): Map<String, String> = mapOf(
        "User-Agent" to UA,
        "Accept" to "application/json",
        "Accept-Language" to "en-US,en;q=0.9",
        "Origin" to REEL_DEFAULT_WEB,
        "Referer" to REEL_DEFAULT_WEB + "/"
    )

    private fun isChallenge(body: String): Boolean =
        body.contains("<title>Just a moment", true) ||
            body.contains("/cdn-cgi/challenge-platform/", true)
}
