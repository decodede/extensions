package com.stremiouniversal
import com.lagradost.cloudstream3.utils.getQualityFromName
import kotlinx.coroutines.CancellationException
suspend fun <T> resultOr(default: T, block: suspend () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    default
}
fun isPlaceholderStream(name: String?, description: String?, externalUrl: String?): Boolean {
    val text = (name.orEmpty() + " " + description.orEmpty()).lowercase()
    val url = externalUrl.orEmpty().lowercase()
    if (text.contains("no streams found")) return true
    if (text.contains("donat") || text.contains("discord") || text.contains("click here to donate")) return true
    if (url.contains("discord.gg") || url.contains("donation") || url.contains("donate") || url.contains("buymeacoffee") || url.contains("patreon") || url.contains("ko-fi")) return true
    return false
}
private val LIVE_TYPES = setOf("tv", "channel", "livestream", "live", "iptv", "sport")
private val FALLBACK_TRACKERS = listOf(
    "udp://tracker.opentrackr.org:1337/announce",
    "udp://open.demonii.com:1337/announce",
    "udp://tracker.torrent.eu.org:451/announce",
    "udp://tracker.dler.org:6969/announce",
    "udp://exodus.desync.com:6969/announce",
    "udp://open.stealth.si:80/announce",
    "udp://tracker.moeking.me:6969/announce",
    "http://tracker.openbittorrent.com:80/announce"
)
fun manifestBase(manifestUrl: String): String {
    var base = manifestUrl.trim().replace(Regex("^stremio://", RegexOption.IGNORE_CASE), "https://")
    base = base.substringBefore("?")
    base = base.replace(Regex("/manifest\\.json/?$", RegexOption.IGNORE_CASE), "").trimEnd('/')
    return base
}
fun manifestQuery(manifestUrl: String): String =
    if (manifestUrl.contains("?")) "?" + manifestUrl.substringAfter("?") else ""
fun withQuery(url: String, suffix: String): String = url + suffix
fun String.isValidQuerySuffix(): Boolean {
    if (isEmpty()) return true
    if (!startsWith("?")) return false
    if (length > 1024) return false
    if (contains(Regex("\\s"))) return false
    return true
}

fun String.fixSourceUrl(): String {
    return normalizeAddonUrl(this)?.removeSuffix("/manifest.json") ?: this.replace("/manifest.json", "").replace(Regex("^stremio://", RegexOption.IGNORE_CASE), "https://")
}
fun fixSourceName(name: String?, title: String?, description: String?): String {
    val pName = name?.replace("\n", " ")
    val pTitle = title?.replace("\n", " ")
    return when {
        !pName.isNullOrEmpty() && !pTitle.isNullOrEmpty() -> "$pName\n$pTitle"
        !pName.isNullOrEmpty() && !description.isNullOrEmpty() -> "$pName\n$description"
        else -> pTitle ?: description ?: pName ?: ""
    }
}
fun normalizeAddonUrl(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    var line = raw.trim()
    if (line.isEmpty()) return null
    if ("|" in line) {
        val parts = line.split("|").map { it.trim() }.filter { it.isNotEmpty() }
        line = parts.firstOrNull { it.startsWith("http://") || it.startsWith("https://") || it.startsWith("stremio://", ignoreCase = true) }
            ?: parts.last()
    }
    line = line.replace(Regex("^stremio://", RegexOption.IGNORE_CASE), "https://").trim()
    val scheme = line.substringBefore(":").lowercase()
    if (scheme != "http" && scheme != "https") return null
    if (line.contains(Regex("\\s"))) return null
    val query = if (line.contains("?")) "?" + line.substringAfter("?") else ""
    var noQuery = line.substringBefore("?").trimEnd('/')
    val lower = noQuery.lowercase()
    val isManifest = lower.endsWith("manifest.json") || "/manifest.json" in lower ||
        "/configure" in lower
    if (!isManifest) {
        noQuery = "$noQuery/manifest.json"
    }
    if (noQuery.length > 2048) return null
    return noQuery + query
}
fun addonDisplayHost(manifestUrl: String): String =
    manifestUrl.substringAfter("://").substringBefore("/").substringBefore("?")
        .takeIf { it.isNotEmpty() } ?: manifestUrl.take(32)
