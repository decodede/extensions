package com.kimoitv

import com.lagradost.api.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jsoup.nodes.Document
import java.net.URLEncoder

data class CatalogItem(
    val name: String,
    val url: String,
    val posterUrl: String?,
    val year: Int?,
    val type: TvType,
)

@Serializable
data class MediaFile(
    val url: String,
    val name: String,
    val season: Int,
)

@Serializable
data class MediaPayload(
    val files: List<MediaFile>,
) {
    fun encode(): String = JSON.encodeToString(this)

    companion object {
        val JSON = Json { ignoreUnknownKeys = true }

        fun decode(value: String): MediaPayload = JSON.decodeFromString(value)
    }
}

data class SeasonRef(
    val url: String,
    val season: Int,
)

data class StreamRef(
    val id: String,
    val dataName: String,
    val url: String,
    val label: String,
)

data class StreamSource(
    val url: String,
    val name: String,
    val host: String,
    val quality: String?,
    val type: ExtractorLinkType?,
)

data class StreamTrack(
    val url: String,
    val lang: String,
    val label: String,
)

data class StreamBundle(
    val sources: List<StreamSource>,
    val tracks: List<StreamTrack>,
)

object Catalog {

    fun url(path: String, sort: String, page: Int): String {
        val params = buildList {
            if (sort.isNotBlank()) add("${K.PARAM_SORT}=${URLEncoder.encode(sort, K.ENCODING)}")
            if (page > K.FIRST_PAGE) add("${K.PARAM_PAGE}=$page")
        }
        val base = K.BASE_URL + path
        return if (params.isEmpty()) base else "$base?${params.joinToString(K.PARAM_SEPARATOR)}"
    }

    fun searchUrl(query: String, page: Int): String =
        K.BASE_URL + K.PATH_SEARCH + "?${K.PARAM_QUERY}=${URLEncoder.encode(query, K.ENCODING)}" +
            (if (page > K.FIRST_PAGE) "&${K.PARAM_PAGE}=$page" else K.EMPTY)

    fun totalPages(doc: Document): Int? = doc.select(K.SEL_PAGINATION)
        .asSequence()
        .flatMap { it.select(K.SEL_PAGINATION_LINK).asSequence() }
        .mapNotNull { K.RE_PAGE.find(it.attr(K.ATTR_HREF))?.groupValues?.get(1)?.toIntOrNull() }
        .maxOrNull()

    fun parse(doc: Document, fallbackType: TvType): List<CatalogItem> {
        val cards = doc.select(K.SEL_CARD)
        Log.i(K.TAG, "parse cards=${cards.size} title=${doc.title().take(K.TITLE_LOG)}")
        return cards.mapNotNull { card ->
            val link = card.select(K.SEL_CARD_TITLE_LINK).firstOrNull()
                ?: card.select(K.SEL_CARD_LINK).firstOrNull()
                ?: return@mapNotNull null
            val href = link.attr(K.ATTR_HREF)
            if (href.isBlank() || !href.contains(K.PATH_TITLE)) return@mapNotNull null
            val image = card.select(K.SEL_CARD_IMAGE).firstOrNull()
            val meta = Dom.text(card, K.SEL_CARD_META, K.SEL_CARD_MUTED)
            val name = Dom.text(card, K.SEL_CARD_TITLE, K.SEL_CARD_LEGACY_TITLE)
                ?: Dom.attr(image, K.ATTR_ALT)
                ?: return@mapNotNull null
            CatalogItem(
                name = name,
                url = Dom.absolute(href),
                posterUrl = image?.attr(K.ATTR_SRC_ABS),
                year = Dom.year(meta, name),
                type = Dom.type(listOf(name, meta.orEmpty(), href), fallbackType),
            )
        }.distinctBy { it.url }
    }

    suspend fun page(path: String, sort: String, page: Int, fallbackType: TvType): List<CatalogItem> =
        parse(Net.get(url(path, sort, page), K.CACHE_CATALOG), fallbackType)

    suspend fun search(query: String, page: Int, fallbackType: TvType): List<CatalogItem> {
        if (query.trim().length < K.MIN_QUERY_LENGTH) return emptyList()
        return parse(Net.get(searchUrl(query, page), K.CACHE_SEARCH), fallbackType)
    }
}

object Detail {

    fun title(doc: Document): String? {
        val raw = Dom.text(doc, K.SEL_DETAIL_HEADER) ?: doc.title()
        if (raw.isBlank()) return null
        return raw.replace(K.TITLE_SUFFIX, K.EMPTY)
            .replace(K.RE_WHITESPACE, K.SEPARATOR_SPACE)
            .trim()
            .ifBlank { null }
    }

