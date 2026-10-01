package com.stremio

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.SubtitleHelper
import com.lagradost.cloudstream3.utils.getQualityFromName
import java.net.URLEncoder
import java.util.Locale
import kotlinx.coroutines.CancellationException

suspend fun <T> resultOr(default: T, block: suspend () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    default
}

fun isPlaceholderStream(name: String?, description: String?, externalUrl: String?): Boolean {
    val text = (name.orEmpty() + " " + description.orEmpty()).lowercase(Locale.ROOT)
    val url = externalUrl.orEmpty().lowercase(Locale.ROOT)
    if (text.contains("no streams found")) return true
    if (text.contains("donat") || text.contains("discord") || text.contains("click here to donate")) return true
    if (url.contains("discord.gg") || url.contains("donation") || url.contains("donate") ||
        url.contains("buymeacoffee") || url.contains("patreon") || url.contains("ko-fi")
    ) {
        return true
    }
    return false
}

private val LIVE_TYPES = setOf("channel", "livestream", "live", "iptv", "sport")

private val FALLBACK_TRACKERS = listOf(
    "udp://tracker.opentrackr.org:1337/announce",
    "udp://open.demonii.com:1337/announce",
    "udp://tracker.torrent.eu.org:451/announce",
    "udp://tracker.dler.org:6969/announce",
    "udp://exodus.desync.com:6969/announce",
    "udp://open.stealth.si:80/announce",
    "udp://tracker.moeking.me:6969/announce",
    "http://tracker.openbittorrent.com:80/announce",
)

fun manifestBase(manifestUrl: String): String {
    var base = manifestUrl.trim().replace(Regex("^stremio://", RegexOption.IGNORE_CASE), "https://")
    base = base.substringBefore("?")
    base = base.replace(Regex("/manifest\\.json/?$", RegexOption.IGNORE_CASE), "").trimEnd('/')
    return base
}

fun manifestQuery(manifestUrl: String): String =
    if (manifestUrl.contains("?")) "?" + manifestUrl.substringAfter("?") else ""

fun String.isValidQuerySuffix(): Boolean {
    if (isEmpty()) return true
    if (!startsWith("?")) return false
    if (contains(Regex("\\s"))) return false
    return true
}

fun String.fixSourceUrl(): String = normalizeAddonUrl(this)
    ?.removeSuffix("/manifest.json")
    ?: this.replace("/manifest.json", "")
        .replace(Regex("^stremio://", RegexOption.IGNORE_CASE), "https://")

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
        line = parts.firstOrNull {
            it.startsWith("http://") || it.startsWith("https://") ||
                it.startsWith("stremio://", ignoreCase = true)
        } ?: parts.last()
    }
    line = line.replace(Regex("^stremio://", RegexOption.IGNORE_CASE), "https://").trim()
    val scheme = line.substringBefore(":").lowercase(Locale.ROOT)
    if (scheme != "http" && scheme != "https") return null
    if (line.contains(Regex("\\s"))) return null
    val query = if (line.contains("?")) "?" + line.substringAfter("?") else ""
    var noQuery = line.substringBefore("?").trimEnd('/')
    val lower = noQuery.lowercase(Locale.ROOT)
    val isManifest = lower.endsWith("manifest.json") || "/manifest.json" in lower || "/configure" in lower
    if (!isManifest) {
        noQuery = "$noQuery/manifest.json"
    }
    return noQuery + query
}

fun addonDisplayHost(manifestUrl: String): String =
    manifestUrl.substringAfter("://").substringBefore("/").substringBefore("?")
        .takeIf { it.isNotEmpty() } ?: manifestUrl.take(32)

fun addonBaseKey(manifestUrl: String): String = manifestBase(manifestUrl).lowercase(Locale.ROOT).trimEnd('/')

