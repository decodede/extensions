package com.rulz

import android.content.Context
import android.content.SharedPreferences

const val DEFAULT_BASE = "https://www.5movierulz.services"
private const val LEGACY_DEFAULT_BASE = "https://www.5movierulz.fitness"

object RulzStore {
    private const val PREFS = "rulz_prefs"
    private const val KEY_BASE = "base_url"

    @Volatile private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) synchronized(this) {
            if (prefs == null) prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    fun base(): String {
        val stored = normalizeBaseUrl(prefs?.getString(KEY_BASE, null)) ?: return DEFAULT_BASE
        return if (stored == LEGACY_DEFAULT_BASE) DEFAULT_BASE else stored
    }

    fun saveBase(raw: String?): Boolean {
        val n = normalizeBaseUrl(raw) ?: return false
        prefs?.edit()?.putString(KEY_BASE, n)?.apply() ?: return false
        return true
    }
}

fun normalizeBaseUrl(input: String?): String? {
    val value = input?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
    val candidate = when {
        value.startsWith("http://", ignoreCase = true) -> value
        value.startsWith("https://", ignoreCase = true) -> value
        else -> "https://$value"
    }
    return candidate.takeIf { isPublicHttp(it) }
}
