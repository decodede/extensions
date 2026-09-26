package com.chartdrama

import com.lagradost.cloudstream3.app
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

object ChartDramaHosts {
    private val hosts = listOf(CHARTDRAMA_API, "https://www.chartdrama.com")
    @Volatile private var index = 0

    fun current(): String = hosts[index]

    fun rotate(): String {
        index = (index + 1) % hosts.size
        return current()
    }
}

object ChartDramaClient {
    private const val TIMEOUT_MS = 20_000L
    private const val ATTEMPTS = 2

    suspend fun get(url: String): String? {
        for (round in 0 until 2) {
            for (attempt in 0 until ATTEMPTS) {
                val body = withTimeoutOrNull(TIMEOUT_MS) {
                    runCatching {
                        val response = app.get(url.replace(CHARTDRAMA_API, ChartDramaHosts.current()), headers = headers())
                        val text = response.text
                        if (response.code in 200..299 && text.isNotBlank()) text else null
                    }.getOrNull()
                }
                if (!body.isNullOrBlank()) return body
                if (attempt < ATTEMPTS - 1) delay(350L)
            }
            if (round == 0) ChartDramaHosts.rotate()
        }
        return null
    }

    suspend fun series(source: Int, page: Int, limit: Int, query: String = "", tag: String = ""): List<Series> {
        val body = get(ChartDramaApi.seriesUrl(source, page, limit, query, tag)) ?: return emptyList()
        return ChartDramaParse.series(body)
    }

    suspend fun total(source: Int, query: String = "", tag: String = ""): Int {
        val body = get(ChartDramaApi.seriesUrl(source, 1, 1, query, tag)) ?: return 0
        return ChartDramaParse.total(body)
    }

    suspend fun random(source: Int, limit: Int, offset: Int = 0): List<Series> {
        val body = get(ChartDramaApi.randomUrl(source, limit, offset)) ?: return emptyList()
        return ChartDramaParse.series(body)
    }

    suspend fun tags(limit: Int = 60): List<String> {
        val body = get(ChartDramaApi.tagsUrl(limit)) ?: return emptyList()
        return ChartDramaParse.tags(body)
    }

    suspend fun seriesBySlug(slug: String): Series? {
        val body = get(ChartDramaApi.seriesUrlFor(slug)) ?: return null
        return ChartDramaParse.series(body).firstOrNull()
    }

    suspend fun watch(slug: String): Watch? {
        val body = get(ChartDramaApi.watchUrl(slug)) ?: return null
        return ChartDramaParse.watch(body)
    }

    private fun headers(): Map<String, String> = mapOf(
        "User-Agent" to CHARTDRAMA_UA,
        "Accept" to "application/json",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to CHARTDRAMA_SITE + "/",
        "Origin" to CHARTDRAMA_SITE
    )
}
