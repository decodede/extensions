package com.nunodrama

import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.serialization.decodeFromString
import org.jsoup.Jsoup
import java.net.URI

data class PlayerSource(
    val url: String,
    val kind: String,
    val title: String,
    val cover: String,
    val language: String,
    val encrypted: Boolean,
    val key: String,
)

data class EpisodeRef(
    val number: Int,
    val url: String,
)

object NunoDramaStreams {

    private const val MAX_HLS_VARIANTS = 12

    fun parsePlayer(html: String): PlayerSource? {
        val document = runCatching { Jsoup.parse(html) }.getOrNull() ?: return null
        val video = document.selectFirst("video#player")
            ?: document.selectFirst("video")
            ?: document.selectFirst("[data-encrypt-url]")
            ?: return null
        val raw = video.attr("data-src").trim()
            .ifEmpty { video.attr("src").trim() }
            .ifEmpty { video.attr("data-encrypt-url").trim() }
        if (raw.isEmpty()) return null
        val url = NunoDramaClient.absolute(raw)
        return PlayerSource(
            url = url,
            kind = video.attr("data-kind").trim().lowercase().ifEmpty { guessKind(url) },
            title = video.attr("data-title").trim(),
            cover = video.attr("data-cover").trim().takeIf { it.isNotEmpty() }
                ?.let { NunoDramaClient.absolute(it) } ?: "",
            language = video.attr("data-lang").trim().ifEmpty { LANG_ID },
            encrypted = video.attr("data-encrypted").trim().equals("true", ignoreCase = true),
            key = video.attr("data-key").trim(),
        )
    }

    fun parseEpisodes(html: String, absolute: (String) -> String): List<EpisodeRef> {
        val document = runCatching { Jsoup.parse(html) }.getOrNull() ?: return emptyList()
        val links = document.select("[data-ep-list] a[data-ep-index]")
            .ifEmpty { document.select("a[data-ep-index]") }
        return links.mapNotNull { anchor ->
            val number = anchor.attr("data-ep-index").trim().toIntOrNull() ?: return@mapNotNull null
            EpisodeRef(number = number, url = absolute(anchor.attr("href").trim()))
        }.distinctBy { it.number }.sortedBy { it.number }
    }

    fun parseSeriesLd(html: String): TvSeriesLd? {
        for (match in LD_SCRIPT.findAll(html)) {
            val payload = match.groupValues[1].trim()
            if (!payload.contains("\"TVSeries\"")) continue
            val parsed = runCatching {
                NunoDramaClient.json.decodeFromString<TvSeriesLd>(payload)
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    fun guessKind(url: String): String = when {
        url.contains(".m3u8", ignoreCase = true) -> "hls"
        url.contains(".mpd", ignoreCase = true) -> "dash"
        else -> "mp4"
    }

    fun linkType(kind: String, url: String): ExtractorLinkType = when {
        kind == "hls" || url.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
        kind == "dash" || url.contains(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
        else -> ExtractorLinkType.VIDEO
    }

    fun isMasterPlaylist(body: String): Boolean = body.contains("#EXT-X-STREAM-INF")
    fun parseMaster(body: String, playlistUrl: String): List<HlsVariant> {
        val out = LinkedHashMap<String, HlsVariant>()
        val lines = body.lines()
        var index = 0
        while (index < lines.size) {
            val line = lines[index].trim()
            if (!line.startsWith("#EXT-X-STREAM-INF")) {
                index++
                continue
            }
            val bandwidth = Regex("""BANDWIDTH=(\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val resolution = Regex("""RESOLUTION=(\d+x\d+)""").find(line)?.groupValues?.get(1).orEmpty()
            val height = resolution.substringAfter('x', "").toIntOrNull() ?: 0
            var uri = ""
            var probe = index + 1
            while (probe < lines.size) {
                val candidate = lines[probe].trim()
                if (candidate.isNotEmpty() && !candidate.startsWith("#")) {
                    uri = candidate
                    break
                }
                probe++
            }
            if (uri.isNotEmpty()) {
                val absolute = resolve(playlistUrl, uri)
                out.putIfAbsent(absolute, HlsVariant(absolute, resolution, bandwidth, height))
            }
            index = probe + 1
        }
        return out.values.sortedByDescending { it.height }.take(MAX_HLS_VARIANTS)
    }

    fun resolve(base: String, relative: String): String = runCatching { URI(base).resolve(relative).toString() }
        .getOrElse { if (relative.startsWith("http")) relative else base.trimEnd('/') + "/" + relative.trimStart('/') }

    fun qualityLabel(height: Int, bandwidth: Int): String = when {
        height > 0 -> "${height}p"
        bandwidth > 0 -> "${bandwidth / 1000}kbps"
        else -> "Auto"
    }

    private val LD_SCRIPT = Regex("""<script[^>]*type="application/ld\+json"[^>]*>([\s\S]*?)</script>""")
}
