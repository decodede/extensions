package com.nunodrama

import android.content.Context
import android.content.SharedPreferences

object NunoDramaStore {
    private const val PREFS = "nunodrama_prefs"
    private const val KEY_BASE = "base_url"
    private const val KEY_LANG = "site_lang"

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
