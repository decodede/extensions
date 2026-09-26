package com.reelfren

import android.content.Context
import android.content.SharedPreferences

const val REEL_DEFAULT_API = "https://api.reelfren.com"
const val REEL_DEFAULT_WEB = "https://reelfren.com"
const val REEL_SITE = "https://www.reelfren.com"

object ReelFrenStore {
    private const val PREFS = "reelfren_prefs"
    private const val KEY_API = "api_base"
    private const val KEY_CATEGORIES = "categories"
    private const val KEY_PROBED_AT = "probed_at"
    private const val KEY_PROBE_VERSION = "probe_version"
    private const val KEY_DIAGNOSTICS = "diagnostics"
    private const val PROBE_VERSION = "6"
    private const val KEY_KNOWN = "known_providers"
    private const val KEY_COOKIES = "cookies"
    const val CATEGORY_TTL_MS = 24L * 60 * 60 * 1000
    const val EMPTY_TTL_MS = 72L * 60 * 60 * 1000

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

    fun tabsFor(slug: String): List<Category> {
        val raw = categories()[slug].orEmpty()
        return raw.mapNotNull { entry ->
            val index = entry.indexOf('~')
            if (index < 0) return@mapNotNull null
            Category(entry.substring(0, index), entry.substring(index + 1))
        }
    }

    fun saveTabs(slug: String, tabs: List<Category>) {
        val merged = categories().toMutableMap()
        merged[slug] = tabs.map { it.key + "~" + it.label }
        prefs?.edit()?.putString(KEY_CATEGORIES, encodeCategories(merged))?.apply()
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

    fun markProbed(slug: String) {
        val probed = decodeMap(prefs?.getString(KEY_PROBED_AT, "").orEmpty()).toMutableMap()
        probed[slug] = System.currentTimeMillis().toString()
        prefs?.edit()?.putString(KEY_PROBED_AT, encodeMap(probed))
            ?.putString(KEY_PROBE_VERSION, PROBE_VERSION)?.apply()
    }

    fun probeFresh(slug: String): Boolean {
        if (prefs?.getString(KEY_PROBE_VERSION, "") != PROBE_VERSION) return false
        val at = decodeMap(prefs?.getString(KEY_PROBED_AT, "").orEmpty())[slug]?.toLongOrNull() ?: 0L
        if (at <= 0L) return false
        val found = diagnostics()[slug]?.split("|")?.getOrNull(1)?.toIntOrNull() ?: 1
        val ttl = if (found > 0) CATEGORY_TTL_MS else EMPTY_TTL_MS
        return System.currentTimeMillis() - at < ttl
    }

    fun saveDiagnostics(slug: String, probed: Int, found: Int, keys: String) {
        val merged = diagnostics().toMutableMap()
        merged[slug] = "$probed|$found|$keys"
        prefs?.edit()?.putString(KEY_DIAGNOSTICS, encodeMap(merged))?.apply()
    }

    fun diagnostics(): Map<String, String> =
        decodeMap(prefs?.getString(KEY_DIAGNOSTICS, "").orEmpty())

    fun diagnosticLines(): List<String> {
        val map = diagnostics()
        if (map.isEmpty()) return emptyList()
        return map.entries.sortedBy { it.key }.map { entry ->
            val parts = entry.value.split("|", limit = 3)
            val probed = parts.getOrElse(0) { "?" }
            val found = parts.getOrElse(1) { "?" }
            val keys = parts.getOrElse(2) { "" }
            val name = ReelFrenNames.display(entry.key)
            if (found == "0") "$name: none (probed $probed keys)"
            else "$name: $found (probed $probed) $keys"
        }
    }

    fun clearCategories() {
        prefs?.edit()?.remove(KEY_CATEGORIES)?.remove(KEY_PROBED_AT)
            ?.remove(KEY_PROBE_VERSION)?.remove(KEY_DIAGNOSTICS)?.apply()
    }

    fun knownSlugs(): Set<String> = prefs?.getStringSet(KEY_KNOWN, null)?.toSet().orEmpty()

    fun saveKnown(slugs: Set<String>) {
        prefs?.edit()?.putStringSet(KEY_KNOWN, slugs.toSet())?.apply()
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
