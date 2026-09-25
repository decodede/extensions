package com.rulz

import com.lagradost.cloudstream3.app
import kotlinx.coroutines.withTimeoutOrNull

internal object StreamVinResolver {
    private const val NAME = "StreamVin"

    fun matches(label: String, url: String): Boolean {
        val key = "$label $url".lowercase()
        return listOf("streamvin", "fireplayer", "playerlare").any(key::contains)
    }

    suspend fun resolve(
        target: HostTarget,
        referer: String,
        context: RulzResolverContext
    ): List<FoundStream> {
        RulzResolverLog.start(NAME, target)
        val id = Regex("""/video/([A-Za-z0-9]+)""").find(target.url)?.groupValues?.get(1).orEmpty()
        if (id.isEmpty()) return emptyResult(target, "video-id-missing")

        val headers = context.headers(target.url, target.url).toMutableMap().apply {
            put("X-Requested-With", "XMLHttpRequest")
        }
        val playbackHeaders = context.playbackHeaders(target.url, target.url)
        val streams = LinkedHashMap<String, FoundStream>()
        try {
            RulzResolverLog.stage(NAME, target, "api")
            val data = withTimeoutOrNull(15_000L) {
                app.post(
                    originOf(target.url) + "/player/index.php?data=$id&do=getVideo",
                    headers = headers,
                    referer = target.url,
                    data = mapOf("hash" to id, "r" to referer)
                ).text
            }
            if (data == null) RulzResolverLog.empty(NAME, target, "api-timeout")

            val secured = jsonString(data.orEmpty(), "securedLink")
            val source = jsonString(data.orEmpty(), "videoSource")
            listOf(secured, source).filter { it.isNotEmpty() }.forEach {
                context.putStream(streams, it, "Auto", target, playbackHeaders)
            }
            jsonStringArray(data.orEmpty(), "downloadLinks").forEach {
                context.putStream(
                    streams,
                    it,
                    parseQuality(it).ifEmpty { "HD" },
                    target.copy(label = target.label + " Download"),
                    playbackHeaders
                )
            }
        } catch (error: Exception) {
            RulzResolverLog.failure(NAME, target, "api", error)
        }

        if (streams.isEmpty()) {
            RulzResolverLog.fallback(NAME, target, "api-empty")
            val html = context.text(target, target.url, referer, "page") ?: return emptyResult(target, "page-unavailable")
            val candidates = extractMediaCandidates(html + "\n" + unpackPacker(html), target.url)
            candidates.forEach { context.putStream(streams, it.url, it.quality, target, playbackHeaders) }
        }

        return streams.values.toList().also {
            if (it.isEmpty()) RulzResolverLog.empty(NAME, target, "api-and-page-empty")
            else RulzResolverLog.success(NAME, target, it.size)
        }
    }

    internal fun jsonString(json: String, key: String): String =
        Regex("\"$key\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
            .find(json)?.groupValues?.get(1).orEmpty()
            .let(::decodeEntities)

    private fun jsonStringArray(json: String, key: String): List<String> {
        val array = Regex("\"$key\"\\s*:\\s*\\[([^]]*)]")
            .find(json)?.groupValues?.get(1).orEmpty()
        return Regex("\"((?:\\\\.|[^\"\\\\])*)\"")
            .findAll(array)
            .map { decodeEntities(it.groupValues[1]) }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .toList()
    }

    private fun emptyResult(target: HostTarget, reason: String): List<FoundStream> {
        RulzResolverLog.empty(NAME, target, reason)
        return emptyList()
    }
}
