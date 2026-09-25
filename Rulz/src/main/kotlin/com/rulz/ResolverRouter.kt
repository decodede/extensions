package com.rulz

internal object RulzResolverRouter {
    suspend fun resolve(
        target: HostTarget,
        referer: String,
        context: RulzResolverContext
    ): List<FoundStream> {
        val provider = when {
            DownloadResolver.matches(target.label, target.url) -> "Download"
            EasySyncResolver.matches(target.label, target.url) -> "EasySync"
            UperBoxResolver.matches(target.label, target.url) -> "UperBox"
            StreamVinResolver.matches(target.label, target.url) -> "StreamVin"
            StreamLareResolver.matches(target.label, target.url) -> "StreamLare"
            StreamWishResolver.matches(target.label, target.url) -> "StreamWish"
            FileLionsResolver.matches(target.label, target.url) -> "FileLions"
            isHlsUrl(target.url) || target.url.endsWith(".mp4", true) || target.url.endsWith(".mkv", true) -> "Direct"
            else -> "Generic"
        }

        RulzResolverLog.stage("Router", target, "provider=$provider")
        if (provider == "Direct") {
            return listOf(context.direct(target, referer)).also {
                RulzResolverLog.success(provider, target, it.size)
            }
        }

        val result = try {
            when (provider) {
                "Download" -> DownloadResolver.resolve(target, referer, context)
                "EasySync" -> EasySyncResolver.resolve(target, referer, context)
                "UperBox" -> UperBoxResolver.resolve(target, referer, context)
                "StreamVin" -> StreamVinResolver.resolve(target, referer, context)
                "StreamLare" -> StreamLareResolver.resolve(target, referer, context)
                "StreamWish" -> StreamWishResolver.resolve(target, referer, context)
                "FileLions" -> FileLionsResolver.resolve(target, referer, context)
                else -> GenericResolver.resolve(target, referer, context)
            }
        } catch (error: Exception) {
            RulzResolverLog.failure(provider, target, "resolver", error)
            emptyList()
        }
        if (result.isNotEmpty() || provider == "Generic") return result

        RulzResolverLog.fallback(provider, target, "provider-empty")
        return GenericResolver.resolve(target, referer, context)
    }
}