    fun seasons(doc: Document): List<SeasonRef> = doc.select(K.SEL_SEASON)
        .mapNotNull { anchor ->
            val href = anchor.attr(K.ATTR_HREF)
            if (href.isBlank()) null else SeasonRef(
                url = href.removePrefix(K.BASE_URL),
                season = Dom.season(anchor.text(), href),
            )
        }
        .distinctBy { it.url }
        .sortedBy { it.season }

    fun type(doc: Document): TvType = Dom.type(
        doc.select(K.SEL_CATEGORY).map { it.text() } +
            listOfNotNull(Dom.text(doc, K.SEL_DETAIL_HEADER)),
        if (seasons(doc).isEmpty()) TvType.Movie else TvType.TvSeries,
    )

    fun poster(doc: Document): String? =
        Dom.attr(doc.selectFirst(K.SEL_DETAIL_POSTER), K.ATTR_SRC)
            ?: Dom.attr(doc.selectFirst(K.SEL_OG_IMAGE), K.ATTR_CONTENT)

    fun plot(doc: Document): String? = doc.select(K.SEL_DETAIL_PLOT)
        .firstOrNull { it.text().isNotBlank() && !it.text().contains(K.LABEL_RELEASE_DATE) }
        ?.text()
        ?.trim()

    fun tags(doc: Document): List<String> = doc.select(K.SEL_GENRE)
        .map { it.text().trim() }
        .filter { it.isNotBlank() }
        .distinct()

    fun year(doc: Document, name: String): Int? = Dom.year(
        doc.select(K.SEL_DETAIL_PLOT)
            .firstOrNull { it.text().contains(K.LABEL_RELEASE_DATE) }
            ?.text(),
        name,
        doc.title(),
    )

    fun runtime(doc: Document): Int? = doc.select(K.SEL_DETAIL_EXTRA)
        .firstOrNull { it.text().contains(K.LABEL_RUNTIME) }
        ?.text()
        ?.let { K.RE_DIGITS.findAll(it).lastOrNull()?.value }
        ?.toIntOrNull()

    fun actors(doc: Document): List<ActorData> = doc.select(K.SEL_DETAIL_CAST)
        .mapNotNull { row ->
            val values = row.select(K.SEL_CAST_FIELD)
            val actor = values.firstOrNull()?.text()?.trim()
            if (actor.isNullOrBlank()) null
            else ActorData(Actor(actor), roleString = values.getOrNull(K.CAST_ROLE_INDEX)?.text()?.trim())
        }
        .distinctBy { it.actor.name }

    fun files(doc: Document, season: Int): List<MediaFile> = doc.select(K.SEL_DOWNLOAD)
        .mapNotNull { anchor ->
            val href = anchor.attr(K.ATTR_HREF)
            if (href.isBlank()) null else MediaFile(
                url = Dom.absolute(href),
                name = Dom.text(anchor).orEmpty(),
                season = season,
            )
        }
        .distinctBy { it.url }

    suspend fun versions(seasons: List<SeasonRef>): List<Pair<Pair<Int, Int>, List<MediaFile>>> =
        Net.parallel(seasons, K.PARALLEL_SEASONS) { collect(it) }
            .flatten()
            .groupBy { it.season to Dom.episode(it.name, it.url) }
            .toSortedMap(compareBy({ it.first }, { it.second }))
            .map { it.key to it.value }

    suspend fun collect(season: SeasonRef): List<MediaFile> {
        val first = Net.get(Catalog.url(season.url, K.SORT_UPDATE, K.FIRST_PAGE), K.CACHE_EPISODES)
        val found = LinkedHashMap<String, MediaFile>()
        files(first, season.season).forEach { found.putIfAbsent(it.url, it) }
        val total = (Catalog.totalPages(first) ?: K.FIRST_PAGE).coerceAtMost(K.MAX_EPISODE_PAGES)
        Net.parallel((K.FIRST_PAGE + 1..total).toList(), K.PARALLEL_SEVER_PAGES) { page ->
            files(Net.get(Catalog.url(season.url, K.SORT_UPDATE, page), K.CACHE_EPISODES), season.season)
        }.flatten().forEach { found.putIfAbsent(it.url, it) }
        return found.values.toList()
    }

    fun name(index: Int, versions: Int): String {
        val name = "$K.PREFIX_EPISODE$index"
        return if (versions > 1) "$name$K.LABEL_VERSIONS_OPEN$versions$K.LABEL_VERSIONS_CLOSE" else name
    }
}

object Providers {

