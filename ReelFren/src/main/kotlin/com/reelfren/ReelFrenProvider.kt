package com.reelfren

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.newSubtitleFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder

class ReelFrenProvider : MainAPI() {
    override var mainUrl = REEL_DEFAULT_WEB
    override var name = "ReelFren"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val cfKiller by lazy { CloudflareKiller() }

    companion object {
        const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        const val PAGE_SIZE = 30
    }

    private val memFeeds = LinkedHashMap<String, List<String>>()
    private val resolved = HashSet<String>()
    private val pageCache = LinkedHashMap<String, List<SearchResponse>>()
    @Volatile private var catalogReady = false

    private fun apiBase(): String = ReelFrenStore.apiBase()

    override val mainPage
        get() = mainPageOf(*rows())

    private fun defaultCategoryKeys(slug: String): List<String> =
        ReelFrenCatalog.categories(slug).map { it.key }

    private fun rows(): Array<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (slug in visibleSlugs()) {
            val categories = memFeeds[slug] ?: ReelFrenStore.cachedFeeds()[slug]
                ?: defaultCategoryKeys(slug)
            if (slug !in memFeeds) memFeeds[slug] = categories
            for (category in categories) {
                val title = ReelFrenCatalog.displayName(slug) + " • " +
                    ReelFrenCatalog.categoryLabel(slug, category)
                out.add(ReelFrenCodec.encodeMainData(slug, category) to title)
            }
        }
        return out.toTypedArray()
    }

    private fun visibleSlugs(): List<String> {
        val known = ReelFrenStore.knownSlugs()
        val all = (ReelFrenCatalog.providers.map { it.slug } + known).distinct()
        val enabled = ReelFrenStore.enabledSlugs()
        return all.filter { enabled == null || enabled.contains(it) }
    }

    private suspend fun ensureCatalog() {
        if (catalogReady) return
        val items = apiGet(apiBase() + "/api/home")?.let { ReelFrenParse.homeItems(it) } ?: return
        catalogReady = true
        val slugs = items.map { it.provider }.filter { it.isNotEmpty() }.toSet()
        if (slugs.isEmpty()) return
        ReelFrenStore.saveKnown(ReelFrenStore.knownSlugs() + slugs)
        for (slug in slugs) {
            if (slug !in memFeeds) {
                memFeeds[slug] = ReelFrenStore.cachedFeeds()[slug]
                    ?: defaultCategoryKeys(slug)
            }
        }
    }

    private suspend fun resolveFeeds(slug: String, base: List<HomeItem>) {
        if (slug in resolved || ReelFrenStore.feedsFresh() && ReelFrenStore.cachedFeeds().containsKey(slug)) {
            resolved.add(slug)
            return
        }
        val feeds = defaultCategoryKeys(slug).toMutableList()
        if (slug in ReelFrenCatalog.bySlug) {
            memFeeds[slug] = feeds.distinct()
            val merged = ReelFrenStore.cachedFeeds().toMutableMap()
            merged[slug] = memFeeds[slug]!!
            ReelFrenStore.saveFeeds(merged)
            resolved.add(slug)
            return
        }
        val baseIds = base.map { it.id }.toSet()
        val candidates = listOf("ranked", "trending", "top-searched", "rising-fast")
        for (category in candidates) {
            if (category in feeds) continue
            val items = apiGet(homeUrl(slug, category))?.let { ReelFrenParse.homeItems(it) } ?: continue
            if (items.isNotEmpty() && items.map { it.id }.toSet() != baseIds) feeds.add(category)
        }
        memFeeds[slug] = feeds.distinct()
        val merged = ReelFrenStore.cachedFeeds().toMutableMap()
        merged[slug] = memFeeds[slug]!!
        ReelFrenStore.saveFeeds(merged)
        resolved.add(slug)
    }

    private fun homeUrl(slug: String, category: String): String {
        return apiBase() + "/api/home?provider=" + query(slug) + "&category=" + query(category)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureCatalog()
        val (slug, feed) = ReelFrenCodec.decodeMainData(request.data)
        if (slug.isEmpty()) return newHomePageResponse(
            listOf(HomePageList(request.name, emptyList())), hasNext = false
        )
        if (page > 1) {
            val total = pageCache[request.data].orEmpty()
            val cards = total.drop((page - 1) * PAGE_SIZE).take(PAGE_SIZE)
            return newHomePageResponse(
                listOf(HomePageList(request.name, cards)),
                hasNext = total.size > page * PAGE_SIZE
            )
        }
        val items = apiGet(homeUrl(slug, feed))?.let { ReelFrenParse.homeItems(it) }.orEmpty()
        if (feed == defaultCategoryKeys(slug).firstOrNull().orEmpty()) resolveFeeds(slug, items)
        val mapped = items.mapNotNull { toCard(slug, it) }
        pageCache[request.data] = mapped
        if (pageCache.size > 60) pageCache.remove(pageCache.keys.first())
        return newHomePageResponse(
            listOf(HomePageList(request.name, mapped.take(PAGE_SIZE))),
            hasNext = mapped.size > PAGE_SIZE
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        ensureCatalog()
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val e = URLEncoder.encode(q, "UTF-8")
        return visibleSlugs().amap { slug ->
            val body = apiGet(apiBase() + "/api/search?q=" + e + "&provider=" + query(slug)) ?: return@amap emptyList()
            ReelFrenParse.homeItems(body).mapNotNull { toCard(slug, it) }
        }.flatten().distinctBy { it.url }.take(60)
    }

    override suspend fun load(url: String): LoadResponse? {
        val (slug, id) = ReelFrenCodec.decodeLoadData(url)
        if (slug.isEmpty() || id.isEmpty()) return null
        val info = apiGet(apiBase() + "/api/detail?provider=" + query(slug) + "&id=" + query(id))
            ?.let { ReelFrenParse.detail(it) } ?: return null
        val series = info.videos.size > 1 || info.episodes > 1
        val tags = listOf(ReelFrenCatalog.displayName(slug)).filter { it.isNotEmpty() }
        return if (series) {
            val eps = info.videos.map { v ->
                newEpisode(ReelFrenCodec.encodeEpisodeData(slug, info.id, v.episode)) {
                    this.name = "Episode " + v.episode
                    this.posterUrl = info.cover.ifEmpty { null }
                }
            }
            newTvSeriesLoadResponse(info.title, url, TvType.TvSeries, eps) {
                this.posterUrl = info.cover.ifEmpty { null }
                this.plot = info.intro.ifEmpty { null }
                this.tags = tags
            }
        } else {
            val epData = ReelFrenCodec.encodeEpisodeData(slug, info.id, info.videos.firstOrNull()?.episode ?: 1)
            newMovieLoadResponse(info.title, url, TvType.Movie, epData) {
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
        val (slug, id, ep) = ReelFrenCodec.decodeEpisodeData(data)
        if (slug.isEmpty() || id.isEmpty()) return false
        val play = apiGet(apiBase() + "/api/video?provider=" + query(slug) + "&id=" + query(id) + "&ep=" + ep)
            ?.let { ReelFrenParse.playback(it) } ?: return false
        if (play.locked) return false
        for (subtitle in play.subtitles) {
            val url = absUrl(subtitle.url)
            if (url.isNotEmpty()) {
                subtitleCallback(newSubtitleFile(subtitle.lang.ifEmpty { subtitle.label }.ifEmpty { "und" }, url))
            }
        }
        val referer = mainUrl.trimEnd('/') + "/watch/" + slug + "/" + id + "?ep=" + ep + "&lang=en"
        val headers = mapOf(
            "User-Agent" to UA,
            "Referer" to referer,
            "Origin" to mainUrl.trimEnd('/'),
            "Accept" to "*/*",
            "Connection" to "keep-alive"
        )
        var emitted = false
        for (q in play.qualities.distinctBy { it.url }) {
            val abs = absUrl(q.url)
            if (abs.isEmpty()) continue
            val label = q.label.ifEmpty { "Auto" }
            callback(
                newExtractorLink(
                    "ReelFren",
                    "[S" + play.server + "] " + label,
                    abs,
                    if (q.format.equals("hls", true) || abs.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
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

    private fun toCard(slug: String, item: HomeItem): SearchResponse? {
        val data = ReelFrenCodec.encodeLoadData(slug, item.id)
        val poster = item.cover.ifEmpty { null }
        return if (item.episodes > 1) {
            newTvSeriesSearchResponse(item.title, data, TvType.TvSeries) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(item.title, data, TvType.Movie) { this.posterUrl = poster }
        }
    }

    private suspend fun apiGet(url: String): String? {
        val headers = mapOf(
            "User-Agent" to UA,
            "Accept" to "application/json",
            "Accept-Language" to "en-US,en;q=0.9",
            "Origin" to REEL_DEFAULT_WEB,
            "Referer" to REEL_DEFAULT_WEB + "/"
        )
        var challengeSeen = false
        for (attempt in 0..1) {
            val body = withTimeoutOrNull(20000L) {
                runCatching {
                    val response = app.get(url, headers = headers)
                    val text = response.text
                    if (response.code in 200..299 && text.isNotBlank() && !isChallenge(text)) {
                        text
                    } else {
                        if (response.code == 403 || response.code == 503 || isChallenge(text)) challengeSeen = true
                        null
                    }
                }.getOrNull()
            }
            if (!body.isNullOrBlank()) return body
            if (attempt == 0) delay(350L)
        }
        if (!challengeSeen) return null
        return withTimeoutOrNull(30000L) {
            runCatching {
                val response = app.get(url, headers = headers, interceptor = cfKiller)
                if (response.code in 200..299) response.text else null
            }.getOrNull()?.takeIf { it.isNotBlank() && !isChallenge(it) }
        }
    }

    private fun isChallenge(body: String): Boolean {
        return body.contains("<title>Just a moment", true) ||
            body.contains("/cdn-cgi/challenge-platform/", true)
    }

    private fun query(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun absUrl(u: String): String {
        val t = u.trim()
        if (t.isEmpty()) return ""
        if (t.startsWith("http")) return t
        if (t.startsWith("//")) return "https:$t"
        return apiBase() + if (t.startsWith("/")) t else "/$t"
    }
}
