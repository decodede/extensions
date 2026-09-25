package com.rulz

internal object EasySyncResolver {
    private const val NAME = "EasySync"

    fun matches(label: String, url: String): Boolean {
        val key = "$label $url".lowercase()
        return key.contains("easysyncr") || key.contains("easysync")
    }

    suspend fun resolve(
        target: HostTarget,
        referer: String,
        context: RulzResolverContext
    ): List<FoundStream> {
        RulzResolverLog.start(NAME, target)
        return TokenDownloadResolver.resolveChain(target, referer, context, NAME)
    }
}
