package com.nunodrama

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
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class NunoDramaProvider : MainAPI() {

    override var mainUrl = DEFAULT_BASE
    override var name = "NunoDrama"
    override var lang = LANG_EN
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override var sequentialMainPage = false
    override var sequentialMainPageDelay = 250L
    override var sequentialMainPageScrollDelay = 250L
    override val supportedTypes = setOf(TvType.TvSeries, TvType.AsianDrama, TvType.Anime)
    override val getMainPageTimeoutMs = 60_000L
    override val searchTimeoutMs = 120_000L
    override val quickSearchTimeoutMs = 60_000L
    override val loadTimeoutMs = 45_000L

    private val cursors = ConcurrentHashMap<String, String>()
    private val seenIds = ConcurrentHashMap<String, MutableSet<String>>()
    private val emptyStreak = ConcurrentHashMap<String, Int>()
    private val searchCache = ConcurrentHashMap<String, List<Pair<Provider, DramaDto>>>()
    private val searchOrder = Collections.synchronizedList(ArrayList<String>())

    override val mainPage: List<MainPageData>
        get() {
            val providers = NunoDramaRegistry.cached()
            if (providers.isEmpty()) return emptyList()
            return buildList {
                providers.forEach { add(MainPageData(name = it.name, data = it.slug)) }
                add(MainPageData(name = MIXED_RAIL, data = RAIL_ALL))
            }
        }

    suspend fun warmUp() {
        if (NunoDramaRegistry.cached().isEmpty()) NunoDramaRegistry.providers()
    }

    fun refresh() {
        NunoDramaStore.clearPages()
        NunoDramaRegistry.invalidate()
        cursors.clear()
        seenIds.clear()
        emptyStreak.clear()
        searchCache.clear()
        synchronized(searchOrder) { searchOrder.clear() }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page <= 0) return emptyRail(request)
        val providers = NunoDramaRegistry.providers()
        if (providers.isEmpty()) return emptyRail(request)
        return if (request.data == RAIL_ALL) {
            mixedRail(page, request, providers)
        } else {
            providerRail(request.data, page, request)
        }
    }

    private fun emptyRail(request: MainPageRequest): HomePageResponse =
        newHomePageResponse(listOf(HomePageList(request.name, emptyList())), hasNext = false)

    private suspend fun providerRail(slug: String, page: Int, request: MainPageRequest): HomePageResponse {
        val category = NunoDramaRegistry.categoryFor(slug)
        val cacheKey = pageCacheKey(slug, category, page)
        startSequence(railScope(Rail.PROVIDER, slug, category), page)

        cachedCards(slug, cacheKey)?.let { return railResponse(request, railScope(Rail.PROVIDER, slug, category), page, it) }

        val cursor = cursors["${railKey(slug, category)}|${page - 1}"]
        var resolved = category
        var section = NunoDramaClient.getSection(slug, resolved, page, cursor)

        if (section != null && section.dramas.isEmpty() && page == 1) {
            // The default name is wrong for a handful of providers. Learn the
            // real one once, so the empty rail fills in on this same request and
            // every later one goes straight to the right section.
            val learned = NunoDramaRegistry.relearn(slug)
            if (learned != null && learned != resolved) {
                resolved = learned
                section = NunoDramaClient.getSection(slug, learned, 1, null)
            }
        }

        if (section == null) {
            return if (page > 1) newHomePageResponse(listOf(HomePageList(request.name, emptyList())), hasNext = false)
            else emptyRail(request)
        }

        section.next?.takeIf { it.isNotBlank() }?.let { rememberCursor("${railKey(slug, resolved)}|$page", it) }
        if (section.dramas.isNotEmpty()) {
            NunoDramaStore.savePage(
                pageCacheKey(slug, resolved, page),
                CachedPage(next = section.next, items = section.dramas),
            )
        }

        val seenKey = railScope(Rail.PROVIDER, slug, resolved)
        val cards = section.dramas.filter { rememberNew(seenKey, it.bookId) }.map { it.toSearchResponse(slug) }
        reportRail(slug, page, resolved, section.dramas.size, cards.size)
        return railResponse(request, seenKey, page, cards)
    }

    private fun pageCacheKey(slug: String, category: String, page: Int) = "P:$slug@$category|$page"

    private fun reportRail(slug: String, page: Int, category: String, items: Int, cards: Int) {
        if (cards > 0) {
            Log.i(TAG, "rail $slug p$page ($category): $items items -> $cards cards")
        } else {
            Log.w(TAG, "rail $slug p$page ($category): EMPTY, $items items, $cards cards")
        }
    }

    private fun cachedCards(slug: String, cacheKey: String): List<SearchResponse>? {
        val cached = NunoDramaStore.loadPage(cacheKey, PAGE_CACHE_MINUTES) ?: return null
        if (cached.items.isEmpty()) return null
        return cached.items.map { it.toSearchResponse(slug) }
    }

    private suspend fun mixedRail(page: Int, request: MainPageRequest, providers: List<Provider>): HomePageResponse {
        val batches = withTimeoutOrNull(MIXED_RAIL_BUDGET_MS) {
            if (page == 1) providers.forEach { startSequence(mixedScope(it.slug), page) }
            NunoDramaClient.mapBounded(providers, HTTP_PARALLELISM) { provider ->
                val category = NunoDramaRegistry.categoryFor(provider.slug)
                val cursorKey = railKey(provider.slug, category)
                val seenKey = mixedScope(provider.slug)
                val cacheKey = "M:$category|${provider.slug}|$page"
                val cached = NunoDramaStore.loadPage(cacheKey, PAGE_CACHE_MINUTES)
                if (cached != null && cached.items.isNotEmpty()) {
                    cached.items.forEach { rememberNew(seenKey, it.bookId) }
                    cached.items.map { provider to it }
                } else {
                    val fresh = NunoDramaClient.getSection(
                        provider.slug,
                        category,
                        page,
                        cursors["$cursorKey|${page - 1}"],
                    )
                    if (fresh != null) {
                        fresh.next?.takeIf { it.isNotBlank() }?.let { rememberCursor("$cursorKey|$page", it) }
                        if (fresh.dramas.isNotEmpty()) {
                            NunoDramaStore.savePage(cacheKey, CachedPage(next = fresh.next, items = fresh.dramas))
                        }
                    }
                    fresh?.dramas.orEmpty().filter { rememberNew(seenKey, it.bookId) }.map { provider to it }
                }
            }
        }.orEmpty()
        val cards = batches.filterNotNull().flatten()
            .take(CATALOGUE_PAGE_SIZE)
            .map { (provider, drama) -> drama.toSearchResponse(provider.slug, provider.name) }
        return railResponse(request, RAIL_ALL, page, cards)
    }

    private fun railResponse(
        request: MainPageRequest,
        key: String,
        page: Int,
        cards: List<SearchResponse>,
    ): HomePageResponse {
        if (page >= MAX_PAGES) {
            return newHomePageResponse(listOf(HomePageList(request.name, cards)), hasNext = false)
        }
        val exhausted = registerEmptyProgress(key, cards.isEmpty()) >= MAX_EMPTY_PAGES
        return newHomePageResponse(listOf(HomePageList(request.name, cards)), hasNext = !exhausted)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val q = query.trim()
        if (q.isEmpty()) return newSearchResponseList(emptyList(), false)
        if (page <= 0) return newSearchResponseList(emptyList(), false)
        val merged = mergedSearch(q)
        val from = (page - 1) * SEARCH_PAGE_SIZE
        if (from >= merged.size) return newSearchResponseList(emptyList(), false)
        val to = minOf(from + SEARCH_PAGE_SIZE, merged.size)
        val cards = merged.subList(from, to).map { (provider, drama) ->
            drama.toSearchResponse(provider.slug, provider.name)
        }
        return newSearchResponseList(cards, to < merged.size)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return mergedSearch(q).take(SEARCH_PAGE_SIZE).map { (provider, drama) ->
            drama.toSearchResponse(provider.slug, provider.name)
        }
    }

    private suspend fun mergedSearch(query: String): List<Pair<Provider, DramaDto>> {
        val id = query.lowercase()
        searchCache[id]?.let { return it }
        val providers = NunoDramaRegistry.providers()
        if (providers.isEmpty()) return emptyList()
        val batches = NunoDramaClient.mapBounded(providers, HTTP_PARALLELISM) { provider ->
            NunoDramaClient.search(provider.slug, query).take(SEARCH_PER_PROVIDER).map { provider to it }
        }
        val merged = LinkedHashMap<String, Pair<Provider, DramaDto>>()
        for (entry in batches.filterNotNull().flatten()) {
            val (provider, drama) = entry
            merged.putIfAbsent("${provider.slug}|${drama.bookId}", entry)
        }
        val result = merged.values.toList()
        if (result.isNotEmpty()) storeSearch(id, result)
        return result
    }

    private fun storeSearch(id: String, value: List<Pair<Provider, DramaDto>>) {
        synchronized(searchOrder) {
            if (searchCache.containsKey(id)) return
            searchOrder.add(id)
            searchCache[id] = value
            while (searchOrder.size > SEARCH_CACHE_LIMIT) searchCache.remove(searchOrder.removeAt(0))
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val match = DETAIL_PATH.find(url) ?: return null
        val slug = match.groupValues[1]
        val bookId = match.groupValues[2]
        if (slug.isEmpty() || bookId.isEmpty()) return null

        val detail = NunoDramaClient.getHtml("/detail/$slug/$bookId", slug, DETAIL_CACHE_MINUTES) ?: return null
        val series = NunoDramaStreams.parseSeriesLd(detail)
        val title = series?.name?.takeIf { it.isNotBlank() } ?: titleFromHtml(detail) ?: return null
        val episodes = buildEpisodes(slug, bookId, series?.episodeCount ?: 0)
        if (episodes.isEmpty()) return null

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = (series?.image?.trim()?.takeIf { it.isNotEmpty() } ?: coverFromHtml(detail))
                ?.let { NunoDramaClient.absolute(it) }
            this.plot = series?.description?.takeIf { it.isNotBlank() } ?: descriptionFromHtml(detail)
            this.tags = buildTags(series, episodes.size)
        }
    }

    private suspend fun buildEpisodes(slug: String, bookId: String, declared: Int): List<Episode> {
        val watch = NunoDramaClient.getHtml("/watch/$slug/$bookId?ep=1", slug, DETAIL_CACHE_MINUTES)
        val refs = watch?.let { html -> NunoDramaStreams.parseEpisodes(html, NunoDramaClient::absolute) }.orEmpty()
        val numbered = when {
            refs.isNotEmpty() -> refs
            declared > 0 -> (1..declared).map { EpisodeRef(it, watchUrl(slug, bookId, it)) }
            else -> emptyList()
        }
        return numbered.map { ref ->
            newEpisode(ref.url) {
                this.name = "Episode ${ref.number}"
                this.episode = ref.number
            }
        }
    }

    private fun buildTags(series: TvSeriesLd?, episodeCount: Int): List<String> {
        val tags = LinkedHashSet<String>()
        series?.language?.takeIf { it.isNotBlank() }?.let { tags.add(languageTag(it)) }
        if (episodeCount > 0) tags.add("$episodeCount Episodes")
        tags.add("Short Drama")
        return tags.toList()
    }

    private fun languageTag(code: String): String = when (code.lowercase()) {
        LANG_ID -> "Indonesian"
        LANG_EN -> "English"
        else -> code
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val match = WATCH_PATH.find(data) ?: return false
        val slug = match.groupValues[1]
        val bookId = match.groupValues[2]
        val episode = EPISODE_QUERY.find(data)?.groupValues?.get(1)?.toIntOrNull() ?: 1

        val html = NunoDramaClient.getHtml("/watch/$slug/$bookId?ep=$episode", slug) ?: return false
        val player = NunoDramaStreams.parsePlayer(html) ?: return false
        val headers = NunoDramaClient.playbackHeaders()

        emitSubtitles(html, subtitleCallback)

        val type = NunoDramaStreams.linkType(player.kind, player.url)
        var emitted = 0
        if (type == ExtractorLinkType.M3U8) {
            val body = NunoDramaClient.getText(player.url, headers)
            val variants = body
                ?.takeIf { NunoDramaStreams.isMasterPlaylist(it) }
                ?.let { NunoDramaStreams.parseMaster(it, player.url) }
                .orEmpty()
            if (variants.isEmpty()) {
                callback(link(player.url, player.title, episode, "Auto", 0, type, headers))
                emitted = 1
            } else {
                val seen = HashSet<String>()
                for (variant in variants) {
                    if (!seen.add(variant.url)) continue
                    val height = variant.height
                    val label = NunoDramaStreams.qualityLabel(height, variant.bandwidth)
                    callback(
                        link(
                            variant.url,
                            player.title,
                            episode,
                            label,
                            if (height > 0) height else getQualityFromName(variant.resolution),
                            type,
                            headers,
                        )
                    )
                    emitted++
                }
            }
        } else {
            val height = qualityFromPath(player.url)
            callback(
                link(
                    player.url,
                    player.title,
                    episode,
                    if (height > 0) "${height}p" else "Auto",
                    height,
                    type,
                    headers,
                )
            )
            emitted = 1
        }
        return emitted > 0
    }

    private suspend fun link(
        url: String,
        title: String,
        episode: Int,
        quality: String,
        height: Int,
        type: ExtractorLinkType,
        headers: Map<String, String>,
    ): ExtractorLink {
        val base = title.ifBlank { "Episode $episode" }
        return newExtractorLink(name, "$base - Ep $episode [$quality]", url, type) {
            // Measured over 144 live probes: sending Referer/Origin breaks CDNs such as
            // reelala (6/6 with bare UA, 0/6 with either) and never helps any provider.
            // The site ships referrerpolicy="no-referrer" on its own player for this reason.
            this.referer = ""
            this.headers = headers
            this.quality = if (height > 0) height else Qualities.Unknown.value
        }
    }

    private suspend fun emitSubtitles(html: String, subtitleCallback: (SubtitleFile) -> Unit) {
        val document = runCatching { Jsoup.parse(html) }.getOrNull() ?: return
        val seen = HashSet<String>()
        document.select("track[src]").forEach { track ->
            val src = NunoDramaClient.absolute(track.attr("src").trim())
            if (src.isEmpty() || !seen.add(src)) return@forEach
            val lang = subtitleLang(track.attr("srclang").trim(), track.attr("label").trim())
            addSubtitle(lang, src, subtitleCallback)
        }
        document.select("a[href]").forEach { anchor ->
            val href = anchor.attr("href").trim()
            if (!SUBTITLE_EXTENSIONS.any { href.endsWith(it, ignoreCase = true) }) return@forEach
            val src = NunoDramaClient.absolute(href)
            if (!seen.add(src)) return@forEach
            addSubtitle(subtitleLang("", anchor.text().trim()), src, subtitleCallback)
        }
    }

    private fun subtitleLang(srclang: String, label: String): String {
        val candidates = listOf(srclang, label.lowercase(), label)
        for (candidate in candidates) {
            val normalized = candidate.trim().lowercase()
            if (normalized.isEmpty()) continue
            if (LANG_ID in normalized || "indonesia" in normalized) return LANG_ID
            if (LANG_EN in normalized || "english" in normalized) return LANG_EN
        }
        return LANG_EN
    }

    private suspend fun addSubtitle(lang: String, url: String, subtitleCallback: (SubtitleFile) -> Unit) {
        if (!url.startsWith("http")) return
        subtitleCallback(
            newSubtitleFile(lang, url) {
                this.headers = NunoDramaClient.playbackHeaders()
            }
        )
    }

    private fun qualityFromPath(url: String): Int =
        QUALITY_TOKEN.find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun DramaDto.toSearchResponse(slug: String, providerName: String? = null): SearchResponse {
        val book = bookId.trim()
        val title = bookName.trim().ifEmpty { book }
        val provider = providerName?.trim()?.takeIf { it.isNotEmpty() }
            ?: platformName.trim().takeIf { it.isNotEmpty() }
            ?: slug
        return newTvSeriesSearchResponse("$title • $provider", detailUrl(slug, book), tvTypeFor(slug)) {
            this.id = (slug + "|" + book).hashCode()
            this.posterUrl = cover?.trim()?.takeIf { it.isNotEmpty() }?.let { NunoDramaClient.absolute(it) }
            this.episodes = chapterCount.takeIf { it > 0 }
        }
    }

    private fun tvTypeFor(slug: String): TvType = when (slug.lowercase()) {
        "anime", "donghua" -> TvType.Anime
        "drakor", "drakorid" -> TvType.AsianDrama
        else -> TvType.TvSeries
    }

    private fun siteBase(): String = NunoDramaStore.base().trimEnd('/')

    private fun detailUrl(slug: String, bookId: String): String = "${siteBase()}/detail/$slug/$bookId"

    private fun watchUrl(slug: String, bookId: String, episode: Int): String =
        "${siteBase()}/watch/$slug/$bookId?ep=$episode"

    private fun railKey(slug: String, category: String): String = "$slug|$category"

    /**
     * Page one starts a fresh pass down a rail. Without this the dedupe state
     * from the previous visit is still in memory, so reopening the home screen
     * filters every id as already seen and every rail comes back empty.
     * Cursors deliberately survive: they track how far down the rail we are.
     */
    private fun startSequence(seenKey: String, page: Int) {
        if (page != 1) return
        seenIds.remove(seenKey)
        emptyStreak.remove(seenKey)
    }

    private fun railScope(rail: Rail, slug: String, category: String): String =
        "${rail.name}:$slug@$category"

    private fun mixedScope(slug: String): String =
        railScope(Rail.MIXED, slug, NunoDramaRegistry.categoryFor(slug))

    private fun rememberCursor(key: String, value: String) {
        if (cursors.size >= MAX_CURSOR_ENTRIES) cursors.clear()
        cursors[key] = value
    }

    private fun rememberNew(key: String, id: String): Boolean {
        if (id.isEmpty()) return false
        val seen = seenIds.computeIfAbsent(key) { Collections.synchronizedSet(LinkedHashSet()) }
        if (!seen.add(id)) return false
        if (seen.size > SEEN_MEMORY) {
            synchronized(seen) {
                if (seen.size > SEEN_MEMORY) {
                    val keep = seen.toList().takeLast(SEEN_MEMORY / 2)
                    seen.clear()
                    seen.addAll(keep)
                }
            }
        }
        return true
    }

    private fun registerEmptyProgress(key: String, produced: Boolean): Int {
        val streak = if (produced) 0 else (emptyStreak[key] ?: 0) + 1
        emptyStreak[key] = streak
        return streak
    }

    private fun metaContent(html: String, property: String): String? {
        val document = runCatching { Jsoup.parse(html) }.getOrNull() ?: return null
        return document.selectFirst("meta[property=$property]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun titleFromHtml(html: String): String? =
        metaContent(html, "og:title")?.substringBefore(" — ")?.takeIf { it.isNotEmpty() }

    private fun descriptionFromHtml(html: String): String? = metaContent(html, "og:description")

    private fun coverFromHtml(html: String): String? = metaContent(html, "og:image")

    private const val TAG = "NunoDrama"

    private enum class Rail { PROVIDER, MIXED }

    private companion object {
        const val RAIL_ALL = "__all__"
        const val MIXED_RAIL = "🌐 All Providers"
        const val MAX_PAGES = 100
        const val MAX_EMPTY_PAGES = 3
        const val SEEN_MEMORY = 900
        const val SEARCH_CACHE_LIMIT = 24
        const val MAX_CURSOR_ENTRIES = 4096
        const val MIXED_RAIL_BUDGET_MS = 20_000L
        const val PAGE_CACHE_MINUTES = 180L
        const val DETAIL_CACHE_MINUTES = 5

        val DETAIL_PATH = Regex("""/detail/([^/?#]+)/([^/?#]+)""")
        val WATCH_PATH = Regex("""/watch/([^/?#]+)/([^/?#]+)""")
        val EPISODE_QUERY = Regex("""[?&]ep=(\d+)""")
        val QUALITY_TOKEN = Regex("""(?i)(2160|1440|1080|720|480|360|240)""")
        val SUBTITLE_EXTENSIONS = listOf(".srt", ".vtt", ".ass")
    }
}
