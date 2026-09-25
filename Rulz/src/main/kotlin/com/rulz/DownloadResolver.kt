package com.rulz

internal object DownloadResolver {
    private const val NAME = "Download"

    fun matches(label: String, url: String): Boolean =
        label.contains("download", ignoreCase = true) || "$label $url".lowercase().contains("/download/")

    suspend fun resolve(
        target: HostTarget,
        referer: String,
        context: RulzResolverContext
    ): List<FoundStream> {
        RulzResolverLog.start(NAME, target)
        return TokenDownloadResolver.resolveChain(target, referer, context, NAME)
    }
}
