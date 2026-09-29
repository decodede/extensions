package com.kisskh

import android.util.Log
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLEncoder

@Serializable
data class Media(
    @SerialName("id") val id: Long = 0,
    @SerialName("title") val title: String? = null,
    @SerialName("thumbnail") val thumbnail: String? = null,
    @SerialName("episodesCount") val episodesCount: Int = 0,
)

@Serializable
data class ListResponse(
    @SerialName("pageSize") val pageSize: Int = 10,
    @SerialName("totalCount") val totalCount: Int = 0,
    @SerialName("data") val data: List<Media> = emptyList(),
)

@Serializable
data class EpisodeRef(
    @SerialName("id") val id: Long = 0,
    @SerialName("number") val number: Double = 0.0,
)

@Serializable
data class DramaDetail(
    @SerialName("id") val id: Long = 0,
    @SerialName("title") val title: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("releaseDate") val releaseDate: String? = null,
    @SerialName("country") val country: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("type") val type: String? = null,
    @SerialName("thumbnail") val thumbnail: String? = null,
    @SerialName("episodes") val episodes: List<EpisodeRef> = emptyList(),
)

@Serializable
data class EpisodeSource(
    @SerialName("Video") val video: String? = null,
    @SerialName("Video_tmp") val videoTmp: String? = null,
    @SerialName("ThirdParty") val thirdParty: String? = null,
    @SerialName("Type") val type: Int = 0,
)

@Serializable
data class SubtitleTrack(
    @SerialName("src") val src: String = "",
    @SerialName("label") val label: String? = null,
    @SerialName("land") val land: String? = null,
)

@Serializable
data class EpisodeData(
    val title: String,
    val dramaId: Long,
    val episodeNumber: Double,
    val episodeId: Long,
)

data class ResolvedSource(val url: String, val kind: String, val type: Int)

fun episodeLabel(number: Double): String =
    if (number % 1.0 == 0.0) "Episode ${number.toInt()}" else "Episode $number"

fun yearOf(releaseDate: String?): Int? =
    releaseDate?.take(4)?.toIntOrNull()?.takeIf { it in 1900..2999 }

fun posterUrl(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val absolute = if (raw.startsWith("http")) raw else "https:$raw"
    return absolute.replace("_face/", "/")
}

fun posterHeaders(url: String?): Map<String, String> {
    val host = runCatching { URI(url ?: "").host }.getOrNull().orEmpty()
    val headers = HashMap<String, String>()
    headers["User-Agent"] = USER_AGENT
    if (host.isNotEmpty()) headers["Referer"] = "https://$host/"
    return headers
}

fun streamHeaders(referer: String, origin: String): Map<String, String> = mapOf(
    "User-Agent" to USER_AGENT,
    "Referer" to referer,
    "Origin" to origin,
    "Accept" to "*/*",
)

const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/131.0.0.0 Safari/537.36"

object Api {
    const val PAGE_SIZE = 40
    const val TIMEOUT = 20L

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    fun encodeEpisode(data: EpisodeData): String = json.encodeToString(data)

    fun decodeEpisode(payload: String): EpisodeData? =
        runCatching { json.decodeFromString<EpisodeData>(payload) }.getOrNull()

    suspend fun getText(url: String, referer: String, origin: String): String? = withContext(Dispatchers.IO) {
        try {
            val response = app.get(
                url,
                headers = mapOf("User-Agent" to USER_AGENT, "Accept" to "*/*", "Origin" to origin),
                referer = referer,
                timeout = TIMEOUT,
            )
            if (response.code in 200..299) response.text
            else {
                Log.w(TAG, "getText HTTP ${response.code} $url")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "getText failed $url", e)
            null
        }
    }

    suspend inline fun <reified T> getJson(url: String, config: SiteConfig.Snapshot, origin: String): T? =
        withContext(Dispatchers.IO) {
            try {
                val response = app.get(
                    url,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Accept" to "application/json, text/plain, */*",
                        "Origin" to origin,
                    ),
                    referer = config.host + "/",
                    timeout = TIMEOUT,
                )
                if (response.code !in 200..299) {
                    Log.w(TAG, "getJson HTTP ${response.code} $url :: ${response.text.take(200)}")
                    return@withContext null
                }
                json.decodeFromString<T>(response.text)
            } catch (e: Exception) {
                Log.w(TAG, "getJson failed $url", e)
                null
            }
        }

    suspend fun list(
        config: SiteConfig.Snapshot,
        page: Int,
        type: Int,
        order: Int,
        status: Int,
        country: Int,
    ): ListResponse? {
        val url = "${config.api}DramaList/List?page=$page&type=$type&sub=0" +
            "&country=$country&status=$status&order=$order&pageSize=$PAGE_SIZE"
        return getJson(url, config, config.host)
    }

    suspend fun search(config: SiteConfig.Snapshot, query: String): List<Media> {
        val encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        return getJson<List<Media>>("${config.api}DramaList/Search?q=$encoded&type=0", config, config.host)
            ?: emptyList()
    }

    suspend fun detail(config: SiteConfig.Snapshot, dramaId: Long): DramaDetail? =
        getJson<DramaDetail>("${config.api}DramaList/Drama/$dramaId?isq=false", config, config.host)
            ?.takeIf { it.id != 0L }

    suspend fun episode(
        config: SiteConfig.Snapshot,
        episodeId: Long,
        referer: String,
    ): EpisodeSource? {
        val url = "${config.api}DramaList/Episode/$episodeId.png" +
            "?err=false&ts=null&time=null&kkey=${config.episodeKey(episodeId)}"
        return getJson(url, config, config.host)
    }

    suspend fun subtitles(
        config: SiteConfig.Snapshot,
        episodeId: Long,
        referer: String,
    ): List<SubtitleTrack> {
        val url = "${config.api}Sub/$episodeId?kkey=${config.subtitleKey(episodeId)}"
        return getJson<List<SubtitleTrack>>(url, config, config.host) ?: emptyList()
    }

    suspend fun resolve(
        config: SiteConfig.Snapshot,
        referer: String,
        episodeId: Long,
    ): Pair<EpisodeSource?, List<SubtitleTrack>> = coroutineScope {
        val stream = async { episode(config, episodeId, referer) }
        val subs = async { subtitles(config, episodeId, referer) }
        stream.await() to subs.await()
    }

    fun sources(source: EpisodeSource?): List<ResolvedSource> {
        if (source == null) return emptyList()
        val out = ArrayList<ResolvedSource>(3)
        for ((value, kind) in listOf(
            source.video to "primary",
            source.videoTmp to "backup",
            source.thirdParty to "embed",
        )) {
            val url = value?.trim().orEmpty()
            if (url.isEmpty() || url.contains("tickcounter.com")) continue
            out += ResolvedSource(url, kind, source.type)
        }
        return out
    }

    fun playable(detail: DramaDetail): List<EpisodeRef> =
        detail.episodes.filter { it.id != 0L && it.number > 0.0 }.sortedBy { it.number }
}

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
