package com.finddrama

object Gl {
    const val DEFAULT_SITE = "https://www.chartdrama.com"
    const val NAME = "FindDrama"

    fun site(): String = FindDramaStore.base()
    fun api(): String = site() + "/api"

    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    const val ACCEPT_JSON = "application/json, text/plain, */*"
    const val ACCEPT_ANY = "*/*"
    const val ACCEPT_HTML = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    const val ACCEPT_LANG = "en-US,en;q=0.9"

    const val RAIL_PUBLIC = "p:"
    const val RAIL_TRENDING = "t:trending"
    const val RAIL_LATEST = "t:latest"
    const val RAIL_TRENDING_NAME = "🔥 Trending"
    const val RAIL_LATEST_NAME = "🆕 New Releases"

    const val PAGE_SIZE_PUBLIC = 100
    const val PAGE_SIZE_RANDOM = 40

    const val MAX_IN_FLIGHT = 8
    const val HTTP_ATTEMPTS = 2
    const val TIMEOUT_SECONDS = 20L
    const val MEDIA_TIMEOUT_SECONDS = 15L
    const val BACKOFF_MS = 600L

    const val PROVIDER_TTL_MINUTES = 360L
    const val RAIL_TTL_MINUTES = 180L
    const val EPISODE_TTL_MINUTES = 10L

    const val MAX_CACHED_RAILS = 200
    const val MAX_SEEN = 2000
    const val MAX_SCOPES = 64
    const val MAX_STREAMS = 12
    const val FAILURE_RUN = 12
    const val COOL_OFF_MS = 20_000L
    const val BODY_SAMPLE = 160
}

fun apiHeaders(): Map<String, String> = mapOf(
    "User-Agent" to Gl.UA,
    "Accept" to Gl.ACCEPT_JSON,
    "Accept-Language" to Gl.ACCEPT_LANG,
    "X-Requested-With" to "XMLHttpRequest",
    "Sec-Fetch-Dest" to "empty",
    "Sec-Fetch-Mode" to "cors",
    "Sec-Fetch-Site" to "same-origin",
)

fun mediaHeaders(): Map<String, String> = mapOf(
    "User-Agent" to Gl.UA,
    "Accept" to Gl.ACCEPT_ANY,
    "Accept-Language" to Gl.ACCEPT_LANG,
)

fun pageHeaders(): Map<String, String> = mapOf(
    "User-Agent" to Gl.UA,
    "Accept" to Gl.ACCEPT_HTML,
    "Accept-Language" to Gl.ACCEPT_LANG,
    "Sec-Fetch-Dest" to "document",
    "Sec-Fetch-Mode" to "navigate",
    "Sec-Fetch-Site" to "none",
    "Upgrade-Insecure-Requests" to "1",
)

fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
