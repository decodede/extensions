package com.kisskh

import android.util.Log
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAudioFile
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil

private data class Row(
    val label: String,
    val type: Int,
    val order: Int,
    val status: Int,
    val country: Int,
    val data: String = "type=$type&country=$country&status=$status&order=$order",
)

private const val TYPE_ALL = 0
private const val TYPE_SERIES = 1
private const val TYPE_MOVIE = 2
private const val TYPE_ANIME = 3
private const val TYPE_HOLLYWOOD = 4

private const val ORDER_POPULAR = 1
private const val ORDER_LAST_UPDATE = 2

private const val STATUS_ALL = 0
private const val STATUS_ONGOING = 1
private const val STATUS_COMPLETED = 2
private const val STATUS_UPCOMING = 3

private const val COUNTRY_ALL = 0
private const val COUNTRY_CHINA = 1
private const val COUNTRY_KOREA = 2

private val CATALOGUES = listOf(
    Row("Latest", TYPE_ALL, ORDER_LAST_UPDATE, STATUS_ALL, COUNTRY_ALL),
    Row("Popular", TYPE_ALL, ORDER_POPULAR, STATUS_ALL, COUNTRY_ALL),
    Row("Upcoming", TYPE_ALL, ORDER_LAST_UPDATE, STATUS_UPCOMING, COUNTRY_ALL),
    Row("Ongoing", TYPE_ALL, ORDER_LAST_UPDATE, STATUS_ONGOING, COUNTRY_ALL),
    Row("Completed", TYPE_ALL, ORDER_LAST_UPDATE, STATUS_COMPLETED, COUNTRY_ALL),
    Row("TVSeries", TYPE_SERIES, ORDER_LAST_UPDATE, STATUS_ALL, COUNTRY_ALL),
    Row("K-Drama", TYPE_SERIES, ORDER_POPULAR, STATUS_ALL, COUNTRY_KOREA),
    Row("C-Drama", TYPE_SERIES, ORDER_POPULAR, STATUS_ALL, COUNTRY_CHINA),
    Row("Movies", TYPE_MOVIE, ORDER_LAST_UPDATE, STATUS_ALL, COUNTRY_ALL),
    Row("Popular Movies", TYPE_MOVIE, ORDER_POPULAR, STATUS_ALL, COUNTRY_ALL),
    Row("Anime", TYPE_ANIME, ORDER_LAST_UPDATE, STATUS_ALL, COUNTRY_ALL),
    Row("Popular Anime", TYPE_ANIME, ORDER_POPULAR, STATUS_ALL, COUNTRY_ALL),
    Row("Hollywood", TYPE_HOLLYWOOD, ORDER_LAST_UPDATE, STATUS_ALL, COUNTRY_ALL),
    Row("Popular Hollywood", TYPE_HOLLYWOOD, ORDER_POPULAR, STATUS_ALL, COUNTRY_ALL),
)

private val byData = CATALOGUES.associateBy { it.data }

class KissKhProvider : MainAPI() {
    override var name = "KissKH"
    override var mainUrl = SiteConfig.hosts.first()

