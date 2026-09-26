package com.wood
import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
private const val PROBE_TIMEOUT_MS = 10_000L
/**
 * Playback request shape. ExoPlayer/Cronet otherwise sends a bare client UA and
 * no navigational headers, which many CDNs answer with 403 even when the file
 * plays in a browser.
 */
val MEDIA_HEADERS = mapOf(
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
    "Accept" to "*/*",
    "Accept-Language" to "en-US,en;q=0.9",
    "Connection" to "keep-alive",
    "Sec-Fetch-Dest" to "video",
    "Sec-Fetch-Mode" to "no-cors",
    "Sec-Fetch-Site" to "cross-site"
)
val BROWSER_HEADERS = mapOf(
    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36",
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
    "Accept-Language" to "en-US,en;q=0.9",
    "Sec-CH-UA" to "\"Chromium\";v=\"126\", \"Google Chrome\";v=\"126\"",
    "Sec-CH-UA-Mobile" to "?1",
    "Sec-CH-UA-Platform" to "\"Android\"",
    "Upgrade-Insecure-Requests" to "1",
    "Sec-Fetch-Dest" to "document",
    "Sec-Fetch-Mode" to "navigate",
    "Sec-Fetch-Site" to "none",
    "Sec-Fetch-User" to "?1",
    "Connection" to "keep-alive"
)
val DIRECT_MEDIA_PATTERN =
    Regex("""\.(mp4|mkv|m3u8|avi|mov)(\?|#|$)""", RegexOption.IGNORE_CASE)
/** Only a definite answer from the server retires a source. */
internal fun isPlayableStatus(code: Int): Boolean = code in 200..299

/**
 * Asks the CDN whether the file is really downloadable before CloudStream tries
 * to play it. Dead or Cloudflare-blocked hosts otherwise reach the player and
 * fail there as ERROR_CODE_IO_BAD_HTTP_STATUS (2004), one toast per source.
 *
 * The retry without Range exists because a host that dislikes ranged requests
 * would otherwise lose a source that plays fine. A probe that cannot answer
 * (timeout, IO error) keeps the source: only an explicit non-2xx status from
 * both attempts retires it.
 */
internal suspend fun mediaLinkIsReachable(url: String, referer: String): Boolean = try {
    withTimeoutOrNull(PROBE_TIMEOUT_MS) {
        val ranged = app.get(url, headers = MEDIA_HEADERS + ("Range" to "bytes=0-1023"), referer = referer)
        if (isPlayableStatus(ranged.code)) {
            Log.d("Wood", "probe ${ranged.code} $url")
            return@withTimeoutOrNull true
        }
        val plain = app.get(url, headers = MEDIA_HEADERS, referer = referer)
        val playable = isPlayableStatus(plain.code)
        Log.d(
            "Wood",
            "probe ranged=${ranged.code} plain=${plain.code} ${if (playable) "kept" else "dropped"} $url"
        )
        playable
    } ?: true
} catch (error: Exception) {
    Log.d("Wood", "probe error ${error.javaClass.simpleName} kept $url")
    true
}
fun qualityFromText(str: String?): Int {
    if (str.isNullOrBlank()) return Qualities.Unknown.value
    Regex("""(\d{3,4})[pP]""").find(str)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
    val lower = str.lowercase()
    return when {
        lower.contains("8k") -> 4320
        lower.contains("4k") || lower.contains("2160") -> 2160
        lower.contains("1440") -> 1440
        lower.contains("1080") -> 1080
        lower.contains("720") -> 720
        lower.contains("480") -> 480
        lower.contains("360") -> 360
        else -> Qualities.Unknown.value
    }
}
fun joinUrl(base: String, href: String): String {
    val h = href.replace("&amp;", "&").trim()
    if (h.isBlank()) return ""
    if (h.startsWith("http")) return h
    if (h.startsWith("//")) return "https:$h"
    val b = base.trimEnd('/')
    return if (h.startsWith("/")) b + h else "$b/$h"
}
fun joinUrlNull(base: String, href: String?): String? {
    if (href.isNullOrBlank()) return null
    return joinUrl(base, href)
}
fun getBaseUrl(url: String): String {
    return try {
        URI(url).let { "${it.scheme}://${it.host}" }
    } catch (_: Exception) {
        url
    }
}
suspend fun emitFile(
    source: String,
    label: String,
    link: String,
    quality: Int,
    referer: String,
    callback: (ExtractorLink) -> Unit
): Boolean {
    if (link.isBlank()) return false
    if (!mediaLinkIsReachable(link, referer)) return false
    val clean = label.trim().ifBlank { link.substringAfterLast("/") }
    callback.invoke(
        newExtractorLink(
            source,
            "$source $clean",
            link,
            if (link.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        ) {
            this.quality = quality
            this.referer = referer
            this.headers = MEDIA_HEADERS
        }
    )
    return true
}
suspend fun resolveRatingFile(
    ratingUrl: String,
    fileLabel: String,
    quality: Int,
    referer: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Int {
    val res = app.get(ratingUrl, headers = BROWSER_HEADERS, referer = referer)
    val doc = res.document
    val base = getBaseUrl(ratingUrl)
    Log.d("Wood", "rating $ratingUrl -> ${res.code} len=${res.text.length}")
    val anchors = doc.select("a[href]")
    val direct = anchors.firstOrNull {
        DIRECT_MEDIA_PATTERN.containsMatchIn(it.attr("href"))
    }
    if (direct != null) {
        val link = joinUrl(base, direct.attr("href").trim())
        Log.d("Wood", "rating direct: $link")
        return if (emitFile("Wood", fileLabel, link, quality, referer, callback)) 1 else 0
    }
    Log.d("Wood", "rating fallback anchors: ${anchors.size}")
    val externals = anchors.mapNotNull {
        val href = joinUrl(base, it.attr("href").trim())
        if (href.startsWith("http") && !href.contains("wood.cloud", true)) href else null
    }.distinct()
    externals.amap { href ->
        try {
            loadExtractor(href, referer, subtitleCallback, callback)
        } catch (_: Exception) {
        }
    }
    // loadExtractor owns emission, so its result is not observable here; assume
    // one source when there was anything to hand it.
    return if (externals.isEmpty()) 0 else 1
}
