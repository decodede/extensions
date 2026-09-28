package com.nunodrama

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val DEFAULT_BASE = "https://nunodrama.my.id"

const val PLATFORM_COOKIE = "nuno_platform"
const val LANG_COOKIE = "nuno_lang"

const val LANG_ID = "id"
const val LANG_EN = "en"

const val CATALOGUE_PAGE_SIZE = 30
const val SEARCH_PAGE_SIZE = 60
const val SEARCH_PER_PROVIDER = 8
const val HTTP_PARALLELISM = 3

@Serializable
data class Provider(
    val slug: String,
    val name: String,
)

@Serializable
private data class ProviderList(val items: List<Provider> = emptyList())

object NunoDramaStore {
    private const val PREFS = "nunodrama_prefs"
    private const val KEY_BASE = "base_url"
    private const val KEY_LANG = "site_lang"
    private const val KEY_PROVIDERS = "providers_json"

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
