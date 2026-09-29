package com.kisskh

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Session
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URI
import java.net.URLEncoder

@Serializable
data class Media(
    @JsonProperty("id") @SerialName("id") val id: Long = 0,
    @JsonProperty("title") @SerialName("title") val title: String? = null,
    @JsonProperty("thumbnail") @SerialName("thumbnail") val thumbnail: String? = null,
    @JsonProperty("episodesCount") @SerialName("episodesCount") val episodesCount: Int = 0,
)

@Serializable
data class ListResponse(
    @JsonProperty("pageSize") @SerialName("pageSize") val pageSize: Int = 10,
    @JsonProperty("totalCount") @SerialName("totalCount") val totalCount: Int = 0,
    @JsonProperty("data") @SerialName("data") val data: List<Media> = emptyList(),
)

@Serializable
data class EpisodeRef(
    @JsonProperty("id") @SerialName("id") val id: Long = 0,
    @JsonProperty("number") @SerialName("number") val number: Double = 0.0,
)

fun episodeLabel(number: Double): String =
    if (number % 1.0 == 0.0) "Episode ${number.toInt()}" else "Episode $number"

@Serializable
data class DramaDetail(
    @JsonProperty("id") @SerialName("id") val id: Long = 0,
    @JsonProperty("title") @SerialName("title") val title: String? = null,
    @JsonProperty("description") @SerialName("description") val description: String? = null,
    @JsonProperty("releaseDate") @SerialName("releaseDate") val releaseDate: String? = null,
    @JsonProperty("country") @SerialName("country") val country: String? = null,
    @JsonProperty("status") @SerialName("status") val status: String? = null,
    @JsonProperty("type") @SerialName("type") val type: String? = null,
    @JsonProperty("thumbnail") @SerialName("thumbnail") val thumbnail: String? = null,
    @JsonProperty("episodes") @SerialName("episodes") val episodes: List<EpisodeRef> = emptyList(),
)

@Serializable
data class EpisodeSource(
    @JsonProperty("Video") @SerialName("Video") val video: String? = null,
    @JsonProperty("Video_tmp") @SerialName("Video_tmp") val videoTmp: String? = null,
    @JsonProperty("ThirdParty") @SerialName("ThirdParty") val thirdParty: String? = null,
    @JsonProperty("Type") @SerialName("Type") val type: Int = 0,
)

@Serializable
data class SubtitleTrack(
    @JsonProperty("src") @SerialName("src") val src: String = "",
    @JsonProperty("label") @SerialName("label") val label: String? = null,
    @JsonProperty("land") @SerialName("land") val land: String? = null,
)

@Serializable
data class EpisodeData(
    val title: String,
    val dramaId: Long,
    val episodeNumber: Double,
    val episodeId: Long,
)

data class ResolvedSource(val url: String, val kind: String, val type: Int)

object Api {
    private const val PAGE_SIZE = 40
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Safari/537.36"

    suspend fun getText(session: Session, url: String, referer: String, origin: String): String =
        withContext(Dispatchers.IO) {
            session.get(url, referer = referer, headers = mapOf("Origin" to origin)).text
        }

    suspend fun list(
        session: Session,
        config: SiteConfig.Snapshot,
        page: Int,
        type: Int,
        order: Int,
        status: Int,
        country: Int,
    ): ListResponse {
        val url = "${config.api}DramaList/List?page=$page&type=$type&sub=0" +
            "&country=$country&status=$status&order=$order&pageSize=$PAGE_SIZE"
        return withContext(Dispatchers.IO) {
            session.get(url, referer = config.host + "/", headers = mapOf("Origin" to config.host))
                .tryParseJson<ListResponse>() ?: throw IllegalStateException("bad catalogue response")
        }
    }

    suspend fun search(session: Session, config: SiteConfig.Snapshot, query: String): List<Media> {
        val url = "${config.api}DramaList/Search?q=${URLEncoder.encode(query, "UTF-8").replace("+", "%20")}&type=0"
        return withContext(Dispatchers.IO) {
            runCatching { session.get(url, referer = config.host + "/").tryParseJson<List<Media>>() }
                .getOrNull() ?: emptyList()
        }
    }

    suspend fun detail(session: Session, config: SiteConfig.Snapshot, dramaId: Long): DramaDetail {
        val url = "${config.api}DramaList/Drama/$dramaId?isq=false"
        return withContext(Dispatchers.IO) {
            val detail = session.get(url, referer = config.host + "/").tryParseJson<DramaDetail>()
            if (detail == null || detail.id == 0L) throw IllegalStateException("empty detail for $dramaId")
            detail
        }
    }

    suspend fun episode(
        session: Session,
        config: SiteConfig.Snapshot,
        episodeId: Long,
        referer: String,
    ): EpisodeSource? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "${config.api}DramaList/Episode/$episodeId.png" +
                "?err=false&ts=null&time=null&kkey=${config.episodeKey(episodeId)}"
            session.get(url, referer = referer, headers = mapOf("Origin" to config.host))
                .tryParseJson<EpisodeSource>()
        }.getOrNull()
    }

    suspend fun subtitles(
        session: Session,
        config: SiteConfig.Snapshot,
        episodeId: Long,
        referer: String,
    ): List<SubtitleTrack> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "${config.api}Sub/$episodeId?kkey=${config.subtitleKey(episodeId)}"
            session.get(url, referer = referer, headers = mapOf("Origin" to config.host))
                .tryParseJson<List<SubtitleTrack>>()
        }.getOrNull() ?: emptyList()
    }

    suspend fun resolve(
        session: Session,
        config: SiteConfig.Snapshot,
        referer: String,
        episodeId: Long,
    ): Pair<EpisodeSource?, List<SubtitleTrack>> = coroutineScope {
        val stream = async { episode(session, config, episodeId, referer) }
        val subs = async { subtitles(session, config, episodeId, referer) }
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

    fun posterHeaders(url: String?): Map<String, String> {
        val host = runCatching {
            URI(if (url?.startsWith("http") == true) url else "https:$url").host
        }.getOrNull().orEmpty()
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

    fun yearOf(releaseDate: String?): Int? =
        releaseDate?.take(4)?.toIntOrNull()?.takeIf { it in 1900..2999 }
}