fun streamTypesFor(type: String): List<String> = when (val t = type.lowercase(Locale.ROOT)) {
    "movie" -> listOf("movie")
    "series", "anime", "tv", "show" -> listOf("series")
    in LIVE_TYPES -> listOf(t)
    else -> listOf(t, "movie", "series").distinct()
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
    val l = label.lowercase(Locale.ROOT)
    return when {
        Regex("\\b(4320p?|8k|uhd8k)\\b").containsMatchIn(l) -> "8K" to 6
        Regex("\\b(2160p?|4k|uhd)\\b").containsMatchIn(l) -> "4K" to 5
        Regex("\\b1440p?\\b|\\b2k\\b").containsMatchIn(l) -> "1440p" to 4
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
    else if (t.equals("8K", ignoreCase = true)) "4320p"
    else Regex("(\\d{3,4}[pP])").find(t)?.groupValues?.get(1)
    return getQualityFromName(query)
}

private val SOURCE_TAGS = setOf(
    "WEB-DL", "WEBDL", "WEBRIP", "WEB", "BLURAY", "BLU-RAY", "BDREMUX", "REMUX", "BDRIP",
    "BRRIP", "HDRIP", "DVDRIP", "HDTV", "PDTV", "DVDSCR", "CAM", "TS", "R5",
)
private val CODEC_TAGS = setOf("H264", "H265", "X264", "X265", "HEVC", "AVC", "AV1", "VP9")
private val AUDIO_TAGS = setOf("AAC", "AC3", "EAC3", "DTS", "DTS-HD", "DD", "DDP", "DDP5.1", "MP3", "FLAC", "OPUS", "TRUEHD", "ATMOS")
private val HDR_TAGS = setOf("SDR", "HDR", "HDR10", "HDR10+", "DV", "DOLBYVISION", "DOLBY VISION", "HLG")
private val BITDEPTH_TAGS = setOf("8BIT", "10BIT", "12BIT")

fun technicalTagsOf(label: String): List<String> {
    val words = label.uppercase(Locale.ROOT).split(Regex("[^A-Z0-9+.]+"))
        .filter { it.isNotEmpty() }
    val found = linkedSetOf<String>()
    for (word in words) {
        when {
            HDR_TAGS.contains(word) -> found += if (word == "DV") "DOLBYVISION" else word
            CODEC_TAGS.contains(word) -> found += word
            SOURCE_TAGS.contains(word) -> found += word
            AUDIO_TAGS.contains(word) || AUDIO_TAGS.any { word.startsWith(it) } -> found += word
            BITDEPTH_TAGS.contains(word) -> found += word
        }
    }
    return found.toList()
}

private fun seedersOf(label: String): Int {
    Regex("[👥🌱👤]\\s*(\\d+)").find(label)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
    return Regex("(?:^|\\s)(\\d{2,})\\s*(?:seeders?|peers?)\\b", RegexOption.IGNORE_CASE)
        .find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 0
}

private fun sizeOf(label: String): String? =
    Regex("💾\\s*([0-9.]+\\s*[A-Za-z]+)").find(label)?.groupValues?.get(1)?.trim()

fun buildMagnet(
    infoHash: String?,
    name: String?,
    sources: List<String>,
    fileIdx: Int? = null,
    extraTrackers: List<String> = emptyList(),
): String? {
    val hash = infoHash.orEmpty().trim().lowercase(Locale.ROOT)
    val isV1 = hash.length == 40
    val isV2 = hash.length == 64
    if (!isV1 && !isV2) return null
    if (!hash.all { it in '0'..'9' || it in 'a'..'f' }) return null
    return buildString {
        if (isV1) append("magnet:?xt=urn:btih:").append(hash)
        else append("magnet:?xt=urn:btmh:1220").append(hash)
        if (!name.isNullOrBlank()) {
            append("&dn=").append(URLEncoder.encode(name.trim().take(160), "UTF-8"))
        }
        if (fileIdx != null && fileIdx >= 0) append("&so=").append(fileIdx)
        val trackers = buildList {
            addAll(FALLBACK_TRACKERS)
            addAll(extraTrackers)
            sources.forEach { source ->
                when {
                    source.startsWith("tracker:", ignoreCase = true) -> source.substringAfter(':')
                    source.startsWith("dht:", ignoreCase = true) -> null
                    source.startsWith("udp://") || source.startsWith("http://") ||
                        source.startsWith("https://") || source.startsWith("ws://") ||
                        source.startsWith("wss://") -> source
                    else -> null
                }?.let(::add)
            }
        }
        trackers.distinct().forEach { append("&tr=").append(URLEncoder.encode(it, "UTF-8")) }
    }
}

private val BLOCKED_FORWARD_HEADERS = setOf(
    "host", "content-length", "transfer-encoding", "connection", "keep-alive", "upgrade",
    "proxy-authenticate", "proxy-authorization", "te", "trailer", "expect", "range",
)

private val HEADER_NAME = Regex("^[A-Za-z0-9!#$%&'*+.^_`|~-]+$")

fun sanitizeForwardedHeaders(headers: Map<String, String>): Map<String, String> {
    if (headers.isEmpty()) return emptyMap()
    val out = LinkedHashMap<String, String>()
    for ((rawKey, value) in headers) {
        if (rawKey.isBlank() || !HEADER_NAME.matches(rawKey)) continue
        if (value.any { it.code > 0xFF || it == '\r' || it == '\n' }) continue
        val low = rawKey.lowercase(Locale.ROOT)
        if (low in BLOCKED_FORWARD_HEADERS) continue
        if (low.startsWith("proxy-") || low.startsWith("sec-")) continue
        out.keys.firstOrNull { it.equals(rawKey, ignoreCase = true) }?.let { out.remove(it) }
        out[rawKey] = value
    }
    return out
}

private fun Map<String, String>.headerOrNull(name: String): String? =
    entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.takeIf { it.isNotBlank() }

fun videoSizeOrNull(size: Long?): Long? = size?.takeIf { it > 0 }

private val GATED_HOSTS = mapOf(
    "hubcloud" to "https://hubcloud.cx/",
    "hubdrive" to "https://hubdrive.dev/",
)

private fun gatedRefererFor(host: String): String? {
    val labels = host.lowercase(Locale.ROOT).split('.').filter { it.isNotEmpty() }
    if (labels.size < 3) return null
    val registrable = labels.takeLast(2).joinToString(".")
    return GATED_HOSTS[registrable.substringBefore('.')]
}

private fun originOf(url: String): String? = runCatching {
    val parsed = java.net.URI(url.trim())
    val scheme = parsed.scheme?.lowercase(Locale.ROOT) ?: return null
    val host = parsed.host ?: return null
    val port = parsed.port
    val defaultPort = when (scheme) {
        "http" -> 80
        "https" -> 443
        else -> -1
    }
    if (port > 0 && port != defaultPort) "$scheme://$host:$port" else "$scheme://$host"
}.getOrNull()

fun mergeStreamHeaders(
    stream: StremioStream,
    behaviorHints: BehaviorHints?,
    streamUrl: String,
    defaultUserAgent: String = StremioConstants.UA_DESKTOP,
    kodiHeaders: Map<String, String> = splitKodiHeaders(streamUrl).second,
): Map<String, String> {
    val merged = LinkedHashMap<String, String>()
    merged += sanitizeForwardedHeaders(stream.headers.orEmpty())
    behaviorHints?.let { hints ->
        merged += sanitizeForwardedHeaders(hints.headers.orEmpty())
        merged += sanitizeForwardedHeaders(hints.proxyHeaders?.request.orEmpty())
    }
    merged += sanitizeForwardedHeaders(kodiHeaders)

    if (merged.headerOrNull("User-Agent").isNullOrEmpty() && defaultUserAgent.isNotEmpty()) {
        merged["User-Agent"] = defaultUserAgent
    }

    if (merged.headerOrNull("Referer") == null) {
        val host = runCatching { java.net.URI(streamUrl).host }.getOrNull()
        if (host != null) {
            merged["Referer"] = gatedRefererFor(host) ?: "https://$host/"
        }
    }
    if (merged.headerOrNull("Origin") == null) {
        merged.headerOrNull("Referer")?.let { originOf(it) }?.let { merged["Origin"] = it }
    }
    return merged
}

fun splitKodiHeaders(url: String): Pair<String, Map<String, String>> {
    val cut = url.indexOf('|')
    if (cut <= 0) return url to emptyMap()
    val tail = url.substring(cut + 1)
    if (tail.isEmpty() || !tail.contains('=')) return url to emptyMap()
    val params = tail.split('&').filter { it.contains('=') }
    if (params.isEmpty() || params.any { it.substringBefore('=').isBlank() }) {
        return url to emptyMap()
    }
    val headers = params.associate { it.substringBefore('=').trim() to it.substringAfter('=') }
    if (headers.isEmpty()) return url to emptyMap()
    return url.substring(0, cut) to headers
}

fun toStreamLink(
    stream: StremioStream,
    addonName: String,
    addonOrder: Int,
    defaultUserAgent: String = StremioConstants.UA_DESKTOP,
): StreamLink? {
    if (isPlaceholderStream(stream.name, stream.description ?: stream.title, stream.externalUrl)) {
        return null
    }
    val direct = stream.url?.trim().orEmpty()
    val hash = stream.infoHash?.trim().orEmpty()
    val body = stream.description?.trim().orEmpty().ifEmpty { stream.title?.trim().orEmpty() }
    val header = stream.name?.trim().orEmpty()
    val hints = stream.behaviorHints

    val text = fixSourceName(header.ifEmpty { null }, body.ifEmpty { null }, null)
        .replace("\n", " ")
        .ifEmpty { listOfNotNull(header.ifEmpty { null }, body.ifEmpty { null }).joinToString(" ") }
    val low = text.lowercase(Locale.ROOT)

    val (cleanUrl, kodiHeaders) = splitKodiHeaders(direct)
    val kindFromUrl = streamKindOf(cleanUrl, hash.isNotEmpty())
    val magnet = {
        buildMagnet(
            infoHash = hash,
            name = stream.name ?: hints?.filename ?: body.ifEmpty { null },
            sources = stream.sources,
            fileIdx = stream.fileIdx,
        )
    }
    var kind = kindFromUrl
    val url = when (kindFromUrl) {
        StreamKind.MAGNET -> if (cleanUrl.isEmpty()) magnet() ?: return null else cleanUrl
        StreamKind.TORRENT, StreamKind.HLS, StreamKind.DASH, StreamKind.PROGRESSIVE,
        StreamKind.RTMP, StreamKind.RTSP, StreamKind.FTP,
        -> cleanUrl
        StreamKind.M3U -> return null
        StreamKind.NONE -> {
            if (hash.isEmpty()) return null
            kind = StreamKind.MAGNET
            magnet() ?: return null
        }
        else -> return null
    }
    if (url.isEmpty()) return null

    if (kind != StreamKind.MAGNET) {
        val lowerUrl = url.lowercase(Locale.ROOT)
        if (lowerUrl.contains("discord.gg") || lowerUrl.contains("donation")) return null
        val path = url.replace(Regex("^https?://[^/]+"), "")
        if (Regex("/(login|logout|signin|signup)([._?#]|$)", RegexOption.IGNORE_CASE)
                .containsMatchIn(path)
        ) {
            return null
        }
    }

    val (resolution, rank) = resolutionOf(low)
    val tags = technicalTagsOf(text)
    val decorated = buildString {
        if (header.isNotEmpty() && body.isNotEmpty() && header != body && header != addonName) {
            append(header).append(" • ").append(body)
        } else if (body.isNotEmpty()) {
            append(body)
        } else if (header.isNotEmpty()) {
            append(header)
        } else {
            append(url)
        }
        if (tags.isNotEmpty()) append(" [").append(tags.joinToString(" ")).append(']')
        sizeOf(text)?.let { append(" (").append(it).append(')') }
    }

    val headers = mergeStreamHeaders(stream, hints, cleanUrl, defaultUserAgent, kodiHeaders)
    return StreamLink(
        url = url,
        source = addonName,
        title = decorated,
        qualityTag = resolution,
        headers = headers,
        referer = headers.headerOrNull("Referer"),
        resolutionRank = rank,
        seeders = seedersOf(low),
        addonOrder = addonOrder,
        fileIdx = stream.fileIdx,
        kind = kind,
        bingeGroup = hints?.bingeGroup,
        videoSize = videoSizeOrNull(hints?.videoSize),
    )
}

fun sortAndDedupe(links: List<StreamLink>): List<StreamLink> {
    val seen = mutableSetOf<String>()
    val unique = links.filter { link ->
        val hash = Regex("urn:b?t[ihm]+:([a-fA-F0-9]{40,64})")
            .find(link.url)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
        val identity = when {
            !link.bingeGroup.isNullOrEmpty() -> "binge:${link.bingeGroup}"
            hash != null -> "hash:$hash:${link.fileIdx}"
            else -> link.url.replace(Regex("^https?://"), "").trimEnd('/')
                .substringBefore("#").lowercase(Locale.ROOT)
        }
        identity.isNotEmpty() && seen.add(identity)
    }
    return unique.sortedWith(
        compareByDescending<StreamLink> { it.resolutionRank }
            .thenByDescending { it.seeders }
            .thenByDescending { it.videoSize ?: 0L }
            .thenBy { it.addonOrder }
    )
}

fun matchesQuery(entry: CatalogEntry, query: String): Boolean {
    val q = query.lowercase(Locale.ROOT).trim()
    if (q.isEmpty()) return false
    val haystack = (entry.name + " " + stripHtml(entry.description)).lowercase(Locale.ROOT)
    val tokens = q.split(Regex("\\s+")).filter { it.isNotEmpty() }
    val hits = tokens.count { token ->
        haystack.contains(token) ||
            stringList(entry.genres).any { it.lowercase(Locale.ROOT).contains(token) } ||
            stringList(entry.cast).any { it.lowercase(Locale.ROOT).contains(token) }
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

enum class StreamKind {
    MAGNET,

    TORRENT,

    DASH,

    HLS,

    M3U,

    RTMP,

    RTSP,

    FTP,

    PROGRESSIVE,

    YOUTUBE,

    EXTERNAL,

    ARCHIVE,

    NONE,
    ;

    val isPlayable: Boolean
        get() = when (this) {
            YOUTUBE, EXTERNAL, M3U, ARCHIVE, NONE -> false
            else -> true
        }

    fun linkType(): ExtractorLinkType? = when (this) {
        MAGNET -> ExtractorLinkType.MAGNET
        TORRENT -> ExtractorLinkType.TORRENT
        DASH -> ExtractorLinkType.DASH
        HLS -> ExtractorLinkType.M3U8
        PROGRESSIVE, RTMP, RTSP, FTP -> null
        M3U, ARCHIVE, YOUTUBE, EXTERNAL, NONE -> null
    }

    fun rejectionReason(): String = when (this) {
        M3U -> "M3U is a channel list, not a single stream"
        ARCHIVE -> "usenet/rar/zip/7z/tar source: no CloudStream player accepts these"
        NONE -> "no usable url or infoHash"
        else -> "not playable on this host"
    }
}

private val M3U8_TOKEN = Regex("(^|[=/_.?&%-])m3u8($|[=/_.?&%-])", RegexOption.IGNORE_CASE)
private val MPD_TOKEN = Regex("(^|[=/_.?&%-])mpd($|[=/_.?&%-])", RegexOption.IGNORE_CASE)
private val HLS_TOKEN = Regex("(^|[=/_.?&%-])(hls|playlist)($|[=/_.?&%-])", RegexOption.IGNORE_CASE)
private val M3U_TOKEN = Regex("(^|[=/_.?&%-])m3u($|[=/_.?&%-])", RegexOption.IGNORE_CASE)
private val DASH_TOKEN = Regex("(^|[=/_.?&%-])dash($|[=/_.?&%-])", RegexOption.IGNORE_CASE)
private val HLS_QUERY = Regex("[?&](format|type)=(hls|m3u8)", RegexOption.IGNORE_CASE)
private val DASH_QUERY = Regex("[?&](format|type)=(dash|mpd)", RegexOption.IGNORE_CASE)

private val PROGRESSIVE_EXTENSIONS = setOf(
    "mp4", "m4v", "mkv", "avi", "webm", "mov", "flv", "ts", "m2ts", "wmv", "mpg", "mpeg", "ogv",
)

private const val SCHEME_MAGNET = "magnet:"
private const val SCHEME_RTMP = "rtmp"
private const val SCHEME_RTMPS = "rtmps"
private const val SCHEME_RTSP = "rtsp"
private const val SCHEME_RTSPS = "rtsps"
private const val SCHEME_FTP = "ftp"
private const val SCHEME_FTPS = "ftps"

fun streamKindOf(rawUrl: String?, hasInfoHash: Boolean = false): StreamKind {
    val url = rawUrl?.trim().orEmpty()
    if (url.isEmpty()) return if (hasInfoHash) StreamKind.MAGNET else StreamKind.NONE

    val scheme = url.substringBefore(':', "").lowercase()
    if (url.startsWith(SCHEME_MAGNET, ignoreCase = true)) return StreamKind.MAGNET
    when (scheme) {
        SCHEME_RTMP, SCHEME_RTMPS -> return StreamKind.RTMP
        SCHEME_RTSP, SCHEME_RTSPS -> return StreamKind.RTSP
        SCHEME_FTP, SCHEME_FTPS -> return StreamKind.FTP
    }

    val path = url.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
    val tail = path.substringAfterLast('/')
    val route = url.substringAfter("://", url).substringAfter('/', "")

    if (tail.endsWith(".torrent")) return StreamKind.TORRENT
    if (tail.endsWith(".m3u")) return if (hasInfoHash) StreamKind.MAGNET else StreamKind.M3U
    if (tail.endsWith(".m3u8")) return StreamKind.HLS
    if (tail.endsWith(".mpd")) return StreamKind.DASH
    if (M3U8_TOKEN.containsMatchIn(route) || HLS_TOKEN.containsMatchIn(route) ||
        HLS_QUERY.containsMatchIn(route)
    ) {
        return StreamKind.HLS
    }
    if (MPD_TOKEN.containsMatchIn(route) || DASH_QUERY.containsMatchIn(route)) return StreamKind.DASH
    if (DASH_TOKEN.containsMatchIn(route)) return StreamKind.DASH
    if (M3U_TOKEN.containsMatchIn(route)) return if (hasInfoHash) StreamKind.MAGNET else StreamKind.M3U
    if (PROGRESSIVE_EXTENSIONS.any { tail.endsWith(".$it") }) return StreamKind.PROGRESSIVE
    return StreamKind.NONE
}

val mapper: ObjectMapper by lazy {
    ObjectMapper().registerKotlinModule()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
}

fun LinkRef.toJsonString(): String = mapper.writeValueAsString(this)

fun parseLinkRef(json: String): LinkRef? = try {
    mapper.readValue(json, LinkRef::class.java)
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

fun stringList(node: JsonNode?): List<String> {
    if (node == null || node.isNull) return emptyList()
    if (node.isArray) {
        return node.mapNotNull { it.asText(null)?.trim()?.takeIf { text -> text.isNotEmpty() } }
    }
    if (node.isValueNode) {
        val text = node.asText().trim()
        if (text.isEmpty()) return emptyList()
        return text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }
    return emptyList()
}

fun extractMetaEntry(body: String, id: String): CatalogEntry? {
    val wrapped = try {
        mapper.readValue(body, CatalogResponse::class.java)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
    wrapped?.let {
        it.meta ?: it.metas?.firstOrNull { meta -> meta.id == id } ?: it.metas?.singleOrNull()
    }?.takeIf { it.name.isNotEmpty() }?.let { return it }
    return try {
        mapper.readValue(body, CatalogEntry::class.java)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }?.takeIf { it.id.isNotEmpty() && it.name.isNotEmpty() }
}

fun maybeClipboardManifest(context: Context): String? = try {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
    if (text.isEmpty()) return null
    normalizeAddonUrl(text)
} catch (_: Exception) {
    null
}

private val NUMERIC = Regex("^-?\\d+$")

private val ISO_INSTANT = Regex("^(\\d{4})-(\\d{2})-(\\d{2})[T ](\\d{2}):(\\d{2})(?::(\\d{2}))?")
private val DATE_ONLY = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$")
private val YEAR_ONLY = Regex("^(\\d{4})$")
private val TEXT_DATE = Regex("(?i)\\b(\\d{1,2})\\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?,?\\s+(\\d{4})\\b")
private val TEXT_DATE_US = Regex("(?i)\\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?\\s+(\\d{1,2}),?\\s+(\\d{4})\\b")
private val MONTHS = mapOf(
    "jan" to 0, "feb" to 1, "mar" to 2, "apr" to 3, "may" to 4, "jun" to 5,
    "jul" to 6, "aug" to 7, "sep" to 8, "oct" to 9, "nov" to 10, "dec" to 11,
)

private fun utc(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0, second: Int = 0): Long? =
    runCatching {
        java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month - 1, day, hour, minute, second)
        }.timeInMillis
    }.getOrNull()

fun parseReleaseDate(raw: String?): Long? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    if (NUMERIC.matches(text)) {
        val n = text.toLongOrNull() ?: return null
        return when {
            n in 1900..2100 -> utc(n.toInt(), 1, 1)
            n > 1_000_000_000_000L -> n
            n > 100_000_000L -> n * 1000L
            else -> null
        }
    }
    ISO_INSTANT.find(text)?.let { m ->
        val tail = text.substringAfter('T', text.substringAfter(' ', ""))
        val hasZone = tail.endsWith("Z") || Regex("[+-]\\d{2}:?\\d{2}$").containsMatchIn(tail)
        val zone = if (hasZone) tail else null
        val base = utc(
            m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(),
            m.groupValues[4].toInt(), m.groupValues[5].toInt(), m.groupValues.getOrNull(6)?.toInt() ?: 0,
        ) ?: return null
        if (!hasZone) return base
        val offset = zone?.let(::offsetMinutesOf) ?: return base
        return base - offset * 60_000L
    }
    DATE_ONLY.find(text)?.let { m ->
        return utc(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
    }
    YEAR_ONLY.find(text)?.let { m -> return utc(m.groupValues[1].toInt(), 1, 1) }
    return dateFromText(text)
}

private fun offsetMinutesOf(tail: String): Long? {
    if (tail.endsWith("Z")) return 0L
    val m = Regex("([+-])(\\d{2}):?(\\d{2})$").find(tail) ?: return null
    val sign = if (m.groupValues[1] == "-") -1L else 1L
    return sign * (m.groupValues[2].toLong() * 60 + m.groupValues[3].toLong())
}

fun dateFromText(text: String?): Long? {
    val t = text?.trim().orEmpty()
    if (t.isEmpty()) return null
    TEXT_DATE.find(t)?.let { m ->
        return utc(
            m.groupValues[3].toInt(), MONTHS[m.groupValues[2].lowercase()]!! + 1, m.groupValues[1].toInt()
        )
    }
    TEXT_DATE_US.find(t)?.let { m ->
        return utc(
            m.groupValues[3].toInt(), MONTHS[m.groupValues[1].lowercase()]!! + 1, m.groupValues[2].toInt()
        )
    }
    return null
}

fun sanitizeSubtitleLang(raw: String?): String {
    val tag = raw?.trim().orEmpty()
    if (tag.isEmpty()) return "Unknown"
    return SubtitleHelper.fromTagToEnglishLanguageName(tag) ?: tag
}

fun subtitleSlugFor(streamId: String?): String? {
    val text = streamId?.trim().orEmpty()
    if (text.isEmpty()) return null
    val parts = text.split(':')
    if (!parts[0].matches(Regex("^tt\\d+$"))) return null
    if (parts.size >= 3 && parts[1].all { it.isDigit() } && parts[2].all { it.isDigit() }) {
        return "series/${parts[0]}:${parts[1]}:${parts[2]}"
    }
    return "movie/${parts[0]}"
}

inline fun <reified T : Any> parseJson(text: String): T? = try {
    mapper.readValue(text, T::class.java)
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}
