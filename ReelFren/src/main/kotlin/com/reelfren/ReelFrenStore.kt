package com.reelfren

import android.content.Context
import android.content.SharedPreferences

const val REEL_DEFAULT_API = "https://api.reelfren.com"
const val REEL_DEFAULT_WEB = "https://reelfren.com"

object ReelFrenStore {
    private const val PREFS = "reelfren_prefs"
    private const val KEY_API = "api_base"
    private const val KEY_CATEGORIES = "categories"
    private const val KEY_CATEGORIES_AT = "categories_at"
    private const val KEY_KNOWN = "known_providers"
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
        decodeCategories(prefs?.getString(KEY_CATEGORIES, "").orEmpty())

    fun categoriesFor(slug: String): List<Category> =
        categories()[slug].orEmpty().map { Category(it, ReelFrenProbe.label(it)) }

    fun saveCategories(slug: String, keys: List<String>) {
        val merged = categories().toMutableMap()
        merged[slug] = keys
        prefs?.edit()?.putString(KEY_CATEGORIES, encodeCategories(merged))
            ?.putLong(KEY_CATEGORIES_AT, System.currentTimeMillis())?.apply()
    }

    fun categoriesFresh(): Boolean {
        val at = prefs?.getLong(KEY_CATEGORIES_AT, 0L) ?: 0L
        return at > 0 && System.currentTimeMillis() - at < CATEGORY_TTL_MS
    }

    fun clearCategories() {
        prefs?.edit()?.remove(KEY_CATEGORIES)?.remove(KEY_CATEGORIES_AT)?.apply()
    }

    fun knownSlugs(): Set<String> = prefs?.getStringSet(KEY_KNOWN, null)?.toSet().orEmpty()

    fun saveKnown(slugs: Set<String>) {
        prefs?.edit()?.putStringSet(KEY_KNOWN, slugs.toSet())?.apply()
    }

    fun clearKnown() {
        prefs?.edit()?.remove(KEY_KNOWN)?.apply()
    }

    private fun encodeCategories(map: Map<String, List<String>>): String =
        map.entries.joinToString(";") { it.key + ":" + it.value.joinToString(",") }

    private fun decodeCategories(raw: String): Map<String, List<String>> {
        if (raw.isBlank()) return emptyMap()
        return raw.split(";").mapNotNull { entry ->
            val index = entry.indexOf(":")
            if (index < 0) return@mapNotNull null
            entry.substring(0, index) to entry.substring(index + 1).split(",").filter { it.isNotEmpty() }
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
