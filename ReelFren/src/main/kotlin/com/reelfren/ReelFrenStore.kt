package com.reelfren

import android.content.Context
import android.content.SharedPreferences

const val REEL_DEFAULT_API = "https://api.reelfren.com"
const val REEL_DEFAULT_WEB = "https://reelfren.com"

object ReelFrenStore {
    private const val PREFS = "reelfren_prefs"
    private const val KEY_API = "api_base"
    private const val KEY_ENABLED = "enabled_providers"
    private const val KEY_FEEDS = "feeds"
    private const val KEY_FEEDS_AT = "feeds_at"
    private const val KEY_KNOWN = "known_providers"
    const val FEED_TTL_MS = 24L * 60 * 60 * 1000

    @Volatile private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) synchronized(this) {
            if (prefs == null) prefs =
                context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    fun apiBase(): String =
        normalizeBase(prefs?.getString(KEY_API, null)) ?: REEL_DEFAULT_API

    fun saveApiBase(raw: String?): Boolean {
        val n = normalizeBase(raw) ?: return false
        prefs?.edit()?.putString(KEY_API, n)?.apply() ?: return false
        return true
    }

    fun enabledSlugs(): Set<String>? = prefs?.getStringSet(KEY_ENABLED, null)?.toSet()

    fun isEnabled(slug: String): Boolean {
        val set = prefs?.getStringSet(KEY_ENABLED, null) ?: return true
        return set.contains(slug)
    }

    fun saveEnabled(slugs: Set<String>) {
        prefs?.edit()?.putStringSet(KEY_ENABLED, slugs.toSet())?.apply()
    }

    fun clearEnabled() {
        prefs?.edit()?.remove(KEY_ENABLED)?.apply()
    }

    fun cachedFeeds(): Map<String, List<String>> =
        ReelFrenCodec.decodeFeeds(prefs?.getString(KEY_FEEDS, "").orEmpty())

    fun feedsFresh(): Boolean {
        val at = prefs?.getLong(KEY_FEEDS_AT, 0L) ?: 0L
        return at > 0 && System.currentTimeMillis() - at < FEED_TTL_MS
    }

    fun saveFeeds(feeds: Map<String, List<String>>) {
        prefs?.edit()?.putString(KEY_FEEDS, ReelFrenCodec.encodeFeeds(feeds))
            ?.putLong(KEY_FEEDS_AT, System.currentTimeMillis())?.apply()
    }

    fun knownSlugs(): Set<String> =
        prefs?.getStringSet(KEY_KNOWN, null)?.toSet().orEmpty()

    fun saveKnown(slugs: Set<String>) {
        prefs?.edit()?.putStringSet(KEY_KNOWN, slugs.toSet())?.apply()
    }
}

fun normalizeBase(input: String?): String? {
    val t = input?.trim()?.trimEnd('/') ?: return null
    if (t.isEmpty()) return null
    return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
}
