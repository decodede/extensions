package com.rulz

import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.withTimeoutOrNull

internal object StreamWishResolver {
    private const val NAME = "StreamWish"
    private val RULE_HOSTS = setOf("dhcplay.com", "hglink.to", "hgcloud.to")
    private val RULE_MIRRORS = listOf(
        "https://hanerix.com",
        "https://audinifer.com",
        "https://vibuxer.com",
        "https://masukestin.com",
        "https://streamhls.to",
        "https://wishfast.top"
    )
    private val DMCA_MIRRORS = listOf(
        "https://hgplaycdn.com",
        "https://hglamioz.com",
        "https://niramirus.com",
        "https://playnixes.com",
        "https://medixiru.com",
        "https://streamwish.to",
        "https://strwish.xyz",
        "https://hlswish.com",
        "https://kswplayer.info",
        "https://nekowish.my.id",
        "https://multimovies.cloud",
        "https://katomen.store",
        "https://gradehgplus.com",
        "https://stbhg.click",
        "https://sfastwish.com",
        "https://playerwish.com"
    )

    fun matches(label: String, url: String): Boolean {
        val key = "$label $url".lowercase()
        val host = hostOf(url)
        return RULE_HOSTS.contains(host) || key.contains("wish")
    }

    suspend fun resolve(
        target: HostTarget,
        referer: String,
        context: RulzResolverContext
    ): List<FoundStream> {
        RulzResolverLog.start(NAME, target)
        val mediaId = Regex("""/(?:e|f|d)/([A-Za-z0-9]+)""").find(target.url)?.groupValues?.get(1)
            ?: Regex("""/([A-Za-z0-9]{6,})[/?#]?$""").find(target.url)?.groupValues?.get(1)
            ?: return emptyResult(target, "media-id-missing")

        RulzResolverLog.stage(NAME, target, "origin")
        fetchCandidate(target.url, target, referer, context).firstOrNull()?.let {
            return listOf(it).also { resolved -> RulzResolverLog.success(NAME, target, resolved.size) }
        }

        RulzResolverLog.fallback(NAME, target, "origin-empty")
        val mirrors = if (RULE_HOSTS.contains(hostOf(target.url))) RULE_MIRRORS else DMCA_MIRRORS
        val results = mirrors.amap { mirror ->
            fetchCandidate(mirror.trimEnd('/') + "/e/$mediaId", target, referer, context)
        }
        return results.firstOrNull { it.isNotEmpty() } ?: emptyResult(target, "all-mirrors-empty")
    }

    private suspend fun fetchCandidate(
        candidate: String,
        originalTarget: HostTarget,
        referer: String,
        context: RulzResolverContext
    ): List<FoundStream> {
        var html = context.text(originalTarget, candidate, referer, "candidate") ?: return emptyList()
        if (html.length < 3_000 && html.contains("Page is loading")) {
            RulzResolverLog.empty(NAME, originalTarget, "candidate-still-loading")
            return emptyList()
        }

        if (!html.contains("eval(function(p,a,c,k,e")) {
            val fileId = Regex("""\$\.cookie\('file_id',\s*'([^']+)'""").find(html)?.groupValues?.get(1).orEmpty()
            if (fileId.isNotEmpty()) {
                val aff = Regex("""\$\.cookie\('aff',\s*'([^']+)'""").find(html)?.groupValues?.get(1).orEmpty()
                val headers = context.headers(candidate, candidate).toMutableMap().apply {
                    put(
                        "Cookie",
                        "file_id=$fileId" + (if (aff.isEmpty()) "" else "; aff=$aff") +
                            "; ref_url=${hostOf(candidate)}"
                    )
                }
                try {
                    html = withTimeoutOrNull(15_000L) {
                        app.get(candidate, headers = headers, referer = referer).text
                    }.orEmpty()
                    if (html.isBlank()) RulzResolverLog.empty(NAME, originalTarget, "cookie-refresh-empty")
                } catch (error: Exception) {
                    RulzResolverLog.failure(NAME, originalTarget, "cookie-refresh", error)
                }
                if (html.isBlank()) return emptyList()
            }
        }

        val headers = context.playbackHeaders(candidate, candidate)
        val code = html + "\n" + unpackPacker(html)
        val streams = extractMediaCandidates(code, candidate)
            .map { media ->
                FoundStream(
                    media.url,
                    originalTarget.label,
                    qualityRank(media.quality),
                    media.url,
                    headers
                )
            }
            .filter { isPublicHttp(it.url) }
            .distinctBy { it.url }
        if (streams.isNotEmpty()) RulzResolverLog.success(NAME, originalTarget, streams.size)
        return streams
    }

    private fun emptyResult(target: HostTarget, reason: String): List<FoundStream> {
        RulzResolverLog.empty(NAME, target, reason)
        return emptyList()
    }
}
