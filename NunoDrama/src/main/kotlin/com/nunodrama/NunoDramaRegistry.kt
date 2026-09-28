package com.nunodrama

import java.util.concurrent.ConcurrentHashMap

object NunoDramaRegistry {

    const val RAIL_ALL = "__all__"

    private const val REGISTRY_CACHE_MINUTES = 360
    private const val CATEGORY_CACHE_MINUTES = 360
    private const val HEADING_WINDOW = 1500
    private const val PREWARM_PARALLELISM = 3

    private val FALLBACK_CATEGORIES = listOf(
        "foryou", "all", "all_drama", "recommend", "movie", "terbaru", "trending", "alldrama", "asian", "drama",
    )

    private val categoryCache = ConcurrentHashMap<String, String>()

    @Volatile
    private var providerCache: List<Provider> = emptyList()

    private val linkTag = Regex("""<[^>]*\bdata-platform-link\b[^>]*>""")
    private val sectionTag = Regex("""<[^>]*\bdata-inf-section\b[^>]*>""")
    private val headingTag = Regex("""<h2[^>]*>([^<]{1,60})</h2>""")
    private val attribute = Regex("""([a-zA-Z0-9-]+)\s*=\s*"([^"]*)\"""")

    suspend fun providers(): List<Provider> {
        providerCache.takeIf { it.isNotEmpty() }?.let { return it }
        val html = NunoDramaClient.getHtml("/", cacheMinutes = REGISTRY_CACHE_MINUTES) ?: return emptyList()
        val parsed = parse(html)
        if (parsed.isNotEmpty()) providerCache = parsed
        return parsed
    }

    fun cached(): List<Provider> = providerCache

    fun parse(html: String): List<Provider> {
        val out = LinkedHashMap<String, Provider>()
        for (tag in linkTag.findAll(html)) {
            val attrs = attributes(tag.value)
            val slug = attrs["data-slug"]?.trim().orEmpty()
            if (slug.isEmpty() || out.containsKey(slug)) continue
            val name = attrs["data-name"]?.trim().orEmpty()
            out[slug] = Provider(
                slug = slug,
                name = name.ifEmpty { slug.replaceFirstChar(Char::uppercase) },
            )
        }
        return out.values.toList()
    }

    fun parseCategories(html: String): List<Pair<String, String>> = sectionTag.findAll(html)
        .map { tag ->
            val category = attributes(tag.value)["data-category"]?.trim().orEmpty()
            val from = tag.range.last
            val window = html.substring(from.coerceAtMost(html.length), (from + HEADING_WINDOW).coerceAtMost(html.length))
            category to (headingTag.find(window)?.groupValues?.get(1)?.trim().orEmpty())
        }
        .filter { it.first.isNotEmpty() }
        .distinctBy { it.first }
        .toList()

    private fun attributes(tag: String): Map<String, String> =
        attribute.findAll(tag).associate { it.groupValues[1] to it.groupValues[2] }

    suspend fun categoryOf(slug: String): String {
        categoryCache[slug]?.takeIf { it.isNotEmpty() }?.let { return it }
        val discovered = NunoDramaClient.getHtml("/platform/$slug?next=/", slug, CATEGORY_CACHE_MINUTES)
            ?.let { parseCategories(it) }
            ?.firstOrNull()
            ?.first
        val resolved = discovered?.takeIf { it.isNotEmpty() } ?: probeCategory(slug)
        if (resolved.isNotEmpty()) categoryCache[slug] = resolved
        return resolved
    }

    suspend fun prewarmCategories() {
        val pending = providers().filter { categoryCache[it.slug].isNullOrEmpty() }
        NunoDramaClient.mapBounded(pending, PREWARM_PARALLELISM) { provider ->
            categoryOf(provider.slug)
        }
    }

    private suspend fun probeCategory(slug: String): String {
        for (candidate in FALLBACK_CATEGORIES) {
            val section = NunoDramaClient.getSection(slug, candidate, 1, null) ?: continue
            if (section.dramas.isNotEmpty()) return candidate
        }
        return ""
    }

    fun invalidate() {
        providerCache = emptyList()
        categoryCache.clear()
    }
}
