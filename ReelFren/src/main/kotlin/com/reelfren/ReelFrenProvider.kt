package com.reelfren

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.utils.newExtractorLink

class ReelFrenProvider(val slug: String) : MainAPI() {
    override var name: String = ReelFrenNames.display(slug)
    override var mainUrl: String = REEL_DEFAULT_WEB + "/" + slug
    override var lang: String = "en"
    override val hasMainPage: Boolean = true
    override val supportedTypes: Set<TvType> =
        setOf(TvType.TvSeries, TvType.Movie, TvType.AsianDrama)

    private val pageCache = LinkedHashMap<String, List<SearchResponse>>()
    @Volatile private var lastProbeAt = 0L

    companion object {
        const val PAGE_SIZE = 30
        const val PROBE_BACKOFF_MS = 5L * 60 * 1000
    }

    override val mainPage
        get() = mainPageOf(*rows())

    fun categories(): List<Category> =
        listOf(Category(ReelFrenProbe.HOME, "Home")) + ReelFrenStore.categoriesFor(slug)

    private fun rows(): Array<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (category in categories()) {
            out.add(slug + "|" + category.key to name + " • " + category.label)
        }
        return out.toTypedArray()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val (requestSlug, category) = ReelFrenUrl.parseMain(request.data)
        val target = if (requestSlug.isEmpty()) slug else requestSlug
        if (page > 1) {
            val cached = pageCache[request.data].orEmpty()
            val cards = cached.drop((page - 1) * PAGE_SIZE).take(PAGE_SIZE)
            return newHomePageResponse(
                listOf(HomePageList(request.name, cards)),
                hasNext = cached.size > page * PAGE_SIZE
            )
        }
        if (category.isEmpty()) {
            ReelFrenScope.launch {
                if (probeOnce()) ReelFrenScope.refreshHome()
            }
        }
        val items = ReelFrenClient.home(target, category)
        val cards = items.mapNotNull { toCard(target, it) }
        pageCache[request.data] = cards
        if (pageCache.size > 60) pageCache.remove(pageCache.keys.first())
        return newHomePageResponse(
            listOf(HomePageList(request.name, cards.take(PAGE_SIZE))),
            hasNext = cards.size > PAGE_SIZE
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val term = query.trim()
        if (term.isEmpty()) return emptyList()
        return ReelFrenClient.search(slug, term).mapNotNull { toCard(slug, it) }.take(60)
    }

    override suspend fun load(url: String): LoadResponse? {
        val (target, id, _) = ReelFrenUrl.parse(url)
        if (target.isEmpty() || id.isEmpty()) return null
        val info = ReelFrenClient.detail(target, id) ?: return null
        if (info.videos.isEmpty()) return null
        val tags = listOf(name)
        return if (info.videos.size > 1) {
            val episodes = info.videos.map { video ->
                newEpisode(ReelFrenUrl.episode(target, info.id, video.episode)) {
                    this.name = "Episode " + video.episode
                    this.posterUrl = info.cover.ifEmpty { null }
                }
            }
            newTvSeriesLoadResponse(info.title, url, TvType.AsianDrama, episodes) {
                this.posterUrl = info.cover.ifEmpty { null }
                this.plot = info.intro.ifEmpty { null }
                this.tags = tags
            }
        } else {
            val episode = info.videos.first().episode
            val data = ReelFrenUrl.episode(target, info.id, episode)
            newMovieLoadResponse(info.title, url, TvType.Movie, data) {
                this.posterUrl = info.cover.ifEmpty { null }
                this.plot = info.intro.ifEmpty { null }
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val (target, id, episode) = ReelFrenUrl.parse(data)
        if (target.isEmpty() || id.isEmpty()) return false
        val play = ReelFrenClient.playback(target, id, episode) ?: return false
        if (play.locked) return false
        for (subtitle in play.subtitles) {
            val url = ReelFrenClient.absUrl(subtitle.url)
            if (url.isNotEmpty()) {
                val label = subtitle.lang.ifEmpty { subtitle.label }.ifEmpty { "und" }
                subtitleCallback(newSubtitleFile(label, url))
            }
        }
        val referer = REEL_DEFAULT_WEB + "/" + target + "|" + id + "|" + episode
        val headers = mapOf(
            "User-Agent" to ReelFrenClient.UA,
            "Referer" to referer,
            "Origin" to REEL_DEFAULT_WEB,
            "Accept" to "*/*"
        )
        var emitted = false
        for (quality in play.qualities.distinctBy { it.url }) {
            val absolute = ReelFrenClient.absUrl(quality.url)
            if (absolute.isEmpty()) continue
            val label = quality.label.ifEmpty { "Auto" }
            callback(
                newExtractorLink(
                    "ReelFren",
                    "[S" + play.server + "] " + label,
                    absolute,
                    if (quality.format.equals("hls", true) || absolute.contains(".m3u8", true)) {
                        ExtractorLinkType.M3U8
                    } else {
                        ExtractorLinkType.VIDEO
                    }
                ) {
                    this.referer = referer
                    this.quality = ReelFrenQuality.of(label)
                    this.headers = headers
                }
            )
            emitted = true
        }
        return emitted
    }

    suspend fun probeOnce(): Boolean {
        if (ReelFrenStore.probeFresh(slug)) return true
        val now = System.currentTimeMillis()
        if (now - lastProbeAt < PROBE_BACKOFF_MS) return false
        lastProbeAt = now
        val base = ReelFrenClient.home(slug, ReelFrenProbe.HOME)
        if (base.isEmpty()) {
            ReelFrenStore.saveDiagnostics(slug, 0, 0, "no default feed")
            return false
        }
        val baseIds = base.map { it.id }.toSet()
        val samples = LinkedHashMap<String, List<String>>()
        val keys = ReelFrenProbe.candidates.map { it.key }
        var probed = 0
        for (batch in keys.chunked(ReelFrenProbe.BATCH)) {
            val results = batch.amap { ReelFrenClient.home(slug, it).map { item -> item.id } }
            probed += batch.size
            for (index in batch.indices) {
                if (ReelFrenProbe.isDistinct(results[index], baseIds)) samples[batch[index]] = results[index]
            }
            val selected = ReelFrenProbe.select(samples)
            ReelFrenStore.saveCategories(slug, selected.map { it.key })
            if (selected.size >= ReelFrenProbe.CAP) break
        }
        val selected = ReelFrenProbe.select(samples)
        ReelFrenStore.markProbed(slug)
        ReelFrenStore.saveDiagnostics(slug, probed, selected.size, selected.joinToString { it.key })
        return true
    }

    private fun toCard(target: String, item: HomeItem): SearchResponse? {
        if (item.id.isEmpty() || item.title.isEmpty()) return null
        val data = ReelFrenUrl.page(target, item.id)
        val poster = item.cover.ifEmpty { null }
        return if (item.episodes > 1) {
            newTvSeriesSearchResponse(item.title, data, TvType.AsianDrama) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(item.title, data, TvType.Movie) { this.posterUrl = poster }
        }
    }
}

object ReelFrenDiscovery {
    suspend fun refreshProviders(): List<String> {
        val live = ReelFrenClient.providers()
        if (live.isNotEmpty()) ReelFrenStore.saveKnown(ReelFrenStore.knownSlugs() + live)
        return ReelFrenStore.knownSlugs().sorted()
    }
}
