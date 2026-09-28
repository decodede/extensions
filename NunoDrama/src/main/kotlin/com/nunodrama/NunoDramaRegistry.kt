package com.nunodrama

import android.util.Log
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

object NunoDramaRegistry {

    private const val TAG = "NunoDrama"

    private const val REGISTRY_CACHE_MINUTES = 360

    /**
     * What most providers name their first section. Used only when the platform
     * page cannot be read, so a slow or blocked page costs one default instead
     * of a chain of guesses.
     */
    const val DEFAULT_CATEGORY = "foryou"

    private val ALTERNATE_CATEGORIES = listOf("all", "all_drama")

    private val categoryCache = ConcurrentHashMap<String, String>().apply {
        putAll(NunoDramaStore.loadCategories())
    }

    @Volatile
    private var providerCache: List<Provider> = emptyList()

    /**
     * Last-resort provider list, captured from the site's own platform index
     * and each verified to answer /api/section. The index page is served behind
     * an interactive Cloudflare challenge, so discovery cannot be the only way
     * to learn the catalogue: without this a challenged index means zero rails
     * and a blank home screen, which is exactly what happened. A live discovery
     * still replaces this whenever the index can be read.
     */
    private val FALLBACK_PROVIDERS: Map<String, String> = linkedMapOf(
        "dynastyshorts" to "Dynasty Shorts",
        "dramaverse" to "DramaVerse",
        "flickreels" to "FlickReels",
        "happyshort" to "HappyShort",
        "lookseries" to "LookSeries",
        "microdrama" to "MicroDrama",
        "shortswave" to "ShortsWave",
        "sixthshort" to "SixthShort",
        "snackshort" to "SnackShort",
        "dramabite" to "Dramabite",
        "dramarush" to "Dramarush",
        "dramawave" to "DramaWave",
        "freereels" to "FreeReels",
        "freeshort" to "FreeShort",
        "goodshort" to "GoodShort",
        "meloshort" to "MeloShort",
        "minishort" to "Minishort",
        "moboreels" to "MoboReels",
        "moreshort" to "MoreShort",
        "reeltales" to "ReelShort",
        "sodareels" to "SodaReels",
        "storyreel" to "StoryReel",
        "vibeshort" to "VibeShort",
        "bstation" to "Bstation",
        "dotdrama" to "DotDrama",
        "drakorid" to "DrakorID",
        "dramabox" to "DramaBox",
        "fundrama" to "FunDrama",
        "huangdou" to "HuangDou",
        "lupacine" to "Lupacine",
        "netshort" to "NetShort",
        "pinewave" to "PineDrama",
        "reellife" to "ReelLife",
        "shortmax" to "ShortMax",
        "stardust" to "Stardust",
        "anyreel" to "AnyReel",
        "bonustv" to "BonusTV",
        "donghua" to "Donghua",
        "kalostv" to "KalosTV",
        "mydrama" to "MyDrama",
        "nunomix" to "NunoMix",
        "playlet" to "Playlet",
        "radreel" to "RadReel",
        "reelala" to "Reelala",
        "stareel" to "StarReel",
        "cubetv" to "CubeTV",
        "drakor" to "Drakor",
        "flextv" to "FlexTV",
        "idrama" to "iDrama",
        "melolo" to "Melolo",
        "mymuse" to "My Muse",
        "soreel" to "SoReel",
        "velolo" to "Velolo",
        "vigloo" to "Vigloo",
        "anime" to "Anime",
        "wetv" to "WeTV",
    )


    private val linkTag = Regex("""<[^>]*\bdata-platform-link\b[^>]*>""")
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
            // Never return empty. An empty list means no rails at all, and
            // CloudStream shows a blank home screen for that. The catalogue is
            // known even when the index page cannot be read.
            val fallback = FALLBACK_PROVIDERS.map { (slug, name) -> Provider(slug, name) }
            Log.w(TAG, "provider discovery blocked, serving ${fallback.size} known providers")
            providerCache = fallback
            return fallback
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

    private fun attributes(tag: String): Map<String, String> =
        attribute.findAll(tag).associate { it.groupValues[1] to it.groupValues[2] }

    /**
     * The name a rail should use. Never touches the network: the site answers
     * foryou for most providers, so learning the exceptions is done by the rail
     * that actually came back empty, not by walking 56 html pages up front.
     */
    fun categoryFor(slug: String): String = categoryCache[slug] ?: DEFAULT_CATEGORY

    /**
     * Called only when a rail's own section came back empty. Tries the handful
     * of alternate names once, and keeps whichever works, so a provider that
     * needs something other than foryou costs one extra small json read - once,
     * ever, and remembered after that.
     */
    suspend fun relearn(slug: String): String? {
        categoryCache[slug]?.takeIf { it != DEFAULT_CATEGORY }?.let { return it }
        return NunoDramaClient.categoryLock(slug).withLock {
            categoryCache[slug]?.takeIf { it != DEFAULT_CATEGORY }?.let { return@withLock it }
            for (candidate in ALTERNATE_CATEGORIES) {
                val section = NunoDramaClient.getSection(slug, candidate, 1, null) ?: continue
                if (section.dramas.isNotEmpty()) {
                    categoryCache[slug] = candidate
                    NunoDramaStore.saveCategories(categoryCache)
                    Log.i(TAG, "learned category $candidate for $slug")
                    return@withLock candidate
                }
            }
            null
        }
    }

    /**
     * Drops memory only. The persisted list stays put as a fallback so a failed
     * re-discovery leaves the rails usable instead of blanking the provider.
     */
    fun invalidate() {
        providerCache = emptyList()
        categoryCache.clear()
        NunoDramaStore.saveCategories(emptyMap())
    }
}
