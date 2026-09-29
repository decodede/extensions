package com.kisskh

import com.lagradost.cloudstream3.Session
import com.lagradost.cloudstream3.utils.logError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.URI

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
    private val keyArray = Regex("""\[(-?0x[0-9a-fA-F]+(?:\s*,\s*-?0x[0-9a-fA-F]+){10,})\]""")
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
        roundKeys: IntArray,
        iv: IntArray,
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

    suspend fun load(session: Session): Snapshot {
        cached?.let { return it }
        return mutex.withLock {
            cached ?: withContext(Dispatchers.IO) { build(session) }.also { cached = it }
        }
    }

    private fun build(session: Session): Snapshot {
        val found = HashMap<String, String>()
        val missing = ArrayList<String>()
        var index = ""
        val host = hosts.firstOrNull {
            index = runCatching { session.get("$it/").text }.getOrNull() ?: return@firstOrNull false
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

        val main = mainUrl?.let { fetch(session, it) }
        baseUrl.find(main.orEmpty())?.let { found["baseUrl"] = it.groupValues[1] }
        if (main == null) missing += "baseUrl"

        val common = fetch(session, commonUrl)
        if (common != null) {
            keyArray.find(common)?.let { match ->
                val parsed = longHex.findAll(match.groupValues[1])
                    .map { it.value.toLong(16).toInt() }
                    .toList()
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
        }

        val chunkUrls = runtimeUrl?.let { fetch(session, it) }?.let { text ->
            chunkMap.find(text)?.let { match ->
                chunkPair.findAll(match.groupValues[1])
                    .map { root.resolve("${it.groupValues[1]}.${it.groupValues[2]}.js").toString() }
                    .distinct()
            }
        } ?: emptyList()

        for (url in (scripts + chunkUrls).distinct()) {
            val text = fetch(session, url) ?: continue
            guid.findAll(text).forEach { found.putIfAbsent(it.groupValues[1], it.groupValues[2]) }
            appVer.find(text)?.let { found.putIfAbsent("appVer", it.groupValues[1]) }
            platformVer.find(text)?.let { found.putIfAbsent("platformVer", it.groupValues[1]) }
            appName.find(text)?.let { found.putIfAbsent("appName", it.groupValues[1]) }
        }

        fun value(key: String, fallback: String): String =
            found[key]?.takeIf { it.isNotBlank() } ?: fallback.also { missing += key }

        if (common == null) missing += "crypto"

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
            if (missing.isNotEmpty()) {
                logError(IllegalStateException("KissKH config fallbacks used: ${missing.distinct()}"))
            }
        }
    }

    private fun fetch(session: Session, url: String): String? =
        runCatching { session.get(url).text }.getOrNull()

    private fun slug(text: String): String =
        text.lowercase().replace(nonSlug, "-").trim('-').ifEmpty { "watch" }
}
