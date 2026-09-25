package com.rulz

internal object GenericResolver {
    private const val NAME = "Generic"

    suspend fun resolve(
        target: HostTarget,
        referer: String,
        context: RulzResolverContext
    ): List<FoundStream> {
        RulzResolverLog.start(NAME, target)
        val html = context.text(target, target.url, referer, "page")
        if (html.isNullOrEmpty()) {
            RulzResolverLog.empty(NAME, target, "page-unavailable")
            return emptyList()
        }

        val code = html + "\n" + unpackPacker(html)
        val headers = context.playbackHeaders(target.url, target.url)
        val streams = LinkedHashMap<String, FoundStream>()
        extractMediaCandidates(code, target.url).forEach {
            context.putStream(streams, it.url, it.quality, target, headers)
        }
        return streams.values.toList().also {
            if (it.isEmpty()) RulzResolverLog.empty(NAME, target, "no-media-pattern")
            else RulzResolverLog.success(NAME, target, it.size)
        }
    }
}
