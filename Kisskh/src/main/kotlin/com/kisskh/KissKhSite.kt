package com.kisskh

import com.lagradost.cloudstream3.app
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.URI

private const val TAG = "KissKH"

object SiteConfig {
    val hosts = listOf("https://kisskh.is", "https://kisskh.co")

    private const val FALLBACK_APP_VER = "2.8.10"
    private const val FALLBACK_PLATFORM_VER = 4830201
    private const val FALLBACK_APP_NAME = "kisskh"
    private const val FALLBACK_VIDEO_GUID = "62f176f3bb1b5b8e70e39932ad34a0c7"
    private const val FALLBACK_SUBTITLE_GUID = "VgV52sWhwvBSf8BsM3BRY9weWiiCbtGp"
    private const val FALLBACK_SALT = "mg3c3b04ba"
    private const val FALLBACK_API_BASE = "/api/"

    private val fallbackRoundKeys = intArrayOf(
        0x4F6BDAA3.toInt(), 0x9E2F8CB0.toInt(), 0x7F5E722D.toInt(), 0x9EDEF314.toInt(),
        0x536620A8.toInt(), 0xCD49AC18.toInt(), 0xB217DE35.toInt(), 0x2CC92D21.toInt(),
        0x8CBEDDD9.toInt(), 0x41F771C1.toInt(), 0xF3E0AFF4.toInt(), 0xDF2982D5.toInt(),
        0x2DADDE47.toInt(), 0x6C5AAF86.toInt(), 0x9FBA0072.toInt(), 0x409382A7.toInt(),
        0xF9BE824E.toInt(), 0x95E42DC8.toInt(), 0x0A5E2DBA.toInt(), 0x4ACDAF1D.toInt(),
        0x54C72698.toInt(), 0xC1230B50.toInt(), 0xCB7D26EA.toInt(), 0x81B089F7.toInt(),
        0x93604E94.toInt(), 0x524345C4.toInt(), 0x993E632E.toInt(), 0x188EEAD9.toInt(),
        0xCAE77B39.toInt(), 0x98A43EFD.toInt(), 0x019A5DD3.toInt(), 0x1914B70A.toInt(),
        0xB04E1CED.toInt(), 0x28EA2210.toInt(), 0x29707FC3.toInt(), 0x3064C8C9.toInt(),
        0xE8A6C1E9.toInt(), 0xC04CE3F9.toInt(), 0xE93C9C3A.toInt(), 0xD95854F3.toInt(),
        0xB486CCDC.toInt(), 0x74CA2F25.toInt(), 0x9DF6B31F.toInt(), 0x44AEE7EC.toInt(),
    )

    private val fallbackIv = intArrayOf(0x01504AF3, 0x56E619CF, 0x2E42BBA6, 0x8C3F70F9.toInt())