    suspend fun servers(episodeUrl: String, version: String): List<StreamRef> {
        val doc = Net.get(episodeUrl, K.CACHE_EPISODES)
        val info = doc.selectFirst(K.SEL_FILE_INFO) ?: return emptyList()
        val id = info.attr(K.ATTR_DATA_ID)
        if (id.isBlank()) return emptyList()
        val names = LinkedHashSet<String>()
        doc.select(K.SEL_DATA_NAME).forEach { names.add(it.attr(K.ATTR_DATA_NAME)) }
        doc.select(K.SEL_VERSION_OPTION).forEach { names.add(it.attr(K.ATTR_VALUE)) }
        doc.select(K.SEL_VERSION_INPUT).forEach { names.add(it.attr(K.ATTR_VALUE)) }
        return names
            .filter { it.isNotBlank() }
            .take(K.MAX_SERVERS)
            .map { StreamRef(id, it, episodeUrl, version) }
    }

    suspend fun resolve(servers: List<StreamRef>): List<StreamBundle> = Net
        .parallel(servers, K.PARALLEL_SERVERS) { server ->
            val doc = Net.post(
                url = K.BASE_URL + K.ENDPOINT_STREAM,
                data = mapOf(K.PARAM_SOURCE to server.dataName, K.PARAM_FILE to server.id),
                referer = server.url,
            )
            StreamBundle(sources(doc, server.label), tracks(doc))
        }
        .filter { it.sources.isNotEmpty() }

    fun sources(doc: Document, server: String): List<StreamSource> = doc.select(K.SEL_SOURCE)
        .mapNotNull { node ->
            val src = Dom.attr(node, K.ATTR_SRC) ?: return@mapNotNull null
            val label = Dom.attr(node, K.ATTR_LABEL).orEmpty()
            val url = Dom.absolute(src)
            val quality = Dom.quality(label, url)
            val host = Dom.host(url)
            StreamSource(
                url = url,
                name = listOf(server, quality, label)
                    .filterNotNull()
                    .filter { it.isNotBlank() }
                    .joinToString(K.SEPARATOR_DASH)
                    .ifBlank { host },
                host = host,
                quality = quality,
                type = mediaType(url, Dom.attr(node, K.ATTR_TYPE)),
            )
        }
        .distinctBy { it.url }

    fun tracks(doc: Document): List<StreamTrack> = doc.select(K.SEL_TRACK)
        .mapNotNull { node ->
            val src = Dom.attr(node, K.ATTR_SRC) ?: return@mapNotNull null
            StreamTrack(
                url = Dom.absolute(src),
                lang = Dom.attr(node, K.ATTR_SRCLANG) ?: K.SUB_LANG_FALLBACK,
                label = Dom.attr(node, K.ATTR_LABEL) ?: K.SUB_LANG_FALLBACK,
            )
        }
        .distinctBy { it.url }

    fun mediaType(url: String, declared: String?): ExtractorLinkType? {
        val path = url.substringBefore(K.SEPARATOR_QUERY).lowercase()
        return when {
            declared?.contains(K.TOKEN_MPEG_URL, true) == true -> ExtractorLinkType.M3U8
            declared?.contains(K.TOKEN_DASH, true) == true -> ExtractorLinkType.DASH
            path.endsWith(K.SUFFIX_M3U8) -> ExtractorLinkType.M3U8
            path.endsWith(K.SUFFIX_MPD) -> ExtractorLinkType.DASH
            path.endsWith(K.SUFFIX_TORRENT) -> ExtractorLinkType.TORRENT
            path.startsWith(K.SUFFIX_MAGNET) -> ExtractorLinkType.MAGNET
            path.endsWith(K.SUFFIX_MP4) ||
                path.endsWith(K.SUFFIX_MKV) ||
                path.endsWith(K.SUFFIX_WEBM) ||
                path.endsWith(K.SUFFIX_TS) -> ExtractorLinkType.VIDEO

            else -> null
        }
    }
}

class kimoitv : MainAPI() {