fun addonBaseKey(manifestUrl: String): String =
    manifestBase(manifestUrl).lowercase().trimEnd('/')
fun streamTypesFor(type: String): List<String> {
    val t = type.lowercase()
    return when (t) {
        "movie" -> listOf("movie")
        "series", "anime", "tv", "show" -> listOf("series")
        in LIVE_TYPES -> listOf(t)
        else -> listOf(t, "movie", "series").distinct()
    }
}
fun normalizeContentId(id: String): String {
    val clean = id.trim()
    return when {
        clean.matches(Regex("^tt\\d+$")) -> clean
        clean.isNotEmpty() && clean.all { it.isDigit() } -> "tmdb:$clean"
        else -> clean
    }
}
fun yearOf(node: com.fasterxml.jackson.databind.JsonNode?): Int? {
    if (node == null || node.isNull) return null
    if (node.isNumber) return node.asInt().takeIf { it in 1900..2100 }
    val text = node.asText().trim()
    text.toIntOrNull()?.takeIf { it in 1900..2100 }?.let { return it }
    return Regex("(19|20)\\d{2}").find(text)?.value?.toIntOrNull()
}
fun youtubeIdOf(source: String): String? {
    val trimmed = source.trim()
    if (trimmed.matches(Regex("^[A-Za-z0-9_-]{11}$"))) return trimmed
    Regex("[?&]v=([A-Za-z0-9_-]{11})").find(trimmed)?.groupValues?.get(1)?.let { return it }
    return Regex("youtu\\.be/([A-Za-z0-9_-]{11})").find(trimmed)?.groupValues?.get(1)
}
fun fixPosterUrl(poster: String?): String? {
    val p = poster?.trim().orEmpty()
    if (p.isEmpty()) return null
    if (p.startsWith("//")) return "https:$p"
    if (p.startsWith("/") && !p.startsWith("//")) return "https://image.tmdb.org/t/p/w500$p"
    if (p.startsWith("http://") || p.startsWith("https://")) return p
    return null
}
fun stripHtml(raw: String?): String =
    raw.orEmpty().replace(Regex("<[^>]*>"), "").replace(Regex("\\s+"), " ").trim()
