package com.finddrama

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

@Serializable
private data class ProviderList(
    val at: Long = 0L,
    val items: List<Provider> = emptyList(),
    val public: List<Int> = emptyList(),
)

@Serializable
private data class RailList(
    val items: Map<String, CachedRail> = emptyMap(),
)

@Serializable
private data class EpisodeList(
    val items: Map<String, CachedEpisodes> = emptyMap(),
)

object FindDramaStore {
    private const val PREFS = "finddrama_cache"
    private const val KEY_PROVIDERS = "providers"
    private const val KEY_RAILS = "rails"
    private const val KEY_EPISODES = "episodes"
    private const val KEY_BASE = "base_url"

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var railIndex: ConcurrentHashMap<String, CachedRail>? = null

    @Volatile
    private var episodeIndex: ConcurrentHashMap<String, CachedEpisodes>? = null

    private val railLock = Any()
    private val episodeLock = Any()

    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs == null) prefs = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    fun base(): String = normalizeBase(prefs?.getString(KEY_BASE, null)) ?: Gl.DEFAULT_SITE

    fun saveBase(raw: String?): Boolean {
        val current = base()
        val candidate = normalizeBase(raw) ?: return false
        if (candidate == current) return false
        prefs?.edit()?.putString(KEY_BASE, candidate)?.apply() ?: return false
        railIndex = null
        episodeIndex = null
        prefs?.edit()?.remove(KEY_RAILS)?.remove(KEY_EPISODES)?.apply()
        return true
    }

    fun clearCache() {
        railIndex = null
        episodeIndex = null
        prefs?.edit()?.remove(KEY_PROVIDERS)?.remove(KEY_RAILS)?.remove(KEY_EPISODES)?.apply()
    }

    fun loadProviders(ttlMinutes: Long): Pair<List<Provider>, List<Int>>? {
        val raw = prefs?.getString(KEY_PROVIDERS, null) ?: return null
        val list = runCatching { json.decodeFromString<ProviderList>(raw) }.getOrNull() ?: return null
        if (list.items.isEmpty()) return null
        if (System.currentTimeMillis() - list.at > ttlMinutes * 60_000L) return null
        return list.items to list.public
    }

    fun saveProviders(providers: List<Provider>, publicIds: List<Int>) {
        val raw = runCatching {
            json.encodeToString(ProviderList(System.currentTimeMillis(), providers, publicIds))
        }.getOrNull() ?: return
        prefs?.edit()?.putString(KEY_PROVIDERS, raw)?.apply()
    }

    fun loadRail(key: String, ttlMinutes: Long): CachedRail? {
        val hit = rails()[key] ?: return null
        return if (fresh(hit.at, ttlMinutes)) hit else null
    }

    fun saveRail(key: String, rail: CachedRail) {
        if (rail.items.isEmpty()) return
        synchronized(railLock) {
            val all = rails()
            all[key] = rail.copy(at = System.currentTimeMillis())
            evict(all) { it.at }
            write(KEY_RAILS, RailList(all))
        }
    }

    fun loadEpisodes(dramaId: String, ttlMinutes: Long): CachedEpisodes? {
        val hit = episodes()[dramaId] ?: return null
        return if (fresh(hit.at, ttlMinutes)) hit else null
    }

    fun saveEpisodes(dramaId: String, value: CachedEpisodes) {
        if (value.items.isEmpty()) return
        synchronized(episodeLock) {
            val all = episodes()
            all[dramaId] = value.copy(at = System.currentTimeMillis())
            evict(all) { it.at }
            write(KEY_EPISODES, EpisodeList(all))
        }
    }

    private fun fresh(at: Long, ttlMinutes: Long) =
        System.currentTimeMillis() - at <= ttlMinutes * 60_000L

    private fun <T> evict(all: ConcurrentHashMap<String, T>, stamp: (T) -> Long) {
        while (all.size > Gl.MAX_CACHED_RAILS) {
            val oldest = all.minByOrNull { stamp(it.value) }?.key ?: break
            all.remove(oldest)
        }
    }

    private inline fun <reified T> write(key: String, value: T) {
        val raw = runCatching { json.encodeToString(value) }.getOrNull() ?: return
        prefs?.edit()?.putString(key, raw)?.apply()
    }

    private fun rails(): ConcurrentHashMap<String, CachedRail> {
        railIndex?.let { return it }
        synchronized(railLock) {
            railIndex?.let { return it }
            val raw = prefs?.getString(KEY_RAILS, null)
            val loaded = ConcurrentHashMap<String, CachedRail>()
            if (raw != null) {
                runCatching { loaded.putAll(json.decodeFromString<RailList>(raw).items) }
                    .onFailure { loaded.clear() }
            }
            railIndex = loaded
            return loaded
        }
    }

    private fun episodes(): ConcurrentHashMap<String, CachedEpisodes> {
        episodeIndex?.let { return it }
        synchronized(episodeLock) {
            episodeIndex?.let { return it }
            val raw = prefs?.getString(KEY_EPISODES, null)
            val loaded = ConcurrentHashMap<String, CachedEpisodes>()
            if (raw != null) {
                runCatching { loaded.putAll(json.decodeFromString<EpisodeList>(raw).items) }
                    .onFailure { loaded.clear() }
            }
            episodeIndex = loaded
            return loaded
        }
    }
}

private val hostName = Regex("""[a-z0-9]([a-z0-9\-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9\-]*[a-z0-9])?)*""")

fun normalizeBase(input: String?): String? {
    val trimmed = input?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
    val withScheme =
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
        else "https://$trimmed"
    val uri = runCatching { java.net.URI(withScheme) }.getOrNull() ?: return null
    if (uri.scheme !in setOf("http", "https")) return null
    if (uri.userInfo != null) return null
    if (uri.query != null || uri.fragment != null) return null
    if (!uri.path.isNullOrEmpty() && uri.path != "/") return null
    val host = uri.host?.lowercase()?.takeIf { hostName.matches(it) && it.contains('.') }
        ?: return null
    val port = if (uri.port > 0) ":${uri.port}" else ""
    return "${uri.scheme}://$host$port"
}
