package com.stremio

import java.security.SecureRandom
import java.util.Locale

const val DEFAULT_PROFILE_ID = "default"

private const val MAX_NAME = 40
private val random = SecureRandom()

data class StremioProfile(val id: String, val name: String) {
    val isDefault: Boolean get() = id == DEFAULT_PROFILE_ID
}

fun newProfileId(): String =
    ByteArray(8).also { random.nextBytes(it) }
        .joinToString("") { String.format(Locale.ROOT, "%02x", it) }

fun sanitizeProfileName(raw: String?): String =
    raw.orEmpty()
        .filter { it == '\n' || it == '\t' || (it.code in 0x20..0x7E) || it.code > 0x7E && it.code < 0xA0 }
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(MAX_NAME)

fun nameKey(name: String): String = name.lowercase(Locale.ROOT)

fun uniqueProfileName(requested: String, taken: Collection<String>): String {
    val base = sanitizeProfileName(requested)
    if (base.isEmpty()) return ""
    val keys = taken.map(::nameKey).toMutableSet()
    if (keys.add(nameKey(base))) return base
    for (n in 2..999) {
        val suffix = " $n"
        val trimmed = base.take(MAX_NAME - suffix.length).trimEnd()
        if (trimmed.isEmpty()) continue
        val candidate = trimmed + suffix
        if (keys.add(nameKey(candidate))) return candidate
    }
    return base
}

fun profileAddonsKey(profileId: String): String =
    if (profileId == DEFAULT_PROFILE_ID) StremioConstants.KEY_ADDONS
    else "${StremioConstants.KEY_ADDONS}_$profileId"

fun profileDefaultsKey(profileId: String): String =
    if (profileId == DEFAULT_PROFILE_ID) StremioConstants.KEY_DEFAULT_ADDONS
    else "${StremioConstants.KEY_DEFAULT_ADDONS}_$profileId"

fun profileCatalogKey(profileId: String): String =
    if (profileId == DEFAULT_PROFILE_ID) "default" else profileId

fun catalogSeenKey(profileId: String, base: String, catalogId: String): String =
    profileCatalogKey(profileId) + "|" + base + "|" + catalogId

fun profileMainUrl(profileId: String): String =
    if (profileId == DEFAULT_PROFILE_ID) "stremio://default" else "stremio://$profileId"
