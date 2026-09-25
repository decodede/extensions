package com.reelfren

import android.content.Context
import android.content.SharedPreferences

const val REEL_DEFAULT_API = "https://api.reelfren.com"
const val REEL_DEFAULT_WEB = "https://reelfren.com"

object ReelFrenStore {
    private const val PREFS = "reelfren_prefs"
    private const val KEY_API = "api_base"
    private const val KEY_CATEGORIES = "categories"
    private const val KEY_PROBED_AT = "probed_at"
    private const val KEY_KNOWN = "known_providers"
    private const val KEY_COOKIES = "cookies"
    const val CATEGORY_TTL_MS = 24L * 60 * 60 * 1000

    @Volatile private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) synchronized(this) {
            if (prefs == null) prefs =
                context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    fun apiBase(): String = normalizeBase(prefs?.getString(KEY_API, null)) ?: REEL_DEFAULT_API

    fun saveApiBase(raw: String?): Boolean {
        val value = normalizeBase(raw) ?: return false
        prefs?.edit()?.putString(KEY_API, value)?.apply() ?: return false
        return true
    }

    fun categories(): Map<String, List<String>> =
        decodeMap(prefs?.getString(KEY_CATEGORIES, "").orEmpty()).mapValues { (_, v) ->
            v.split(",").filter { it.isNotEmpty() }
        }

    private fun encodeMap(map: Map<String, String>): String =
        map.entries.joinToString(";") { it.key + ":" + it.value }

    private fun encodeCategories(map: Map<String, List<String>>): String =
        map.entries.joinToString(";") { it.key + ":" + it.value.joinToString(",") }

    private fun decodeMap(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.split(";").mapNotNull { entry ->
            val index = entry.indexOf(":")
            if (index < 0) return@mapNotNull null
            entry.substring(0, index) to entry.substring(index + 1)
        }.toMap()
    }

    fun categoriesFor(slug: String): List<Category> =
        categories()[slug].orEmpty().map { Category(it, ReelFrenProbe.label(it)) }

    fun saveCategories(slug: String, keys: List<String>) {
        val merged = categories().toMutableMap()
        merged[slug] = keys
        val probed = decodeMap(prefs?.getString(KEY_PROBED_AT, "").orEmpty()).toMutableMap()
        probed[slug] = System.currentTimeMillis().toString()
        prefs?.edit()?.putString(KEY_CATEGORIES, encodeCategories(merged))
            ?.putString(KEY_PROBED_AT, encodeMap(probed))?.apply()
    }

    fun probeFresh(slug: String): Boolean {
        val at = decodeMap(prefs?.getString(KEY_PROBED_AT, "").orEmpty())[slug]?.toLongOrNull() ?: 0L
        return at > 0 && System.currentTimeMillis() - at < CATEGORY_TTL_MS
    }

    fun clearCategories() {
        prefs?.edit()?.remove(KEY_CATEGORIES)?.remove(KEY_PROBED_AT)?.apply()
    }

    fun knownSlugs(): Set<String> = prefs?.getStringSet(KEY_KNOWN, null)?.toSet().orEmpty()

    fun saveKnown(slugs: Set<String>) {
        prefs?.edit()?.putStringSet(KEY_KNOWN, slugs.toSet())?.apply()
    }

    fun clearKnown() {
        prefs?.edit()?.remove(KEY_KNOWN)?.apply()
    }

    fun cookie(host: String): String {
        if (host.isEmpty()) return ""
        return cookies()[host].orEmpty()
    }

    fun saveCookie(host: String, cookie: String) {
        if (host.isEmpty() || cookie.isBlank()) return
        val merged = cookies().toMutableMap()
        merged[host] = cookie
        prefs?.edit()?.putString(KEY_COOKIES, encodeCookies(merged))?.apply()
    }

    fun hasCookie(): Boolean = cookies().values.any { it.contains("cf_clearance") }

    fun clearCookies() {
        prefs?.edit()?.remove(KEY_COOKIES)?.apply()
        runCatching {
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
        }
    }

    private fun cookies(): Map<String, String> =
        decodeCookies(prefs?.getString(KEY_COOKIES, "").orEmpty())

    private fun encodeCookies(map: Map<String, String>): String =
        map.entries.joinToString(";") { it.key + ":" + it.value }

    private fun decodeCookies(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.split(";").mapNotNull { entry ->
            val index = entry.indexOf(":")
            if (index < 0) return@mapNotNull null
            entry.substring(0, index) to entry.substring(index + 1)
        }.toMap()
    }
}

fun normalizeBase(input: String?): String? {
    val trimmed = input?.trim()?.trimEnd('/') ?: return null
    if (trimmed.isEmpty()) return null
    return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        trimmed
    } else {
        "https://$trimmed"
    }
}
