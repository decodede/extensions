package com.nunodrama

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class Provider(
    val slug: String,
    val name: String,
)

@Serializable
private data class ProviderList(val items: List<Provider> = emptyList())

@Serializable
private data class CategoryList(val items: Map<String, String> = emptyMap())

@Serializable
data class CachedPage(
    val at: Long = 0L,
    val next: String? = null,
    val items: List<DramaDto> = emptyList(),
)

@Serializable
private data class PageList(val items: Map<String, CachedPage> = emptyMap())

object NunoDramaStore {
    private const val PREFS = "nunodrama_prefs"
    private const val KEY_BASE = "base_url"
    private const val KEY_LANG = "site_lang"
    private const val KEY_PROVIDERS = "providers_json"
    private const val KEY_CATEGORIES = "categories_json"
    private const val KEY_PAGES = "pages_json"

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) synchronized(this) {
            if (prefs == null) prefs = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    fun base(): String = normalizeBase(prefs?.getString(KEY_BASE, null)) ?: DEFAULT_BASE

    fun saveBase(raw: String?): Boolean {
        val value = normalizeBase(raw) ?: return false
        prefs?.edit()?.putString(KEY_BASE, value)?.apply() ?: return false
        return true
    }

    fun language(): String = prefs?.getString(KEY_LANG, LANG_EN)?.takeIf { it == LANG_ID || it == LANG_EN } ?: LANG_EN

    fun saveLanguage(value: String): Boolean {
        if (value != LANG_ID && value != LANG_EN) return false
        prefs?.edit()?.putString(KEY_LANG, value)?.apply() ?: return false
        return true
    }

    /**
     * The rail list has to be readable without the network, otherwise a cold
     * start shows an empty provider until the first fetch lands.
     */
    fun loadProviders(): List<Provider> {
        val raw = prefs?.getString(KEY_PROVIDERS, null) ?: return emptyList()
        return runCatching { json.decodeFromString<ProviderList>(raw).items }.getOrDefault(emptyList())
    }

    fun saveProviders(items: List<Provider>) {
        val raw = runCatching { json.encodeToString(ProviderList(items)) }.getOrNull() ?: return
        prefs?.edit()?.putString(KEY_PROVIDERS, raw)?.apply()
    }

    /**
     * Category names are stable and cost a request to learn, so they are kept
     * across launches. This is what takes the platform page off the home screen
     * path entirely after the first run.
     */
    fun loadCategories(): Map<String, String> {
        val raw = prefs?.getString(KEY_CATEGORIES, null) ?: return emptyMap()
        return runCatching { json.decodeFromString<CategoryList>(raw).items }.getOrDefault(emptyMap())
    }

    fun saveCategories(items: Map<String, String>) {
        val raw = runCatching { json.encodeToString(CategoryList(items)) }.getOrNull() ?: return
        prefs?.edit()?.putString(KEY_CATEGORIES, raw)?.apply()
    }

    /**
     * Rail pages are kept so reopening the home screen costs no requests at all.
     * CloudStream fans every rail out on load, so without this the whole
     * catalogue is re-fetched on every visit.
     */
    fun loadPage(key: String, ttlMinutes: Long): CachedPage? {
        val all = loadPages()
        val hit = all[key] ?: return null
        return if (System.currentTimeMillis() - hit.at <= ttlMinutes * 60_000L) hit else null
    }

    @Synchronized
    fun savePage(key: String, page: CachedPage) {
        val all = LinkedHashMap(loadPages())
        all[key] = page.copy(at = System.currentTimeMillis())
        while (all.size > MAX_CACHED_PAGES) {
            val oldest = all.minByOrNull { it.value.at }?.key ?: break
            all.remove(oldest)
        }
        val raw = runCatching { json.encodeToString(PageList(all)) }.getOrNull() ?: return
        prefs?.edit()?.putString(KEY_PAGES, raw)?.apply()
    }

    @Synchronized
    fun clearPages() {
        prefs?.edit()?.remove(KEY_PAGES)?.apply()
    }

    private fun loadPages(): Map<String, CachedPage> {
        val raw = prefs?.getString(KEY_PAGES, null) ?: return emptyMap()
        return runCatching { json.decodeFromString<PageList>(raw).items }.getOrDefault(emptyMap())
    }

    private const val MAX_CACHED_PAGES = 160
}

fun normalizeBase(input: String?): String? {
    val trimmed = input?.trim()?.trimEnd('/') ?: return null
    if (trimmed.isEmpty()) return null
    val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        trimmed
    } else {
        "https://$trimmed"
    }
    return withScheme.takeIf { it.length > "https://".length }
}
