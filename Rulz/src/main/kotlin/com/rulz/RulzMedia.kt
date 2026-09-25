package com.rulz

import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.Jsoup
import java.net.URI

internal data class MediaCandidate(val url: String, val quality: String)

internal fun extractPlayerUrls(html: String, baseUrl: String): List<String> {
    val locations = Regex(
        """var\s+locations\s*=\s*\[(.*?)]""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    ).find(html)?.groupValues?.get(1).orEmpty()

    return locations.split(',')
        .map { absoluteMediaUrl(it.trim().trim('"', '\''), baseUrl) }
        .filter { it.startsWith("http://") || it.startsWith("https://") }
        .distinct()
}

internal fun extractMediaCandidates(html: String, baseUrl: String): List<MediaCandidate> {
    val out = LinkedHashMap<String, MediaCandidate>()

    fun add(rawUrl: String, quality: String = "") {
        val url = absoluteMediaUrl(rawUrl, baseUrl)
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        if (ASSET_URL.containsMatchIn(url)) return
        val resolvedQuality = quality.ifEmpty { if (isHlsUrl(url)) "Auto" else "HD" }
        out.putIfAbsent(url, MediaCandidate(url, resolvedQuality))
    }

    runCatching {
        Jsoup.parse(html, baseUrl).select("video[src], source[src]").forEach { add(it.attr("src")) }
    }
    for (match in Regex(
        """["']?file["']?\s*:\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE
    ).findAll(html)) {
        add(match.groupValues[1])
    }
    for (match in Regex(
        """(?:https?:)?(?://|\\/\\/)[^\s"'<>\\]+(?:\.m3u8|\.mp4|/master\.txt)(?:\?[^\s"'<>\\]*)?""",
        RegexOption.IGNORE_CASE
    ).findAll(html)) {
        add(match.value)
    }

    return out.values.toList()
}

internal fun streamType(url: String): ExtractorLinkType =
    if (isHlsUrl(url)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

internal fun isHlsUrl(url: String): Boolean {
    val path = runCatching { URI(url).path }.getOrDefault(url.substringBefore('?'))
    val lower = path.lowercase()
    if (lower.contains(".m3u8") || HLS_MANIFEST.containsMatchIn(lower)) return true
    // HLS CDNs hand out tokenised playlists with no file extension
    // (StreamLare -> hls2.vcdnx.com/hls/<token>). CloudStream picks its
    // extractor from the declared type, so a VIDEO link to a #EXTM3U body
    // never starts playing.
    return hostOf(url).startsWith("hls") || lower.contains("/hls/")
}

internal fun isChallengePage(html: String): Boolean {
    val lower = html.lowercase()
    return lower.contains("<title>just a moment") ||
        lower.contains("cf-chl-widget") ||
        lower.contains("cf_chl_") ||
        lower.contains("challenge-error-title") ||
        lower.contains("attention required! | cloudflare")
}

internal fun isPublicHttp(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase() ?: return false
    if (scheme != "http" && scheme != "https") return false
    if (uri.userInfo != null) return false
    val host = uri.host?.lowercase()?.removePrefix("[")?.removeSuffix("]").orEmpty()
    if (host.isEmpty() || host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) {
        return false
    }
    if (host == "::1" || host == "0.0.0.0") return false
    if (host.all { it.isDigit() }) return false
    if (host.startsWith("0x") && host.drop(2).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return false
    if (host.contains(':') && (host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe80:"))) {
        return false
    }
    val octets = host.split('.').map { it.toIntOrNull() }
    if (octets.size == 4 && octets.all { it != null && it in 0..255 }) {
        val first = octets[0] ?: 0
        val second = octets[1] ?: 0
        return first !in listOf(0, 10, 127) &&
            !(first == 169 && second == 254) &&
            !(first == 192 && second == 168) &&
            !(first == 172 && second in 16..31)
    }
    return true
}

private val ASSET_URL = Regex(
    """\.(?:png|jpe?g|gif|webp|svg|css|js|vtt|srt)(?:$|[?#])""",
    RegexOption.IGNORE_CASE
)
private val HLS_MANIFEST = Regex("""(?:^|/)master(?:-[^/]+)?\.txt$""", RegexOption.IGNORE_CASE)

private fun absoluteMediaUrl(rawUrl: String, baseUrl: String): String {
    val decoded = rawUrl
        .replace("\\/", "/")
        .replace("\\u0026", "&", ignoreCase = true)
        .replace("&amp;", "&")
        .trim()
    if (decoded.startsWith("//")) return "https:$decoded"
    if (decoded.startsWith("/")) {
        return runCatching { URI(baseUrl).resolve(decoded).toString() }.getOrDefault("")
    }
    return decoded
}
