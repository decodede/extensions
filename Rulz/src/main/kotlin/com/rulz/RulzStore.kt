package com.rulz

import android.content.Context
import android.content.SharedPreferences

const val DEFAULT_BASE = "https://www.5movierulz.fitness"

object RulzStore {
    private const val PREFS = "rulz_prefs"
    private const val KEY_BASE = "base_url"

    @Volatile private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) synchronized(this) {
            if (prefs == null) prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    fun base(): String = normalizeBaseUrl(prefs?.getString(KEY_BASE, null)) ?: DEFAULT_BASE

    fun saveBase(raw: String?): Boolean {
        val n = normalizeBaseUrl(raw) ?: return false
        prefs?.edit()?.putString(KEY_BASE, n)?.apply() ?: return false
        return true
    }
}

fun normalizeBaseUrl(input: String?): String? {
    val t = input?.trim()?.trimEnd('/') ?: return null
    if (t.isEmpty()) return null
    return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
}
