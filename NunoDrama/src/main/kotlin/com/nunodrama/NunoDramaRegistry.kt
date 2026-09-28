package com.nunodrama

import android.util.Log
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

object NunoDramaRegistry {

    private const val TAG = "NunoDrama"

    private const val REGISTRY_CACHE_MINUTES = 360
    private const val CATEGORY_CACHE_MINUTES = 360
    private const val HEADING_WINDOW = 1500

    /**
     * Probing is a last resort, so it stays short. Every candidate costs a
     * request, and a wrong guess here is paid for by all 56 rails.
     */
    private val FALLBACK_CATEGORIES = listOf("foryou", "all", "all_drama")

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
     * Two rails asking for the same provider at once must not both go and
     * discover it, so the work is done under a per slug lock and the second
     * caller re-checks the cache.
     */
    suspend fun categoryOf(slug: String): String {
        categoryCache[slug]?.takeIf { it.isNotEmpty() }?.let { return it }
        return NunoDramaClient.categoryLock(slug).withLock {
            categoryCache[slug]?.takeIf { it.isNotEmpty() }?.let { return@withLock it }
            val html = NunoDramaClient.getHtml("/platform/$slug?next=/", slug, CATEGORY_CACHE_MINUTES)
            val discovered = html?.let { parseCategories(it) }?.firstOrNull()?.first
            val resolved = discovered?.takeIf { it.isNotEmpty() } ?: probeCategory(slug)
            if (resolved.isNotEmpty()) {
                categoryCache[slug] = resolved
            } else {
                Log.w(TAG, "no category for $slug")
            }
            resolved
        }
    }

    private suspend fun probeCategory(slug: String): String {
        for (candidate in FALLBACK_CATEGORIES) {
            val section = NunoDramaClient.getSection(slug, candidate, 1, null) ?: continue
            if (section.dramas.isNotEmpty()) return candidate
        }
        return ""
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