fun resolutionOf(label: String): Pair<String?, Int> {
    val l = label.lowercase()
    return when {
        Regex("\\b(2160p?|4k|uhd)\\b").containsMatchIn(l) -> "4K" to 5
        Regex("\\b1440p?\\b").containsMatchIn(l) -> "1440p" to 4
        Regex("\\b1080(?:p|i)\\b").containsMatchIn(l) -> "1080p" to 3
        Regex("\\b720p?\\b").containsMatchIn(l) -> "720p" to 2
        Regex("\\b480p?\\b|\\bdvdrip\\b").containsMatchIn(l) -> "480p" to 1
        Regex("\\b360p?\\b").containsMatchIn(l) -> "360p" to 1
        Regex("\\b(cam|ts|tc|scr)\\b").containsMatchIn(l) -> "CAM" to 0
        Regex("\\bauto\\b").containsMatchIn(l) -> "AUTO" to 2
        else -> null to 1
    }
}
fun qualityValue(tag: String?): Int {
    val t = tag.orEmpty()
    val query = if (t.equals("4K", ignoreCase = true)) "2160p"
    else Regex("(\\d{3,4}[pP])").find(t)?.groupValues?.get(1)
    return getQualityFromName(query)
}
private fun seedersOf(label: String): Int {
    Regex("[👥🌱👤]\\s*(\\d+)").find(label)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
    return Regex("(?:^|\\s)(\\d{2,})\\s*(?:seeders?|peers?)\\b", RegexOption.IGNORE_CASE)
        .find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 0
}
fun buildMagnet(infoHash: String?, name: String?, sources: List<String>, fileIdx: Int? = null): String? {
    val hash = infoHash.orEmpty().replace(Regex("[^a-fA-F0-9]"), "").lowercase()
    if (hash.length != 40) return null
    return buildString {
        append("magnet:?xt=urn:btih:").append(hash)
        if (!name.isNullOrBlank()) {
            append("&dn=").append(java.net.URLEncoder.encode(name.trim().take(120), "UTF-8"))
        }
        if (fileIdx != null && fileIdx >= 0) append("&so=").append(fileIdx)
        val trackers = FALLBACK_TRACKERS +
            sources.mapNotNull {
                when {
                    it.startsWith("tracker:") -> it.removePrefix("tracker:")
                    it.startsWith("dht:") -> it.removePrefix("dht:")
                    it.startsWith("udp://") || it.startsWith("http://") || it.startsWith("https://") || it.startsWith("ws://") || it.startsWith("wss://") -> it
                    else -> null
                }
            }
        trackers.distinct().forEach { tracker ->
            append("&tr=").append(java.net.URLEncoder.encode(tracker, "UTF-8"))
        }
    }
}
fun mergeStreamHeaders(stream: StremioStream): Map<String, String> {

    val proxy = stream.behaviorHints?.proxyHeaders?.request
    if (!proxy.isNullOrEmpty()) return sanitizeForwardedHeaders(proxy)
    val hinted = stream.behaviorHints?.headers
    if (!hinted.isNullOrEmpty()) return sanitizeForwardedHeaders(hinted)
    return stream.headers?.let { sanitizeForwardedHeaders(it) } ?: emptyMap()
}

