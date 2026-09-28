package com.finddrama

import android.util.Log
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newAudioFile
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class FindDramaProvider : MainAPI() {

    override var mainUrl = Gl.DEFAULT_SITE
    override var name = Gl.NAME
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override var sequentialMainPage = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.AsianDrama)
    override val getMainPageTimeoutMs = 120_000L
    override val searchTimeoutMs = 90_000L
    override val quickSearchTimeoutMs = 60_000L
    override val loadTimeoutMs = 45_000L
    override val loadLinksTimeoutMs = 45_000L

    private val notPublic = ConcurrentHashMap<Int, Boolean>()

    private val seen = ConcurrentHashMap<String, MutableSet<String>>()

    override val mainPage: List<MainPageData>
        get() {
            val providers = FindDramaRegistry.cached()
            if (providers.isEmpty()) return emptyList()
            return buildList {
                add(MainPageData(name = Gl.RAIL_TRENDING_NAME, data = Gl.RAIL_TRENDING))
                add(MainPageData(name = Gl.RAIL_LATEST_NAME, data = Gl.RAIL_LATEST))
                providers.forEach { add(MainPageData(name = it.label, data = Gl.RAIL_PUBLIC + it.id)) }
            }
        }

    suspend fun warmUp() {
        if (FindDramaRegistry.cached().isEmpty()) FindDramaRegistry.providers()
    }

    fun syncBase() {
        mainUrl = Gl.site()
    }

    fun reload() {
        syncBase()
        notPublic.clear()
        seen.clear()
        FindDramaRegistry.reset()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val providers = FindDramaRegistry.providers()
        val sourceId = request.data.removePrefix(Gl.RAIL_PUBLIC).toIntOrNull()
        if (providers.isEmpty() && sourceId == null) return emptyRail(request)
        if (page <= 0) return emptyRail(request)
        return when {
            request.data == Gl.RAIL_TRENDING -> trendingRail(request, page)
            request.data == Gl.RAIL_LATEST -> latestRail(request, page)
            sourceId != null -> providerRail(request, sourceId, page)
            else -> emptyRail(request)
        }
    }

    private fun emptyRail(request: MainPageRequest): HomePageResponse =
        newHomePageResponse(listOf(HomePageList(request.name, emptyList())), hasNext = false)

    private fun rail(
        request: MainPageRequest,
        items: List<DramaDto>,
        hasNext: Boolean,
    ): HomePageResponse = newHomePageResponse(
        listOf(HomePageList(request.name, items.map { it.toCard() })),
        hasNext = hasNext,
    )

    private suspend fun trendingRail(request: MainPageRequest, page: Int): HomePageResponse {
        if (page > 1) return emptyRail(request)
        FindDramaStore.loadRail(Gl.RAIL_TRENDING, Gl.RAIL_TTL_MINUTES)
            ?.takeIf { it.items.isNotEmpty() }
            ?.let { return rail(request, it.items, hasNext = false) }
        val fresh = FindDramaApi.once(Gl.RAIL_TRENDING) {
            FindDramaApi.fetchPage(FindDramaApi.trending())
        } ?: return emptyRail(request)
        if (fresh.items.isEmpty()) return emptyRail(request)
        FindDramaStore.saveRail(
            Gl.RAIL_TRENDING,
            CachedRail(total = fresh.items.size, items = fresh.items),
        )
        return rail(request, fresh.items, hasNext = false)
    }

    private suspend fun latestRail(request: MainPageRequest, page: Int): HomePageResponse {
        val key = "${Gl.RAIL_LATEST}|$page"
        FindDramaStore.loadRail(key, Gl.RAIL_TTL_MINUTES)
            ?.takeIf { it.items.isNotEmpty() }
            ?.let {
                return rail(request, it.items, hasMore(it.total, page, Gl.PAGE_SIZE_PUBLIC))
            }
        val fresh = FindDramaApi.fetchPage(FindDramaApi.seriesLatest(page)) ?: return emptyRail(request)
        if (fresh.items.isNotEmpty()) {
            FindDramaStore.saveRail(key, CachedRail(total = fresh.total, items = fresh.items))
        }
        return rail(request, fresh.items, hasMore(fresh.total, page, Gl.PAGE_SIZE_PUBLIC))
    }

    private fun browsePublic(sourceId: Int): Boolean {
        if (notPublic[sourceId] == true) return false
        val public = FindDramaRegistry.publicIds()
        return public.isEmpty() || public.contains(sourceId)
    }

    private suspend fun providerRail(
        request: MainPageRequest,
        sourceId: Int,
        page: Int,
    ): HomePageResponse {
        val key = "${Gl.RAIL_PUBLIC}$sourceId|$page"
        FindDramaStore.loadRail(key, Gl.RAIL_TTL_MINUTES)
            ?.takeIf { it.items.isNotEmpty() }
            ?.let { return rail(request, it.items, hasMore(it.total, page, Gl.PAGE_SIZE_PUBLIC)) }

        if (!FindDramaRegistry.isKnown(sourceId)) return emptyRail(request)

        if (browsePublic(sourceId)) {
            val fetched = try {
                FindDramaApi.fetchPage(FindDramaApi.seriesPublic(sourceId, page), sourceScoped = true)
            } catch (e: FindDramaApi.ForbiddenSource) {
                notPublic[sourceId] = true
                Log.i(TAG, "source $sourceId refused by the browse endpoint, using the random endpoint")
                return randomRail(request, sourceId, page, key)
            } ?: return emptyRail(request)
            return publish(request, key, page, RailPage(fetched.items, fetched.total), Gl.PAGE_SIZE_PUBLIC)
        }

        return randomRail(request, sourceId, page, key)
    }

    private suspend fun randomRail(
        request: MainPageRequest,
        sourceId: Int,
        page: Int,
        key: String,
    ): HomePageResponse {
        val offset = (page - 1) * Gl.PAGE_SIZE_RANDOM
        val fetched = try {
            FindDramaApi.fetchPage(FindDramaApi.randomSource(sourceId, offset), sourceScoped = true)
        } catch (e: FindDramaApi.ForbiddenSource) {
            Log.w(TAG, "source $sourceId refused by both endpoints")
            return emptyRail(request)
        } ?: return emptyRail(request)

        val total = fetched.total
        if (total <= 0 || offset >= total) {
            return publish(request, key, page, RailPage(emptyList(), total), Gl.PAGE_SIZE_RANDOM)
        }
        return publish(
            request,
            key,
            page,
            RailPage(fetched.items.filter { it.source == sourceId }, total),
            Gl.PAGE_SIZE_RANDOM,
        )
    }

    private fun publish(
        request: MainPageRequest,
        key: String,
        page: Int,
        result: RailPage,
        pageSize: Int,
    ): HomePageResponse {
        if (result.items.isNotEmpty()) {
            FindDramaStore.saveRail(key, CachedRail(total = result.total, items = result.items))
        }
        return rail(request, result.items, hasMore(result.total, page, pageSize))
    }

    private class RailPage(
        val items: List<DramaDto>,
        val total: Int,
    )

    private fun hasMore(total: Int, page: Int, pageSize: Int): Boolean =
        total > 0 && page * pageSize < total

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val q = query.trim()
        if (q.isEmpty() || page <= 0) return newSearchResponseList(emptyList(), false)
        val scope = "search|${q.lowercase()}"
        val key = "$scope|$page"
        FindDramaStore.loadRail(key, Gl.RAIL_TTL_MINUTES)
            ?.takeIf { it.items.isNotEmpty() }
            ?.let {
                val cards = dedupe(it.items, scope)
                return newSearchResponseList(cards, hasMore(it.total, page, Gl.PAGE_SIZE_PUBLIC))
            }
        val fetched = FindDramaApi.once(key) {
            FindDramaApi.fetchPage(FindDramaApi.seriesSearch(q, page))
        } ?: return newSearchResponseList(emptyList(), false)
        if (fetched.items.isNotEmpty()) {
            FindDramaStore.saveRail(key, CachedRail(total = fetched.total, items = fetched.items))
        }
        val cards = dedupe(fetched.items, scope)
        return newSearchResponseList(cards, hasMore(fetched.total, page, Gl.PAGE_SIZE_PUBLIC))
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val key = "quick|${q.lowercase()}"
        val cached = FindDramaStore.loadRail(key, Gl.RAIL_TTL_MINUTES)
        if (cached != null && cached.items.isNotEmpty()) return dedupe(cached.items, key)
        val fetched = FindDramaApi.fetchPage(FindDramaApi.seriesSearch(q, 1)) ?: return emptyList()
        if (fetched.items.isNotEmpty()) {
            FindDramaStore.saveRail(key, CachedRail(total = fetched.total, items = fetched.items))
        }
        return dedupe(fetched.items, key)
    }

    private fun dedupe(items: List<DramaDto>, scope: String): List<SearchResponse> {
        if (seen.size > Gl.MAX_SCOPES) seen.clear()
        val bucket = seen.computeIfAbsent(scope) { Collections.synchronizedSet(LinkedHashSet()) }
        return items.mapNotNull { item ->
            val id = item.slug?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            synchronized(bucket) {
                if (!bucket.add(id)) return@mapNotNull null
                if (bucket.size > Gl.MAX_SEEN) {
                    val keep = bucket.toList().takeLast(Gl.MAX_SEEN / 2)
                    bucket.clear()
                    bucket.addAll(keep)
                }
            }
            item.toCard()
        }
    }

    private fun DramaDto.toCard(): SearchResponse {
        val path = slug.orEmpty()
        return newTvSeriesSearchResponse(
            title.orEmpty().ifEmpty { path },
            path,
            TvType.TvSeries,
            fix = false,
        ) {
            this.id = (dramaId ?: 0L).toInt()
            this.posterUrl = cover?.trim()?.takeIf { it.isNotEmpty() }?.let { FindDramaApi.absolute(it) }
            this.episodes = latestEpisodeLabel?.filter { it.isDigit() }?.toIntOrNull()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val slug = toSlug(url) ?: return null
        val detail = FindDramaApi.once("detail|$slug") { FindDramaApi.fetchDetail(slug) } ?: return null
        val dramaId = detail.dramaId?.toString()?.takeIf { it.isNotEmpty() }
            ?: slug.substringBefore('/').takeIf { it.isNotEmpty() }
            ?: return null
        val episodes = episodesFor(dramaId)
        if (episodes.isEmpty()) return null

        val title = detail.title?.takeIf { it.isNotBlank() } ?: slug
        return newTvSeriesLoadResponse(title, slug, TvType.TvSeries, episodes) {
            this.posterUrl = detail.cover?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { FindDramaApi.absolute(it) }
            this.plot = detail.synopsis?.takeIf { it.isNotBlank() }
            this.tags = buildTags(detail, episodes.size)
        }
    }

    private fun toSlug(url: String): String? {
        var value = url.trim().substringBefore('?').substringBefore('#')
        if (value.startsWith(mainUrl)) value = value.removePrefix(mainUrl)
        value = value.trim('/')
        if (value.isEmpty() || value.contains("://")) return null
        return value
    }

    private fun buildTags(detail: DramaDto, episodeCount: Int): List<String> {
        val tags = LinkedHashSet<String>()
        detail.tags.filter { it.isNotBlank() }.forEach { tags.add(it) }
        tags.add("$episodeCount Episodes")
        tags.add("Short Drama")
        return tags.toList()
    }

    private suspend fun episodesFor(dramaId: String): List<Episode> {
        FindDramaStore.loadEpisodes(dramaId, Gl.EPISODE_TTL_MINUTES)
            ?.takeIf { it.items.isNotEmpty() }
            ?.let { return it.items.toEpisodes() }

        val fetched = FindDramaApi.once("episodes|$dramaId") {
            FindDramaApi.fetchEpisodes(dramaId)
        } ?: return emptyList()
        if (fetched.items.isEmpty()) return emptyList()
        FindDramaStore.saveEpisodes(dramaId, CachedEpisodes(items = fetched.items))
        return fetched.items.toEpisodes()
    }

    private fun List<EpisodeDto>.toEpisodes(): List<Episode> = filter {
        it.ep > 0 && it.url.isNotEmpty()
    }.sortedBy { it.ep }.map { item ->
        newEpisode(item.url + "|" + item.ep) {
            this.name = "Episode ${item.ep}"
            this.episode = item.ep
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val trimmed = data.trim()
        val pipe = trimmed.lastIndexOf('|')
        if (pipe <= 0) return false
        val episode = trimmed.substring(pipe + 1).toIntOrNull() ?: return false
        val rawUrl = trimmed.substring(0, pipe)
        if (rawUrl.isEmpty()) return false

        val direct = FindDramaApi.absolute(rawUrl)
        val body = FindDramaApi.fetchText(direct)
        val playlist = FindDramaPlaylist.parse(body, direct)

        var emitted = 0
        if (playlist.isMaster) {
            playlist.variants
                .sortedByDescending { if (it.height > 0) it.height else it.bandwidth }
                .take(Gl.MAX_STREAMS)
                .forEach { variant ->
                    callback(
                        link(variant.url, episode, variant.height, variant.bandwidth, ExtractorLinkType.M3U8),
                    )
                    emitted++
                }
        }

        if (emitted == 0) {
            callback(link(direct, episode, playlist.height, 0, inferType(direct)))
            emitted = 1
        }

        if (!playlist.readable) {
            val relayed = relay(direct)
            if (relayed != null) {
                val best = relayed.variants.maxByOrNull { it.height }
                if (best != null) {
                    callback(link(best.url, episode, best.height, 0, ExtractorLinkType.M3U8))
                } else {
                    callback(link(FindDramaApi.proxy(direct), episode, 0, 0, ExtractorLinkType.M3U8))
                }
                emitted++
            }
        }

        playlist.tracks.forEach { track ->
            if (track.kind.equals("SUBTITLES", true)) {
                subtitleCallback(
                    newSubtitleFile(track.language.ifEmpty { "en" }, track.url) {
                        this.headers = mediaHeaders()
                    },
                )
            } else {
                callback(
                    newExtractorLink(name, "Audio - ${track.name}", track.url, ExtractorLinkType.M3U8) {
                        this.audioTracks = listOf(
                            newAudioFile(track.url) { this.headers = mediaHeaders() },
                        )
                    },
                )
            }
        }

        return emitted > 0
    }

    private suspend fun relay(direct: String): Playlist? {
        val url = FindDramaApi.proxy(direct)
        val body = FindDramaApi.fetchText(url) ?: return null
        return FindDramaPlaylist.parse(body, url).takeIf { it.readable }
    }

    private suspend fun link(
        url: String,
        episode: Int,
        height: Int,
        bandwidth: Int,
        type: ExtractorLinkType,
    ): ExtractorLink = newExtractorLink(name, "Episode $episode [${label(height, bandwidth)}]", url, type) {
        this.referer = ""
        this.headers = mediaHeaders()
        this.quality = if (height > 0) height else Qualities.Unknown.value
    }

    private fun label(height: Int, bandwidth: Int): String = when {
        height > 0 -> "${height}p"
        bandwidth > 0 -> "${bandwidth / 1000}kbps"
        else -> "Auto"
    }

    private fun inferType(url: String): ExtractorLinkType = when {
        url.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
        url.contains(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
        else -> ExtractorLinkType.VIDEO
    }

    private companion object {
        const val TAG = "FindDrama"
    }
}
