package com.rulz

import com.lagradost.cloudstream3.app
import kotlinx.coroutines.withTimeoutOrNull

internal object StreamLareResolver {
    private const val NAME = "StreamLare"

    fun matches(label: String, url: String): Boolean {
        val key = "$label $url".lowercase()
        return listOf("streamlare", "vcdnlare", "slmaxed", "vcdnx").any(key::contains)
    }

    suspend fun resolve(
        target: HostTarget,
        referer: String,
        context: RulzResolverContext
    ): List<FoundStream> {
        RulzResolverLog.start(NAME, target)
        val headers = context.headers(target.url, referer)
        val mediaHeaders = context.playbackHeaders(target.url, referer).toMutableMap()
            .apply { put("Origin", originOf(referer)) }
        val streams = LinkedHashMap<String, FoundStream>()
        val id = Regex("""/[ve]/([^/?#]+)""", RegexOption.IGNORE_CASE)
            .find(target.url)?.groupValues?.get(1)

        if (!id.isNullOrEmpty()) {
            RulzResolverLog.stage(NAME, target, "api")
            try {
                val body = withTimeoutOrNull(12_000L) {
                    app.post(
                        originOf(target.url) + "/api/video/stream/get",
                        headers = headers,
                        referer = referer,
                        json = org.json.JSONObject().put("id", id)
                    ).text
                }
                if (body == null) RulzResolverLog.empty(NAME, target, "api-timeout")
                extractMediaCandidates(body.orEmpty(), target.url).forEach {
                    context.putStream(streams, it.url, it.quality, target, mediaHeaders, referer)
                }
            } catch (error: Exception) {
                RulzResolverLog.failure(NAME, target, "api", error)
            }
        }

        if (streams.isEmpty()) {
            RulzResolverLog.fallback(NAME, target, if (id.isNullOrEmpty()) "no-media-id" else "api-empty")
            val html = context.text(target, target.url, referer, "page") ?: return emptyResult(target)
            val code = html + "\n" + unpackPacker(html)
            val pageHeaders = context.playbackHeaders(target.url, referer).toMutableMap()
                .apply { put("Origin", originOf(referer)) }
            extractMediaCandidates(code, target.url).forEach {
                context.putStream(streams, it.url, it.quality, target, pageHeaders, referer)
            }
        }

        return streams.values.toList().also {
            if (it.isEmpty()) RulzResolverLog.empty(NAME, target, "api-and-page-empty")
            else RulzResolverLog.success(NAME, target, it.size)
        }
    }

    private fun emptyResult(target: HostTarget): List<FoundStream> {
        RulzResolverLog.empty(NAME, target, "page-unavailable")
        return emptyList()
    }
}