    override val hasMainPage = true
    override val hasQuickSearch = true
    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 120L

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie,
        TvType.Anime,
        TvType.AsianDrama,
        TvType.OVA,
    )

    override val mainPage = mainPageOf(*CATALOGUES.map { it.data to it.label }.toTypedArray())

    private fun Media.toSearchResponse(): TvSeriesSearchResponse? {
        val mediaId = id
        val label = title
        val thumb = posterUrl(thumbnail)
        if (mediaId == 0L || label.isNullOrBlank()) return null
        return newTvSeriesSearchResponse(label, "$mainUrl/Drama/$mediaId", TvType.TvSeries, fix = false) {
            posterUrl = thumb
            posterHeaders = posterHeaders(thumb)
            episodes = episodesCount.takeIf { it > 0 }
            id = mediaId.toInt()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val row = byData[request.data] ?: CATALOGUES.first()
        val response = Api.list(SiteConfig.load(), page, row.type, row.order, row.status, row.country)
            ?: return null
        val lastPage = if (response.pageSize > 0) {
            ceil(response.totalCount.toDouble() / response.pageSize).toInt().coerceAtLeast(1)
        } else {
            1
        }
        val items = response.data.mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request, items, hasNext = page < lastPage)
    }

    override suspend fun search(query: String): List<TvSeriesSearchResponse> =
        Api.search(SiteConfig.load(), query).mapNotNull { it.toSearchResponse() }

    override suspend fun quickSearch(query: String) = search(query)

    override suspend fun load(url: String): LoadResponse {
        val dramaId = url.substringAfterLast('/').toLongOrNull()
            ?: throw ErrorLoadingException("unrecognised KissKH url: $url")
        val detail = Api.detail(SiteConfig.load(), dramaId)
            ?: throw ErrorLoadingException("drama $dramaId not found")
        val title = detail.title ?: throw ErrorLoadingException("drama $dramaId has no title")
        val poster = posterUrl(detail.thumbnail)
        val type = tvType(detail.type)
        val episodes = Api.playable(detail)
        val common: suspend TvSeriesLoadResponse.() -> Unit = {
            plot = detail.description
            year = yearOf(detail.releaseDate)
            posterUrl = poster
            posterHeaders = posterHeaders(poster)
            tags = listOfNotNull(detail.country, detail.status)
        }

        return newTvSeriesLoadResponse(title, url, type, episodes.map { episode ->
            newEpisode(Api.encodeEpisode(EpisodeData(title, dramaId, episode.number, episode.id))) {
                name = episodeLabel(episode.number)
                this.episode = episode.number.toInt()
                posterUrl = poster
            }
        }, common)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val episode = Api.decodeEpisode(data) ?: run {
            Log.w(TAG, "loadLinks could not decode episode data: $data")
            return false
        }
        val config = SiteConfig.load()
        val referer = config.watchReferer(episode.title, episode.dramaId, episode.episodeNumber, episode.episodeId)
        val headers = streamHeaders(referer, config.host)
        val (payload, tracks) = Api.resolve(config, referer, episode.episodeId)

        for (track in tracks) {
            subtitleCallback(
                newSubtitleFile(track.label ?: track.land ?: "Subtitle", track.src) {
                    this.headers = headers
                }
            )
        }

        val resolved = Api.sources(payload)
        if (resolved.isEmpty()) {
            Log.w(TAG, "loadLinks found no source for episode ${episode.episodeId} payload=$payload")
            return false
        }

        for (source in resolved) {
            if (source.url.contains(".m3u8", ignoreCase = true) || source.type == 1) {
                emitVariants(source, referer, config.host, headers, callback)
            } else {
                callback(
                    newExtractorLink(name, "$name (${source.kind})", source.url) {
                        this.referer = referer
                        this.headers = headers
                    }
                )
            }
        }
        return true
    }

    private suspend fun emitVariants(
        source: ResolvedSource,
        referer: String,
        origin: String,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit,
    ) {
        val playlist = withContext(Dispatchers.IO) {
            Api.getText(source.url, referer, origin)?.let { Hls.parse(it, source.url) }
        }
        if (playlist == null || !playlist.isMaster) {
            callback(
                newExtractorLink(name, "$name (${source.kind})", source.url) {
                    this.referer = referer
                    this.headers = headers
                }
            )
            return
        }
        for (variant in playlist.variants.sortedByDescending { it.height }) {
            val label = if (variant.height > 0) "${variant.height}p" else source.kind
            val group = variant.audioGroup
            val audio = if (group.isEmpty()) emptyList() else playlist.audios.filter { it.groupId == group }
            callback(
                newExtractorLink(name, "$name ($label)", variant.url) {
                    this.referer = referer
                    this.headers = headers
                    quality = variant.height.takeIf { it > 0 } ?: Qualities.Unknown.value
                    if (audio.isNotEmpty()) {
                        audioTracks = audio.map { track ->
                            newAudioFile(track.url) { this.headers = headers }
                        }
                    }
                }
            )
        }
    }
}

private fun tvType(siteType: String?): TvType = when (siteType?.lowercase()) {
    "movie" -> TvType.Movie
    "anime" -> TvType.Anime
    "tvseries" -> TvType.AsianDrama
    else -> TvType.TvSeries
}

@CloudstreamPlugin
class KissKhPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(KissKhProvider())
    }
}
