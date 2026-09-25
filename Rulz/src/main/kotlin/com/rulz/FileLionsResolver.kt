package com.rulz

import java.net.URI

internal object FileLionsResolver {
    private const val NAME = "FileLions"
    private const val FALLBACK_HOST = "callistanise.com"
    private val DEAD_HOSTS = setOf(
        "filelions.com", "filelions.to", "filelions.live", "filelions.xyz",
        "filelions.online", "filelions.site", "filelions.co", "ajmidyadfihayh.sbs",
        "alhayabambi.sbs", "vidhideplus.com", "vidhidepro.com", "vidhidevip.com",
        "vidhidepre.com", "vidhidefun.com", "vidhidefast.com", "azipcdn.com",
        "mlions.pro", "alions.pro", "dlions.pro", "mivalyo.com", "motvy55.store",
        "lumiawatch.top", "fviplions.com", "egsyxutd.sbs", "e4xb5c2xnz.sbs",
        "taylorplayer.com", "ryderjet.com", "techradar.ink", "anime7u.com",
        "coolciima.online", "gsfomqu.sbs", "bingezove.com", "katomen.online",
        "6sfkrspw4u.sbs", "dingtezuni.com", "dinisglows.com", "dintezuvio.com",
        "vidhide.com", "minochinos.com", "morencius.com"
    )

    fun matches(label: String, url: String): Boolean {
        val key = "$label $url".lowercase()
        val host = hostOf(url)
        return DEAD_HOSTS.contains(host) || DEAD_HOSTS.any { host.endsWith(".$it") } ||
            listOf(
                "filelion", "vidhide", "minochinos", "callistanise", "morencius",
                "kinoger", "earnvids", "/f/", "/v/", "/s/", "/embed/"
            ).any(key::contains)
    }

    suspend fun resolve(
        target: HostTarget,
        referer: String,
        context: RulzResolverContext
    ): List<FoundStream> {
        RulzResolverLog.start(NAME, target)
        val normalizedUrl = playableUrl(target.url)
        if (normalizedUrl.isEmpty()) return emptyResult(target, "invalid-url")
        val resolvedTarget = target.copy(url = normalizedUrl)
        val html = context.text(resolvedTarget, normalizedUrl, referer, "embed")
            ?: return emptyResult(target, "embed-unavailable")
        val code = html + "\n" + unpackPacker(html)
        val host = hostOf(normalizedUrl)
        val headers = context.playbackHeaders(normalizedUrl, normalizedUrl)
        val streams = LinkedHashMap<String, FoundStream>()

        val links = Regex("""var\s+links\s*=\s*(\{[^}]+\})""").find(code)?.groupValues?.get(1)
        if (links != null) {
            for (key in listOf("hls2", "hls3", "hls4")) {
                val match = Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"").find(links) ?: continue
                context.putStream(
                    streams,
                    fixHost(match.groupValues[1], host),
                    "Auto",
                    resolvedTarget,
                    headers
                )
            }
        }
        if (streams.isEmpty()) {
            for (match in Regex(
                """sources\s*:\s*\[\s*\{\s*file\s*:\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            ).findAll(code)) {
                context.putStream(
                    streams,
                    fixHost(match.groupValues[1], host),
                    "Auto",
                    resolvedTarget,
                    headers
                )
            }
        }
        if (streams.isEmpty()) {
            RulzResolverLog.fallback(NAME, target, "jw-player-empty")
            extractMediaCandidates(code, normalizedUrl).forEach {
                context.putStream(streams, it.url, it.quality, resolvedTarget, headers)
            }
        }

        return streams.values.toList().also {
            if (it.isEmpty()) RulzResolverLog.empty(NAME, target, "no-hls-source")
            else RulzResolverLog.success(NAME, target, it.size)
        }
    }

    private fun playableUrl(pageUrl: String): String = runCatching {
        val uri = URI(pageUrl)
        var host = uri.host.orEmpty().lowercase()
        if (DEAD_HOSTS.contains(host) || DEAD_HOSTS.any { host.endsWith(".$it") }) host = FALLBACK_HOST
        val mediaId = Regex("""/(?:f|v|file|embed|download)/([^/?#]+)""")
            .find(pageUrl)?.groupValues?.get(1)
        val path = if (mediaId != null) "/v/$mediaId" else uri.path.orEmpty()
        "https://$host$path" + (uri.query?.let { "?$it" }.orEmpty())
    }.getOrDefault("")

    private fun fixHost(link: String, host: String): String {
        val clean = link.trim()
        return when {
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> "https://$host$clean"
            else -> clean
        }
    }

    private fun emptyResult(target: HostTarget, reason: String): List<FoundStream> {
        RulzResolverLog.empty(NAME, target, reason)
        return emptyList()
    }
}
