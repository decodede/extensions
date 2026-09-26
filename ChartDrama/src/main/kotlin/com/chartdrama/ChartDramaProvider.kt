package com.chartdrama

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

object ChartDramaScope {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var lastRefresh = 0L

    fun launch(block: suspend CoroutineScope.() -> Unit) = scope.launch(block = block)

    fun refreshHome() {
        val now = System.currentTimeMillis()
        if (now - lastRefresh < 20_000L) return
        lastRefresh = now
        runCatching { com.lagradost.cloudstream3.MainActivity.reloadHomeEvent.invoke(true) }
    }

    fun shutdown() = scope.cancel()
}

class ChartDramaProvider(val source: Int) : MainAPI() {
    override var name: String = ChartDramaNames.label(source)
    override var mainUrl: String = CHARTDRAMA_SITE
    override var lang: String = "en"
    override val hasMainPage: Boolean = true
    override val supportedTypes: Set<TvType> = setOf(TvType.TvSeries, TvType.AsianDrama, TvType.Movie)

    private val pageCache = LinkedHashMap<String, List<SearchResponse>>()
    @Volatile private var tagsLoaded = false

    companion object {
        const val PAGE_SIZE = 30
        const val CACHE_ENTRIES = 60
        const val TAG_ROWS = 8
        const val TAG_BACKOFF_MS = 5L * 60 * 1000
        @Volatile var lastTagProbe = 0L
    }

    private fun rows(): Array<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        out.add("$source|" to "Popular")
        for (tag in ChartDramaStore.tags().take(TAG_ROWS)) {
            out.add("$source|tag:$tag" to name + " • " + tag)
        }
        return out.toTypedArray()
    }

    override val mainPage
        get() = mainPageOf(*rows())

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val raw = request.data.substringBefore("|")
        val requestSource = raw.toIntOrNull() ?: source
        val category = request.data.substringAfter("|", "")
        if (page == 1 && category.isEmpty()) {
            ChartDramaScope.launch {
                if (loadTags()) ChartDramaScope.refreshHome()
            }
        }
        val cached = pageCache[request.data]
        val cards = if (cached != null) {
            cached
        } else {
            val loaded = fetchRow(requestSource, category)
            pageCache[request.data] = loaded
            if (pageCache.size > CACHE_ENTRIES) pageCache.remove(pageCache.keys.first())
            loaded
        }
        val (window, hasNext) = ChartDramaPaging.slice(cards, page)
        return newHomePageResponse(listOf(HomePageList(request.name, window)), hasNext)
    }

    private suspend fun fetchRow(requestSource: Int, category: String): List<SearchResponse> {
        val items = when {
            category.startsWith("tag:") ->
                ChartDramaClient.series(requestSource, 1, PAGE_SIZE, tag = category.removePrefix("tag:"))
            category.isEmpty() -> ChartDramaClient.random(requestSource, PAGE_SIZE)
            else -> emptyList()
        }
        return items.mapNotNull { toCard(it) }
    }

    private suspend fun loadTags(): Boolean {
        if (tagsLoaded || ChartDramaStore.tagsFresh()) return false
        val now = System.currentTimeMillis()
        if (now - lastTagProbe < TAG_BACKOFF_MS) return false
        lastTagProbe = now
        val before = ChartDramaStore.tags()
        val all = ChartDramaClient.tags(60)
        if (all.isEmpty()) return false
        val usable = ArrayList<String>()
        for (tag in all) {
            if (usable.size >= TAG_ROWS) break
            if (ChartDramaClient.total(source, tag = tag) > 0) usable.add(tag)
        }
        if (usable.isEmpty()) {
            tagsLoaded = true
            return false
        }
        ChartDramaStore.saveTags(usable)
        tagsLoaded = true
        return before != usable
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val term = query.trim()
        if (term.isEmpty()) return emptyList()
        return ChartDramaClient.series(source, 1, PAGE_SIZE, query = term).mapNotNull { toCard(it) }
    }

    override suspend fun load(url: String): LoadResponse? {
        val slug = url.substringBefore("|")
        if (slug.isEmpty()) return null
        val info = ChartDramaClient.watch(slug) ?: return null
        val meta = ChartDramaClient.seriesBySlug(slug)
        val episodes = meta?.episodeCount ?: 1
        val poster = info.cover.ifEmpty { null }
        val page = ChartDramaApi.pageUrl(info.slug)
        return if (episodes > 1) {
            newTvSeriesLoadResponse(
                info.title,
                page,
                TvType.AsianDrama,
                listOf(
                    newEpisode(info.slug) {
                        this.name = "Latest (${meta?.episodeLabel.orEmpty().ifEmpty { "EP$episodes" }})"
                        this.posterUrl = poster
                    }
                )
            ) {
                this.posterUrl = poster
                this.plot = info.synopsis.ifEmpty { null }
                this.tags = listOf(name)
            }
        } else {
            newMovieLoadResponse(info.title, page, TvType.Movie, info.slug) {
                this.posterUrl = poster
                this.plot = info.synopsis.ifEmpty { null }
                this.tags = listOf(name)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val slug = data.substringBefore("|")
        if (slug.isEmpty()) return false
        val info = ChartDramaClient.watch(slug) ?: return false
        val url = info.embedUrl
        if (url.isEmpty()) return false
        val page = ChartDramaApi.pageUrl(info.slug)
        callback(
            newExtractorLink(
                "ChartDrama",
                "Latest",
                url,
                if (url.contains(".m3u8", true) || url.contains(".mpd", true)) {
                    ExtractorLinkType.M3U8
                } else {
                    ExtractorLinkType.VIDEO
                }
            ) {
                this.referer = page
                this.quality = ChartDramaParse.qualityOf(url)
                this.headers = mapOf(
                    "User-Agent" to CHARTDRAMA_UA,
                    "Referer" to page,
                    "Origin" to CHARTDRAMA_SITE
                )
            }
        )
        return true
    }

    private fun toCard(item: Series): SearchResponse? {
        if (item.slug.isEmpty() || item.title.isEmpty()) return null
        val data = item.slug
        val poster = item.cover.ifEmpty { null }
        return if (item.episodeCount > 1) {
            newTvSeriesSearchResponse(item.title, data, TvType.AsianDrama) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(item.title, data, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }
}

object ChartDramaPaging {
    const val PAGE_SIZE = 30

    fun <T> slice(items: List<T>, page: Int): Pair<List<T>, Boolean> {
        if (page < 1) return emptyList<T>() to false
        val from = (page - 1) * PAGE_SIZE
        if (from >= items.size) return emptyList<T>() to false
        val window = items.subList(from, minOf(from + PAGE_SIZE, items.size))
        return window to (items.size > from + window.size)
    }
}

object ChartDramaDiscovery {
    private const val PAGE_SIZE = 100

    suspend fun discover(): Set<Int> {
        val found = LinkedHashSet<Int>()
        for (page in listOf(1, 3)) {
            for (item in ChartDramaClient.series(0, page, PAGE_SIZE)) {
                if (item.source > 0) found.add(item.source)
            }
        }
        for (item in ChartDramaClient.random(0, PAGE_SIZE)) {
            if (item.source > 0) found.add(item.source)
        }
        return found
    }

    suspend fun refresh(): Set<Int> {
        if (ChartDramaStore.sourcesFresh()) return ChartDramaStore.sources()
        val found = discover()
        if (found.isNotEmpty()) ChartDramaStore.saveSources(found)
        return ChartDramaStore.sources()
    }

    suspend fun known(): Set<Int> =
        withTimeoutOrNull(20_000L) { refresh() } ?: ChartDramaStore.sources()
}
