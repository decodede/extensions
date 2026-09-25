package com.rulz

import org.jsoup.Jsoup
import java.net.URI

internal object UperBoxResolver {
    private const val NAME = "UperBox"
    private val TLDS = setOf("net", "io", "com", "cx")

    fun matches(label: String, url: String): Boolean =
        "$label $url".lowercase().contains("uperbox")

    suspend fun resolve(
        target: HostTarget,
        referer: String,
        context: RulzResolverContext
    ): List<FoundStream> {
        RulzResolverLog.start(NAME, target)
        val normalized = normalize(target.url)
        val chained = TokenDownloadResolver.resolveChain(
            target.copy(url = normalized),
            referer,
            context,
            NAME
        )
        if (chained.isNotEmpty()) return chained.also { RulzResolverLog.success(NAME, target, it.size) }

        RulzResolverLog.fallback(NAME, target, "token-chain-empty")
        val landing = context.text(target, normalized, referer, "landing")
            ?: return emptyResult(target, "landing-unavailable")
        val title = pageTitle(landing)
        val firstHop = buttonLink(landing, normalized)
        if (firstHop.isEmpty() || !isPublicHttp(firstHop)) {
            return emptyResult(target, "first-hop-missing")
        }

        RulzResolverLog.stage(NAME, target, "first-hop")
        val secondPage = context.text(target, firstHop, normalized, "first-hop")
            ?: return emptyResult(target, "first-hop-unavailable")
        val finalUrl = buttonLink(secondPage, firstHop)
        if (finalUrl.isEmpty() || !isPublicHttp(finalUrl)) {
            return emptyResult(target, "final-hop-missing")
        }

        return listOf(
            FoundStream(
                finalUrl,
                target.label + sizeSuffix(title),
                qualityRank(parseQuality(title)),
                firstHop,
                context.playbackHeaders(firstHop, normalized)
            )
        ).also { RulzResolverLog.success(NAME, target, it.size) }
    }

    private fun buttonLink(html: String, baseUrl: String): String {
        val document = runCatching { Jsoup.parse(html, baseUrl) }.getOrNull() ?: return ""
        val href = document.select("a.btn, a[class~=btn], button.btn").firstOrNull()?.attr("href").orEmpty()
        return absAgainst(decodeEntities(href), baseUrl)
    }

    private fun normalize(pageUrl: String): String = runCatching {
        val uri = URI(pageUrl)
        val parts = uri.host.orEmpty().lowercase().split('.')
        if (parts.size == 2 && parts[0] == "uperbox" && parts[1] in TLDS) {
            "https://www." + uri.host + uri.path + (uri.query?.let { "?$it" }.orEmpty())
        } else {
            pageUrl
        }
    }.getOrDefault(pageUrl)

    private fun emptyResult(target: HostTarget, reason: String): List<FoundStream> {
        RulzResolverLog.empty(NAME, target, reason)
        return emptyList()
    }
}
