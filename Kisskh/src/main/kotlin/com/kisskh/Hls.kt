package com.kisskh

import java.net.URI

object Hls {
    data class Variant(val url: String, val height: Int, val audioGroup: String)
    data class Audio(val url: String, val groupId: String)

    data class Master(
        val isMaster: Boolean,
        val variants: List<Variant> = emptyList(),
        val audios: List<Audio> = emptyList(),
    )

    private val attributes = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

    private fun attrs(line: String): Map<String, String> =
        attributes.findAll(line).associate { it.groupValues[1] to it.groupValues[2].trim('"') }

    private fun resolve(base: String, target: String): String =
        runCatching { URI(base).resolve(target).toString() }.getOrElse { target }

    fun parse(text: String, playlistUrl: String): Master {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty() || !lines[0].startsWith("#EXTM3U")) return Master(false)
        val variants = ArrayList<Variant>()
        val audios = ArrayList<Audio>()
        for (index in lines.indices) {
            val line = lines[index]
            if (line.startsWith("#EXT-X-MEDIA:")) {
                val map = attrs(line)
                val uri = map["URI"].orEmpty()
                if (uri.isNotEmpty()) {
                    audios += Audio(resolve(playlistUrl, uri), map["GROUP-ID"].orEmpty())
                }
            } else if (line.startsWith("#EXT-X-STREAM-INF:")) {
                val map = attrs(line)
                var target: String? = null
                for (next in index + 1 until lines.size) {
                    if (!lines[next].startsWith("#")) {
                        target = lines[next]
                        break
                    }
                }
                if (target == null) continue
                val resolution = (map["RESOLUTION"] ?: "").split("x", limit = 2)
                val width = resolution.getOrNull(0)?.toIntOrNull() ?: 0
                val height = resolution.getOrNull(1)?.toIntOrNull() ?: 0
                variants += Variant(
                    url = resolve(playlistUrl, target),
                    height = if (width > 0 || height > 0) height else 0,
                    audioGroup = map["AUDIO"].orEmpty(),
                )
            }
        }
        return Master(variants.isNotEmpty(), variants, audios)
    }
}
