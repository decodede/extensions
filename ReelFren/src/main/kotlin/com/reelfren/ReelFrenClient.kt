package com.reelfren

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object ReelFrenClient {
    const val UA =
        "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    private const val TIMEOUT_MS = 20_000L
    private const val ATTEMPTS = 2
    private const val SOLVE_BUDGET_MS = 25_000L
    private const val SOLVE_COOLDOWN_MS = 10L * 60 * 1000
    private const val MAX_SOLVE_ATTEMPTS = 3

    private val cfKiller by lazy { CloudflareKiller() }
    private val lastSolveAt = ConcurrentHashMap<String, Long>()
    private val solveAttempts = AtomicInteger(0)

    fun apiBase(): String = ReelFrenStore.apiBase()

    fun homeUrl(slug: String, category: String): String {
        val base = apiBase() + "/api/home?provider=" + ReelFrenUrl.query(slug)
        return if (category.isEmpty()) base else base + "&category=" + ReelFrenUrl.query(category)
    }

    fun detailUrl(slug: String, id: String): String =
        apiBase() + "/api/detail?provider=" + ReelFrenUrl.query(slug) + "&id=" + ReelFrenUrl.query(id)

    fun videoUrl(slug: String, id: String, episode: Int): String =
        apiBase() + "/api/video?provider=" + ReelFrenUrl.query(slug) + "&id=" + ReelFrenUrl.query(id) + "&ep=" + episode

    fun searchUrl(slug: String, term: String): String =
        apiBase() + "/api/search?q=" + ReelFrenUrl.query(term) + "&provider=" + ReelFrenUrl.query(slug)

    suspend fun get(url: String): String? = runCatching {
        var challenged = false
        for (attempt in 0 until ATTEMPTS) {
            val outcome = withTimeoutOrNull(TIMEOUT_MS) { fetch(url) }
            if (outcome != null && outcome.first) return@runCatching outcome.second
            if (outcome == null || ReelFrenCf.isChallenge(outcome.second)) challenged = true
            if (attempt < ATTEMPTS - 1) delay(300L)
        }
        if (!challenged) return@runCatching null
        replayCookie(url)
    }.getOrNull()

    private suspend fun fetch(url: String): Pair<Boolean, String>? = runCatching {
        val response = app.get(url, headers = headers(url), interceptor = cfKiller)
        val text = response.text
        val ok = response.code in 200..299 && text.isNotBlank() && !ReelFrenCf.isChallenge(text)
        ok to text
    }.getOrNull()

    private fun allowReplay(host: String): Boolean {
        if (host.isEmpty()) return false
        if (solveAttempts.get() >= MAX_SOLVE_ATTEMPTS) return false
        val now = System.currentTimeMillis()
        val last = lastSolveAt[host] ?: 0L
        if (now - last < SOLVE_COOLDOWN_MS) return false
        lastSolveAt[host] = now
        solveAttempts.incrementAndGet()
        return true
    }

    private suspend fun replayCookie(url: String): String? = withTimeoutOrNull(SOLVE_BUDGET_MS) {
        val host = ReelFrenCf.host(url)
        if (ReelFrenStore.cookie(host).isBlank()) return@withTimeoutOrNull null
        if (!allowReplay(host)) return@withTimeoutOrNull null
        val retry = withTimeoutOrNull(TIMEOUT_MS) { fetch(url) }
        if (retry != null && retry.first) retry.second else null
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

    private fun headers(url: String): Map<String, String> {
        val headers = linkedMapOf(
            "User-Agent" to UA,
            "Accept" to "application/json",
            "Accept-Language" to "en-US,en;q=0.9",
            "Origin" to REEL_DEFAULT_WEB,
            "Referer" to REEL_DEFAULT_WEB + "/"
        )
        val cookie = ReelFrenStore.cookie(ReelFrenCf.host(url))
        if (cookie.isNotBlank()) headers["Cookie"] = cookie
        return headers
    }
}