    override var name = K.NAME
    override var mainUrl = K.BASE_URL
    override var lang = K.LANG
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var sequentialMainPage = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Cartoon)
    override val getMainPageTimeoutMs = K.TIMEOUT_MAIN_PAGE
    override val searchTimeoutMs = K.TIMEOUT_SEARCH
    override val quickSearchTimeoutMs = K.TIMEOUT_QUICK_SEARCH
    override val loadTimeoutMs = K.TIMEOUT_LOAD
    override val loadLinksTimeoutMs = K.TIMEOUT_LOAD_LINKS

    override val mainPage = mainPageOf(
        *K.CATALOGS.map { Dom.absolute(it.path) to it.label }.toTypedArray()
    )

    private fun catalog(path: String): K.Catalog = K.CATALOGS
        .firstOrNull { Dom.absolute(it.path) == path }
        ?: K.Catalog(K.NAME, path.removePrefix(K.BASE_URL), TvType.Movie, K.SORT_UPDATE)

    private fun responses(items: List<CatalogItem>): List<SearchResponse> = items.map { item ->
        when (item.type) {
            TvType.Anime -> newAnimeSearchResponse(item.name, item.url, TvType.Anime) {
                posterUrl = item.posterUrl
                year = item.year
            }

            TvType.Movie -> newMovieSearchResponse(item.name, item.url, TvType.Movie) {
                posterUrl = item.posterUrl
                year = item.year
            }

            else -> newTvSeriesSearchResponse(item.name, item.url, item.type) {
                posterUrl = item.posterUrl
                year = item.year
            }
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse = guard(newHomePageResponse(emptyList(), false)) {
        val entry = catalog(request.data)
        val items = guard(emptyList()) {
            Catalog.page(entry.path, entry.sort, page.coerceAtLeast(K.FIRST_PAGE), entry.type)
        }
        newHomePageResponse(HomePageList(request.name, responses(items)), hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String, page: Int): SearchResponseList =
        guard(newSearchResponseList(emptyList(), false)) {
            val items = guard(emptyList()) {
                Catalog.search(query, page.coerceAtLeast(K.FIRST_PAGE), TvType.Movie)
            }
            newSearchResponseList(responses(items), hasNext = items.isNotEmpty())
        }

    override suspend fun search(query: String): List<SearchResponse>? = search(query, K.FIRST_PAGE).items

    override suspend fun quickSearch(query: String): List<SearchResponse>? =
        search(query, K.FIRST_PAGE).items.take(K.QUICK_SEARCH_LIMIT)

    override suspend fun load(url: String): LoadResponse? = guard(null) {
        val doc = Net.get(url, K.CACHE_DETAIL)
        val name = Detail.title(doc) ?: return@guard null
        val poster = Detail.poster(doc)
        val seasons = Detail.seasons(doc)
        if (seasons.isEmpty()) {
            val files = Detail.files(doc, K.SEASON_FALLBACK)
            if (files.isEmpty()) return@guard null
            newMovieLoadResponse(name, url, TvType.Movie, MediaPayload(files).encode()) {
                posterUrl = poster
                plot = Detail.plot(doc)
                year = Detail.year(doc, name)
                duration = Detail.runtime(doc)
                tags = Detail.tags(doc)
                actors = Detail.actors(doc)
            }
        } else {
            val episodes = Detail.versions(seasons).map { (key, files) ->
                newEpisode(MediaPayload(files).encode()) {
                    this.name = Detail.name(key.second, files.size)
                    season = key.first
                    episode = key.second
                }
            }
            if (episodes.isEmpty()) return@guard null
            newTvSeriesLoadResponse(name, url, Detail.type(doc), episodes) {
                posterUrl = poster
                plot = Detail.plot(doc)
                year = Detail.year(doc, name)
                duration = Detail.runtime(doc)
                tags = Detail.tags(doc)
                actors = Detail.actors(doc)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean = guard(false) {
        val files = guard(emptyList()) { MediaPayload.decode(data).files }
            .ifEmpty { listOf(MediaFile(data, K.EMPTY, K.SEASON_FALLBACK)) }
        val servers = Net.parallel(files, K.PARALLEL_SERVERS) { file ->
            Providers.servers(file.url, file.name).ifEmpty {
                listOf(StreamRef(K.SERVER_FALLBACK.toString(), K.SERVER_FALLBACK.toString(), file.url, file.name))
            }
        }.flatten().distinctBy { Triple(it.url, it.dataName, it.id) }
        val bundles = Providers.resolve(servers)

        bundles.flatMap { it.tracks }.distinctBy { it.url }.forEach { track ->
            subtitleCallback(newSubtitleFile(track.lang, track.url) { headers = Net.media() })
        }

        var emitted = false
        bundles.flatMap { it.sources }.distinctBy { it.url }.forEach { source ->
            if (source.type == null) {
                if (loadExtractor(source.url, K.REFERER_ROOT, subtitleCallback, callback)) emitted = true
            } else {
                callback(
                    newExtractorLink(
                        source = source.host,
                        name = source.name,
                        url = source.url,
                        type = source.type,
                    ) {
                        referer = K.REFERER_ROOT
                        headers = Net.media()
                        quality = getQualityFromName(source.quality)
                    }
                )
                emitted = true
            }
        }
        emitted
    }
}