    private val scriptSrc = Regex("""src="([^"]+\.js(?:\?[^"]*)?)"""")
    private val commonSrc = Regex("""/common(?:\?|\.js)""")
    private val mainSrc = Regex("""/main\.[0-9a-f]+\.js$""")
    private val runtimeSrc = Regex("""/runtime\.[0-9a-f]+\.js$""")
    private val baseUrl = Regex("""baseUrl:"([^"]+)"""")
    private val chunkMap = Regex("""return e\+"."\+\{([^}]*)\}\[e\]\+"\.js"""")
    private val chunkPair = Regex("""(\d+):"([0-9a-f]+)"""")
    private val guid = Regex("""this\.(viGuid|subGuid)="([^"]+)"""")
    private val appVer = Regex("""this\.appVer="([^"]+)"""")
    private val platformVer = Regex("""this\.platformVer=(\d+)""")
    private val appName = Regex("""this\.appName="([^"]+)"""")
    private val salt = Regex("""'([0-9a-z]{8,12})'(?=[,,\s]*\])""")
    private val longHex = Regex("""-?0x[0-9a-fA-F]+""")
    private val keyArray = Regex("""\[(-?0x[0-9a-fA-F]+(?:\s*,\s*-0x[0-9a-fA-F]+){10,})\]""")
    private val ivArray =
        Regex("""\[\s*(0x[0-9a-fA-F]+)\s*,\s*(0x[0-9a-fA-F]+)\s*,\s*(0x[0-9a-fA-F]+)\s*,\s*(-?0x[0-9a-fA-F]+)\s*\]""")
    private val nonSlug = Regex("[^a-z0-9]+")

    private val mutex = Mutex()

    @Volatile
    private var cached: Snapshot? = null

    data class Snapshot(
        val host: String,
        val apiBase: String,
        val appVer: String,
        val platformVer: Int,
        val appName: String,
        val videoGuid: String,
        val subtitleGuid: String,
        val salt: String,
        private val roundKeys: IntArray,
        private val iv: IntArray,
    ) {
        val api: String = host.trimEnd('/') + apiBase
        private val cipher = SiteCipher(roundKeys, iv)

        fun episodeKey(episodeId: Long): String =
            cipher.encrypt(SiteCipher.plaintext(episodeId, appVer, videoGuid, platformVer, appName, salt))

        fun subtitleKey(episodeId: Long): String =
            cipher.encrypt(SiteCipher.plaintext(episodeId, appVer, subtitleGuid, platformVer, appName, salt))

        fun watchReferer(title: String, dramaId: Long, episodeNumber: Double, episodeId: Long): String =
            "$host/Drama/${slug(title)}/Episode-${episodeNumber.toInt()}" +
                "?id=$dramaId&ep=$episodeId&page=0&pageSize=100"
    }

    suspend fun load(): Snapshot {
        cached?.let { return it }
        return mutex.withLock {
            cached ?: withContext(Dispatchers.IO) { build() }.also { cached = it }
        }
    }

    private suspend fun build(): Snapshot {
        val found = HashMap<String, String>()
        val missing = ArrayList<String>()
        var index = ""
        val host = hosts.firstOrNull {
            index = runCatching { app.get("$it/", timeout = TIMEOUT).text }.getOrNull() ?: return@firstOrNull false
            true
        } ?: throw IllegalStateException("no reachable KissKH host among $hosts")

        val root = URI("$host/")
        val scripts = scriptSrc.findAll(index)
            .map { root.resolve(it.groupValues[1]).toString() }
            .distinct()
            .toList()
        val commonUrl = scripts.firstOrNull { commonSrc.containsMatchIn(it) } ?: "$host/common.js"
        val mainUrl = scripts.firstOrNull { mainSrc.containsMatchIn(it) }
        val runtimeUrl = scripts.firstOrNull { runtimeSrc.containsMatchIn(it) }

        var roundKeys = fallbackRoundKeys
        var iv = fallbackIv
        var saltValue = FALLBACK_SALT

        val main = mainUrl?.let { fetch(it) }
        baseUrl.find(main.orEmpty())?.let { found["baseUrl"] = it.groupValues[1] }
        if (main == null) missing += "baseUrl"

        val common = fetch(commonUrl)
        if (common != null) {
            keyArray.find(common)?.let { match ->
                val parsed = longHex.findAll(match.groupValues[1]).map { it.value.toLong(16).toInt() }.toList()
                if (parsed.size >= 44) roundKeys = parsed.take(44).toIntArray()
            }
            for (m in ivArray.findAll(common)) {
                val words = m.groupValues.drop(1).map { it.toLong(16).toInt() }.toIntArray()
                if (words[0] == 0x01504AF3 && words[1] == 0x56E619CF && words[2] == 0x2E42BBA6) {
                    iv = words
                    break
                }
            }
            salt.find(common)?.let { saltValue = it.groupValues[1] }
        } else {
            missing += "crypto"
        }

        val chunkUrls = runtimeUrl?.let { fetch(it) }?.let { text ->
            chunkMap.find(text)?.let { match ->
                chunkPair.findAll(match.groupValues[1])
                    .map { root.resolve("${it.groupValues[1]}.${it.groupValues[2]}.js").toString() }
                    .distinct()
            }
        } ?: emptyList()

        for (url in (scripts + chunkUrls).distinct()) {
            val text = fetch(url) ?: continue
            guid.findAll(text).forEach { found.putIfAbsent(it.groupValues[1], it.groupValues[2]) }
            appVer.find(text)?.let { found.putIfAbsent("appVer", it.groupValues[1]) }
            platformVer.find(text)?.let { found.putIfAbsent("platformVer", it.groupValues[1]) }
            appName.find(text)?.let { found.putIfAbsent("appName", it.groupValues[1]) }
        }

        fun value(key: String, fallback: String): String =
            found[key]?.takeIf { it.isNotBlank() } ?: fallback.also { missing += key }

        return Snapshot(
            host = host,
            apiBase = value("baseUrl", FALLBACK_API_BASE),
            appVer = value("appVer", FALLBACK_APP_VER),
            platformVer = value("platformVer", FALLBACK_PLATFORM_VER.toString()).toIntOrNull()
                ?: FALLBACK_PLATFORM_VER,
            appName = value("appName", FALLBACK_APP_NAME),
            videoGuid = value("viGuid", FALLBACK_VIDEO_GUID),
            subtitleGuid = value("subGuid", FALLBACK_SUBTITLE_GUID),
            salt = saltValue,
            roundKeys = roundKeys,
            iv = iv,
        ).also {
            if (missing.isNotEmpty()) Log.w(TAG, "config fallbacks used: ${missing.distinct()}")
        }
    }

    private suspend fun fetch(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val response = app.get(url, timeout = TIMEOUT)
            if (response.code !in 200..299) null else response.text
        }.getOrNull()
    }

    private fun slug(text: String): String =
        text.lowercase().replace(nonSlug, "-").trim('-').ifEmpty { "watch" }

    private const val TIMEOUT = 20L
}

class SiteCipher(private val roundKeys: IntArray, private val iv: IntArray) {
    init {
        require(roundKeys.size == 44) { "expected 44 round-key words, got ${roundKeys.size}" }
        require(iv.size == 4) { "expected a 4-word IV, got ${iv.size}" }
    }

    fun encrypt(text: String): String {
        val padded = pkcs7(text)
        val words = toWords(padded)
        var offset = 0
        while (offset < words.size) {
            encryptBlock(words, offset)
            offset += 4
        }
        return toHex(words, padded.length)
    }

    private fun encryptBlock(state: IntArray, offset: Int) {
        val previous = if (offset == 0) iv else state.copyOfRange(offset - 4, offset)
        for (i in 0..3) state[offset + i] = state[offset + i] xor previous[i]

        var a = state[offset] xor roundKeys[0]
        var b = state[offset + 1] xor roundKeys[1]
        var c = state[offset + 2] xor roundKeys[2]
        var d = state[offset + 3] xor roundKeys[3]
        var k = 4

        repeat(9) {
            val na = T0[a ushr 24] xor T1[(b ushr 16) and 0xFF] xor T2[(c ushr 8) and 0xFF] xor
                T3[d and 0xFF] xor roundKeys[k++]
            val nb = T0[b ushr 24] xor T1[(c ushr 16) and 0xFF] xor T2[(d ushr 8) and 0xFF] xor
                T3[a and 0xFF] xor roundKeys[k++]
            val nc = T0[c ushr 24] xor T1[(d ushr 16) and 0xFF] xor T2[(a ushr 8) and 0xFF] xor
                T3[b and 0xFF] xor roundKeys[k++]
            val nd = T0[d ushr 24] xor T1[(a ushr 16) and 0xFF] xor T2[(b ushr 8) and 0xFF] xor
                T3[c and 0xFF] xor roundKeys[k++]
            a = na; b = nb; c = nc; d = nd
        }

        state[offset] = ((SBOX[a ushr 24].toInt() shl 24) or (SBOX[(b ushr 16) and 0xFF].toInt() shl 16) or
            (SBOX[(c ushr 8) and 0xFF].toInt() shl 8) or SBOX[d and 0xFF].toInt()) xor roundKeys[k++]
        state[offset + 1] = ((SBOX[b ushr 24].toInt() shl 24) or (SBOX[(c ushr 16) and 0xFF].toInt() shl 16) or
            (SBOX[(d ushr 8) and 0xFF].toInt() shl 8) or SBOX[a and 0xFF].toInt()) xor roundKeys[k++]
        state[offset + 2] = ((SBOX[c ushr 24].toInt() shl 24) or (SBOX[(d ushr 16) and 0xFF].toInt() shl 16) or
            (SBOX[(a ushr 8) and 0xFF].toInt() shl 8) or SBOX[b and 0xFF].toInt()) xor roundKeys[k++]
        state[offset + 3] = ((SBOX[d ushr 24].toInt() shl 24) or (SBOX[(a ushr 16) and 0xFF].toInt() shl 16) or
            (SBOX[(b ushr 8) and 0xFF].toInt() shl 8) or SBOX[c and 0xFF].toInt()) xor roundKeys[k]
    }

    private fun pkcs7(text: String): String {
        val pad = 16 - text.length % 16
        return text + pad.toChar().toString().repeat(pad)
    }

    private fun toWords(text: String): IntArray {
        val words = IntArray(text.length / 4)
        for (i in text.indices) {
            words[i ushr 2] = words[i ushr 2] or ((text[i].code and 0xFF) shl (24 - i % 4 * 8))
        }
        return words
    }

    private fun toHex(words: IntArray, length: Int): String {
        val out = StringBuilder(length * 2)
        for (i in 0 until length) {
            val byte = (words[i ushr 2] ushr (24 - i % 4 * 8)) and 0xFF
            out.append("%02X".format(byte))
        }
        return out.toString()
    }

    companion object {
        private val SBOX = intArrayOf(
            0x63, 0x7C, 0x77, 0x7B, 0xF2, 0x6B, 0x6F, 0xC5, 0x30, 0x01, 0x67, 0x2B, 0xFE, 0xD7, 0xAB, 0x76,
            0xCA, 0x82, 0xC9, 0x7D, 0xFA, 0x59, 0x47, 0xF0, 0xAD, 0xD4, 0xA2, 0xAF, 0x9C, 0xA4, 0x72, 0xC0,
            0xB7, 0xFD, 0x93, 0x26, 0x36, 0x3F, 0xF7, 0xCC, 0x34, 0xA5, 0xE5, 0xF1, 0x71, 0xD8, 0x31, 0x15,
            0x04, 0xC7, 0x23, 0xC3, 0x18, 0x96, 0x05, 0x9A, 0x07, 0x12, 0x80, 0xE2, 0xEB, 0x27, 0xB2, 0x75,
            0x09, 0x83, 0x2C, 0x1A, 0x1B, 0x6E, 0x5A, 0xA0, 0x52, 0x3B, 0xD6, 0xB3, 0x29, 0xE3, 0x2F, 0x84,
            0x53, 0xD1, 0x00, 0xED, 0x20, 0xFC, 0xB1, 0x5B, 0x6A, 0xCB, 0xBE, 0x39, 0x4A, 0x4C, 0x58, 0xCF,
            0xD0, 0xEF, 0xAA, 0xFB, 0x43, 0x4D, 0x33, 0x85, 0x45, 0xF9, 0x02, 0x7F, 0x50, 0x3C, 0x9F, 0xA8,
            0x51, 0xA3, 0x40, 0x8F, 0x92, 0x9D, 0x38, 0xF5, 0xBC, 0xB6, 0xDA, 0x21, 0x10, 0xFF, 0xF3, 0xD2,
            0xCD, 0x0C, 0x13, 0xEC, 0x5F, 0x97, 0x44, 0x17, 0xC4, 0xA7, 0x7E, 0x3D, 0x64, 0x5D, 0x19, 0x73,
            0x60, 0x81, 0x4F, 0xDC, 0x22, 0x2A, 0x90, 0x88, 0x46, 0xEE, 0xB8, 0x14, 0xDE, 0x5E, 0x0B, 0xDB,
            0xE0, 0x32, 0x3A, 0x0A, 0x49, 0x06, 0x24, 0x5C, 0xC2, 0xD3, 0xAC, 0x62, 0x91, 0x95, 0xE4, 0x79,
            0xE7, 0xC8, 0x37, 0x6D, 0x8D, 0xD5, 0x4E, 0xA9, 0x6C, 0x56, 0xF4, 0xEA, 0x65, 0x7A, 0xAE, 0x08,
            0xBA, 0x78, 0x25, 0x2E, 0x1C, 0xA6, 0xB4, 0xC6, 0xE8, 0xDD, 0x74, 0x1F, 0x4B, 0xBD, 0x8B, 0x8A,
            0x70, 0x3E, 0xB5, 0x66, 0x48, 0x03, 0xF6, 0x0E, 0x61, 0x35, 0x57, 0xB9, 0x86, 0xC1, 0x1D, 0x9E,
            0xE1, 0xF8, 0x98, 0x11, 0x69, 0xD9, 0x8E, 0x94, 0x9B, 0x1E, 0x87, 0xE9, 0xCE, 0x55, 0x28, 0xDF,
            0x8C, 0xA1, 0x89, 0x0D, 0xBF, 0xE6, 0x42, 0x68, 0x41, 0x99, 0x2D, 0x0F, 0xB0, 0x54, 0xBB, 0x16,
        )

        private val T0 = IntArray(256)
        private val T1 = IntArray(256)
        private val T2 = IntArray(256)
        private val T3 = IntArray(256)

        init {
            for (i in 0 until 256) {
                val s = SBOX[i]
                val u = ((s shl 1) xor if (s and 0x80 != 0) 0x11B else 0) and 0xFF
                val v = s xor u
                T0[i] = (u shl 24) or (s shl 16) or (s shl 8) or v
                T1[i] = (v shl 24) or (u shl 16) or (s shl 8) or s
                T2[i] = (s shl 24) or (v shl 16) or (u shl 8) or s
                T3[i] = (s shl 24) or (s shl 16) or (v shl 8) or u
            }
        }

        private fun stringHash(text: String): Double {
            var hash = 0.0
            for (ch in text) hash = (hash.toInt() shl 5).toDouble() - hash + ch.code
            return hash
        }

        fun plaintext(
            episodeId: Long,
            appVer: String,
            guid: String,
            platformVer: Int,
            appName: String,
            salt: String,
        ): String {
            val parts = ArrayList<Any?>(15)
            parts += ""
            parts += episodeId
            parts += null
            parts += salt
            parts += appVer
            parts += guid
            parts += platformVer
            repeat(6) { parts += appName }
            parts += "00"
            parts += ""
            val hash = stringHash(parts.joinToString("|"))
            val joined = ArrayList<Any?>(parts.size + 1)
            joined += ""
            joined += if (hash == Math.floor(hash)) hash.toLong().toString() else hash.toString()
            joined.addAll(parts.subList(1, parts.size))
            return joined.joinToString("|")
        }
    }
}
