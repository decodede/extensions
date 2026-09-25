package com.rulz

import org.jsoup.Jsoup

internal object TokenDownloadResolver {
    suspend fun resolveChain(
        target: HostTarget,
        referer: String,
        context: RulzResolverContext,
        providerName: String
    ): List<FoundStream> = try {
        resolveChainUnsafe(target, referer, context, providerName)
    } catch (error: Exception) {
        RulzResolverLog.failure(providerName, target, "token-chain", error)
        emptyList()
    }

    private suspend fun resolveChainUnsafe(
        target: HostTarget,
        referer: String,
        context: RulzResolverContext,
        providerName: String
    ): List<FoundStream> {
        if (isHlsUrl(target.url) || target.url.contains(".mp4", ignoreCase = true)) {
            return listOf(context.direct(target, referer))
        }

        RulzResolverLog.stage(providerName, target, "landing")
        val landing = context.text(target, target.url, referer, "landing")
            ?: return emptyResult(providerName, target, "landing-unavailable")
        val title = pageTitle(landing)
        val downloadPage = findLink(landing, target.url) { path ->
            path.contains("/download?", ignoreCase = true) && path.contains("token=", ignoreCase = true)
        }
        if (downloadPage.isEmpty() || !isPublicHttp(downloadPage)) {
            return emptyResult(providerName, target, "download-token-link-missing")
        }

        RulzResolverLog.stage(providerName, target, "download-page")
        val page = context.text(target, downloadPage, target.url, "download-page")
            ?: return emptyResult(providerName, target, "download-page-unavailable")
        val fileUrl = findLink(page, downloadPage) { path ->
            path.contains("/dl?", ignoreCase = true) && path.contains("code=", ignoreCase = true)
        }
        if (fileUrl.isEmpty() || !isPublicHttp(fileUrl)) {
            extractMediaCandidates(page, downloadPage).firstOrNull()?.url?.let { directUrl ->
                if (isPublicHttp(directUrl)) {
                    return listOf(
                        FoundStream(
                            directUrl,
                            target.label,
                            qualityRank("HD"),
                            downloadPage,
                            context.playbackHeaders(downloadPage, target.url)
                        )
                    )
                }
            }
            return emptyResult(providerName, target, "file-link-missing")
        }

        val measuredMb = Regex("""(\d+(?:\.\d+)?)\s*MB""", RegexOption.IGNORE_CASE)
            .find(page)?.groupValues?.get(1)
        val size = measuredMb?.let { "${it}MB" }.orEmpty().ifEmpty { parseSize(title) }
        return listOf(
            FoundStream(
                fileUrl,
                target.label + sizeSuffix(size),
                qualityRank(parseQuality(title)),
                downloadPage,
                context.playbackHeaders(downloadPage, target.url)
            )
        ).also { RulzResolverLog.success(providerName, target, it.size) }
    }

    internal fun findLink(html: String, baseUrl: String, predicate: (String) -> Boolean): String {
        val document = runCatching { Jsoup.parse(html, baseUrl) }.getOrNull() ?: return ""
        return document.select("a[href], form[action]").asSequence()
            .map { element ->
                val raw = element.attr("href").ifBlank { element.attr("action") }
                absAgainst(decodeEntities(raw), baseUrl)
            }
            .firstOrNull(predicate).orEmpty()
    }

    private fun emptyResult(providerName: String, target: HostTarget, reason: String): List<FoundStream> {
        RulzResolverLog.empty(providerName, target, reason)
        return emptyList()
    }
}
