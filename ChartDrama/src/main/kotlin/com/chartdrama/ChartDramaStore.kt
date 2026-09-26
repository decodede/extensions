package com.chartdrama

import android.content.Context
import android.content.SharedPreferences

object ChartDramaStore {
    private const val PREFS = "chartdrama_prefs"
    private const val KEY_SOURCES = "sources"
    private const val KEY_SOURCES_AT = "sources_at"
    private const val KEY_TAGS = "tags"
    private const val KEY_SOURCE_TAGS = "tags_"
    private const val KEY_TAGS_AT = "tags_at"
    const val TTL_MS = 24L * 60 * 60 * 1000
    private const val TAG_SEP = "\u001F"

    @Volatile private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) synchronized(this) {
            if (prefs == null) prefs =
                context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    fun sources(): Set<Int> =
        prefs?.getStringSet(KEY_SOURCES, null)?.mapNotNull { it.toIntOrNull() }?.toSet().orEmpty()

    fun saveSources(ids: Set<Int>) {
        prefs?.edit()?.putStringSet(KEY_SOURCES, ids.map { it.toString() }.toSet())
            ?.putLong(KEY_SOURCES_AT, System.currentTimeMillis())?.apply()
    }

    fun sourcesFresh(): Boolean = fresh(KEY_SOURCES_AT)

    fun allTags(): List<String> =
        prefs?.getString(KEY_TAGS, "")?.split(TAG_SEP)?.map { it.trim() }
            ?.filter { it.isNotEmpty() }.orEmpty()

    fun saveAllTags(tags: List<String>) {
        prefs?.edit()?.putString(KEY_TAGS, tags.joinToString(TAG_SEP))
            ?.putLong(KEY_TAGS_AT, System.currentTimeMillis())?.apply()
    }

    fun allTagsFresh(): Boolean = fresh(KEY_TAGS_AT)

    fun tagsFor(source: Int): List<String> =
        prefs?.getString(KEY_SOURCE_TAGS + source, "")?.split(TAG_SEP)?.map { it.trim() }
            ?.filter { it.isNotEmpty() }.orEmpty()

    fun saveTagsFor(source: Int, tags: List<String>) {
        prefs?.edit()?.putString(KEY_SOURCE_TAGS + source, tags.joinToString(TAG_SEP))?.apply()
    }

    fun clear() {
        prefs?.edit()?.clear()?.apply()
    }

    private fun fresh(key: String): Boolean {
        val at = prefs?.getLong(key, 0L) ?: 0L
        return at > 0 && System.currentTimeMillis() - at < TTL_MS
    }
}