private val BLOCKED_FORWARD_HEADERS = setOf(
    "host", "content-length", "transfer-encoding", "connection",
    "keep-alive", "upgrade", "proxy-authenticate", "proxy-authorization",
    "te", "trailer", "authorization", "cookie", "set-cookie", "x-api-key"
)
internal fun sanitizeForwardedHeaders(headers: Map<String, String>): Map<String, String> {
    if (headers.isEmpty()) return emptyMap()
    return HashMap(headers).filterKeys { key ->
        val low = key.lowercase()
        low !in BLOCKED_FORWARD_HEADERS && !low.startsWith("proxy-") && !low.startsWith("sec-") && !low.contains("\r") && !low.contains("\n")
    }.filterValues { v -> !v.contains("\r") && !v.contains("\n") }
}
fun splitKodiHeaders(url: String): Pair<String, Map<String, String>> {
    val cut = url.indexOf('|')
    if (cut == -1) return url to emptyMap()
    val params = url.substring(cut + 1).split('&')
    if (params.isEmpty() || params.any { !it.contains('=') }) return url to emptyMap()
    val headers = params.mapNotNull { part ->
        val key = part.substringBefore('=').trim()
        if (key.isEmpty()) null else key to part.substringAfter('=')
    }.toMap()
    if (headers.isEmpty()) return url to emptyMap()
    return url.substring(0, cut) to headers
}
fun toStreamLink(stream: StremioStream, addonName: String, addonOrder: Int): StreamLink? {
    if (isPlaceholderStream(stream.name, stream.description ?: stream.title, stream.externalUrl)) return null
    val direct = stream.url?.trim().orEmpty()
    val hash = stream.infoHash?.trim().orEmpty()
    val body = stream.description?.trim().orEmpty().ifEmpty { stream.title?.trim().orEmpty() }
    val header = stream.name?.trim().orEmpty()

    val text = fixSourceName(header.ifEmpty { null }, body.ifEmpty { null }, null)
        .replace("\n", " ").ifEmpty { listOfNotNull(header.ifEmpty { null }, body.ifEmpty { null }).joinToString(" ") }
    val low = text.lowercase()
    var kodiHeaders: Map<String, String> = emptyMap()
    val url = when {
        direct.startsWith("http://") || direct.startsWith("https://") -> {
            val (clean, kodi) = splitKodiHeaders(direct)
            val lowerClean = clean.lowercase()
            if (lowerClean.contains("discord.gg") || lowerClean.contains("donation")) return null
            val path = clean.replace(Regex("^https?://[^/]+"), "")
            if (Regex("/(login|logout|signin|signup)([._?#]|$)", RegexOption.IGNORE_CASE).containsMatchIn(path)) return null
            kodiHeaders = kodi
            clean
        }
        direct.startsWith("magnet:") -> direct
        hash.isNotEmpty() -> buildMagnet(
            hash,
            stream.name ?: stream.behaviorHints?.filename,
            stream.sources,
            stream.fileIdx
        ) ?: return null
        else -> return null
    }
    val (resolution, rank) = resolutionOf(low)
    val title = when {
        body.isNotEmpty() && header.isNotEmpty() && header != body && header != addonName -> "$header • $body"
        body.isNotEmpty() -> body
        else -> header.ifEmpty { url }
    }
    return StreamLink(
        url = url,
        source = addonName,
        title = title,
        qualityTag = resolution,
        headers = mergeStreamHeaders(stream) + sanitizeForwardedHeaders(kodiHeaders),
        resolutionRank = rank,
        seeders = seedersOf(low),
        addonOrder = addonOrder,
        fileIdx = stream.fileIdx
    )
}
fun sortAndDedupe(links: List<StreamLink>): List<StreamLink> {
    val seen = mutableSetOf<String>()
    val unique = links.filter { link ->
        val hash = Regex("urn:btih:([a-fA-F0-9]{40})", RegexOption.IGNORE_CASE)
            .find(link.url)?.groupValues?.get(1)?.lowercase()
        val identity = if (hash != null) "$hash:${link.fileIdx}"
        else link.url.replace(Regex("^https?://"), "").trimEnd('/').substringBefore("#").lowercase()
        identity.isNotEmpty() && seen.add(identity)
    }
    return unique.sortedWith(
        compareByDescending(StreamLink::resolutionRank)
            .thenByDescending(StreamLink::seeders)
            .thenBy(StreamLink::addonOrder)
    )
}
fun matchesQuery(entry: CatalogEntry, query: String): Boolean {
    val q = query.lowercase().trim()
    if (q.isEmpty()) return false
    val haystack = (entry.name + " " + stripHtml(entry.description)).lowercase()
    val tokens = q.split(Regex("\\s+")).filter { it.isNotEmpty() }
    val hits = tokens.count { token ->
        haystack.contains(token) ||
            stringList(entry.genres).any { it.lowercase().contains(token) } ||
            stringList(entry.cast).any { it.lowercase().contains(token) }
    }
    if (hits >= tokens.size) return true
    val flatTitle = haystack.replace(Regex("[^a-z0-9]"), "")
    val flatQuery = q.replace(Regex("[^a-z0-9]"), "")
    return flatTitle.isNotEmpty() && flatQuery.isNotEmpty() && flatTitle.contains(flatQuery)
}

fun subtitleLangOf(sub: StremioSubtitle): String? {
    val raw = sub.lang?.trim().orEmpty().ifEmpty { sub.langCode?.trim().orEmpty() }
    return raw.takeIf { it.isNotEmpty() }
}

fun inferStreamTypeName(streamUrl: String): String {
    if (streamUrl.startsWith("magnet:", ignoreCase = true)) return "MAGNET"
    val path = streamUrl.substringBefore("?").substringBefore("#")
    return when {
        path.endsWith(".torrent", ignoreCase = true) -> "TORRENT"
        path.endsWith(".mpd", ignoreCase = true) -> "DASH"
        path.endsWith(".m3u8", ignoreCase = true) || "/hls/" in path.lowercase() -> "M3U8"
        else -> "INFER"
    }
}
