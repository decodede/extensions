package com.nunodrama

import android.util.Log
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

object NunoDramaRegistry {

    private const val TAG = "NunoDrama"

    private const val REGISTRY_CACHE_MINUTES = 360
    private const val CATEGORY_CACHE_MINUTES = 360
    private const val HEADING_WINDOW = 1500

    /**
     * What most providers name their first section. Used only when the platform
     * page cannot be read, so a slow or blocked page costs one default instead
     * of a chain of guesses.
     */
    const val DEFAULT_CATEGORY = "foryou"

    private const val CATEGORY_BUDGET_MS = 25_000L

    private val categoryCache = ConcurrentHashMap<String, String>()

    @Volatile
    private var providerCache: List<Provider> = emptyList()

    private val linkTag = Regex("""<[^>]*\bdata-platform-link\b[^>]*>""")
    private val sectionTag = Regex("""<[^>]*\bdata-inf-section\b[^>]*>""")
    private val headingTag = Regex("""<h2[^>]*>([^<]{1,60})</h2>""")
    private val attribute = Regex("""([a-zA-Z0-9-]+)\s*=\s*"([^"]*)\"""")

    suspend fun providers(): List<Provider> {
        providerCache.takeIf { it.isNotEmpty() }?.let { return it }
        val stored = NunoDramaStore.loadProviders()
        if (stored.isNotEmpty()) {
            providerCache = stored
            Log.i(TAG, "providers restored from disk: ${stored.size}")
            return stored
        }
        val html = NunoDramaClient.getHtml("/", cacheMinutes = REGISTRY_CACHE_MINUTES)
        if (html == null) {
            Log.w(TAG, "provider discovery failed, no rails available")
            return emptyList()
        }
        val parsed = parse(html)
        if (parsed.isEmpty()) {
            Log.w(TAG, "provider discovery matched 0 providers in ${html.length} bytes")
            return emptyList()
        }
        providerCache = parsed
        NunoDramaStore.saveProviders(parsed)
        Log.i(TAG, "providers discovered: ${parsed.size}")
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

    /**
     * Never touches the network. The merged rail calls this so it stays a
     * handful of small json reads instead of 56 slow html pages, and a
     * provider's own rail does the real discovery when it is opened.
     */
    fun categoryOrDefault(slug: String): String = categoryCache[slug] ?: DEFAULT_CATEGORY

    /**
     * Two rails asking for the same provider at once must not both go and
     * discover it, so the work is done under a per slug lock and the second
     * caller re-checks the cache.
     */
    suspend fun categoryOf(slug: String): String {
        categoryCache[slug]?.let { return it }
        return NunoDramaClient.categoryLock(slug).withLock {
            categoryCache[slug]?.let { return@withLock it }
            // CloudStream gives a rail a fixed budget, so discovery has to give up
            // on its own rather than spend the rail's whole allowance guessing.
            val html = withTimeoutOrNull(CATEGORY_BUDGET_MS) {
                NunoDramaClient.getHtml("/platform/$slug?next=/", slug, CATEGORY_CACHE_MINUTES)
            }
            val resolved = html
                ?.let { parseCategories(it) }
                ?.firstOrNull()
                ?.first
                ?.takeIf { it.isNotEmpty() }
                ?: DEFAULT_CATEGORY
            if (html == null) Log.w(TAG, "category page slow for $slug, using $DEFAULT_CATEGORY")
            categoryCache[slug] = resolved
            resolved
        }
    }

    /**
     * Drops memory only. The persisted list stays put as a fallback so a failed
     * re-discovery leaves the rails usable instead of blanking the provider.
     */
    fun invalidate() {
        providerCache = emptyList()
        categoryCache.clear()
    }
}
