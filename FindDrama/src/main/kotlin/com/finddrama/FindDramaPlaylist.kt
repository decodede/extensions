package com.finddrama

data class Variant(
    val url: String,
    val height: Int,
    val bandwidth: Int,
)

data class MediaTrack(
    val kind: String,
    val url: String,
    val language: String,
    val name: String,
)

class Playlist(
    val readable: Boolean,
    val isMaster: Boolean,
    val variants: List<Variant>,
    val tracks: List<MediaTrack>,
    val height: Int,
) {
    companion object {
        val EMPTY = Playlist(false, false, emptyList(), emptyList(), 0)
    }
}

object FindDramaPlaylist {
    private val streamInf = Regex("""#EXT-X-STREAM-INF:([^\n]*)\n([^\n]+)""")
    private val media = Regex("""#EXT-X-MEDIA:([^\n]+)""")
    private val resolution = Regex("""RESOLUTION=(\d+)x(\d+)""")
    private val bandwidth = Regex("""BANDWIDTH=(\d+)""")
    private val height = Regex("""\b(\d{3,4})p\b""")

    private fun isPlaylist(body: String): Boolean = body.trimStart().startsWith("#EXTM3U")

    private fun attribute(text: String, name: String): String? =
        Regex("""\b$name="([^"]*)"""").find(text)?.groupValues?.get(1)

    fun parse(body: String?, baseUrl: String): Playlist {
        if (body.isNullOrBlank() || !isPlaylist(body)) return Playlist.EMPTY
        val tracks = media.findAll(body)
            .map { match ->
                val raw = match.groupValues[1]
                val kind = attribute(raw, "TYPE")?.trim().orEmpty()
                val uri = attribute(raw, "URI")?.trim().orEmpty()
                MediaTrack(
                    kind = kind,
                    url = if (uri.isEmpty()) "" else resolve(baseUrl, uri),
                    language = attribute(raw, "LANGUAGE")?.trim().orEmpty(),
                    name = attribute(raw, "NAME")?.trim().orEmpty(),
                )
            }
            .filter { it.kind.equals("SUBTITLES", true) || it.kind.equals("AUDIO", true) }
            .filter { it.url.isNotEmpty() }
            .toList()

        val inline = height.find(baseUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        if (!body.contains("#EXT-X-STREAM-INF")) {
            return Playlist(true, false, emptyList(), tracks, inline)
        }

        val variants = streamInf.findAll(body).mapNotNull { match ->
            val attrs = match.groupValues[1]
            val url = resolve(baseUrl, match.groupValues[2].trim())
            if (url.isEmpty()) return@mapNotNull null
            val height = resolution.find(attrs)?.groupValues?.get(2)?.toIntOrNull()
                ?: resolution.find(attrs)?.groupValues?.get(1)?.toIntOrNull()
                ?: 0
            Variant(
                url = url,
                height = height,
                bandwidth = bandwidth.find(attrs)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            )
        }.toList()

        val best = variants.maxByOrNull { variant ->
            if (variant.height > 0) variant.height else variant.bandwidth
        }

        return Playlist(true, true, variants, tracks, best?.height ?: inline)
    }

    private fun resolve(baseUrl: String, target: String): String {
        if (target.isEmpty()) return ""
        if (target.startsWith("http://") || target.startsWith("https://")) return target
        if (target.startsWith("//")) return "https:$target"
        if (target.startsWith("/")) return origin(baseUrl) + target
        val slash = baseUrl.lastIndexOf('/')
        return if (slash < 0) target else baseUrl.substring(0, slash + 1) + target
    }

    private fun origin(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return ""
        val hostStart = schemeEnd + 3
        val pathStart = url.indexOf('/', hostStart)
        return if (pathStart < 0) url else url.substring(0, pathStart)
    }
}
