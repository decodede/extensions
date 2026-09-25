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
import java.net.URI
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
) {
    if (link.isBlank()) return
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
        }
    )
}
suspend fun resolveRatingFile(
    ratingUrl: String,
    fileLabel: String,
    quality: Int,
    referer: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
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
        emitFile("Wood", fileLabel, link, quality, referer, callback)
        return
    }
    Log.d("Wood", "rating fallback anchors: ${anchors.size}")
    anchors.mapNotNull {
        val href = joinUrl(base, it.attr("href").trim())
        if (href.startsWith("http") && !href.contains("wood.cloud", true)) href else null
    }.distinct().amap { href ->
        try {
            loadExtractor(href, referer, subtitleCallback, callback)
        } catch (_: Exception) {
        }
    }
}
