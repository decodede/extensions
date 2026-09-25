package com.wood
import android.util.Log
import kotlinx.coroutines.delay
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
fun Element.isDetailAnchor(): Boolean {
    val href = this.attr("href").trim()
    if (href.isBlank() || href.startsWith("#")) return false
    if (FILTER_PARAMS.any { href.contains(it, true) }) return false
    return href.startsWith("/") || href.contains(WoodProvider.MAIN_URL.removePrefix("https://"), true) ||
        (!href.startsWith("http") && !href.startsWith("mailto:") && !href.startsWith("javascript:"))
}
private val FILTER_PARAMS = listOf("list=", "?q=", "&q=", "page=", "letter=")
class WoodProvider : MainAPI() {
    override var mainUrl = MAIN_URL
    override var name = "Wood"
    override val hasMainPage = true
    override var lang = "te"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    private val cfKiller by lazy { CloudflareKiller() }
    companion object {
        const val MAIN_URL = "https://movieswood.cloud"
        private const val TAG = "Wood"
        private val NAV_WORDS = setOf("genres", "latest", "all", "home", "search", "movies")
        private val MIRRORS = listOf(
            MAIN_URL,
            "https://www.movieswood.cloud"
        )
        private const val PATH_TEL = "/tel/"
        private const val PATH_TEL_NEW = "/tel/?list=new"
        private const val PATH_TEL_2026 = "/tel/?list=years&value=2026"
        private const val PATH_TEL_2025 = "/tel/?list=years&value=2025"
        private const val PATH_DUBBING = "/dubbing/"
        private const val PATH_DUBBING_NEW = "/dubbing/?list=new"
        private const val PATH_SEARCH_TEL = "/tel/?q="
        private const val PATH_SEARCH_DUBBING = "/dubbing/?q="
        private const val TMDB_SMALL = "https://image.tmdb.org/t/p/w300"
        private const val TMDB_LARGE = "https://image.tmdb.org/t/p/w500"
        private val YEAR_REGEX = Regex("""\b(19[0-9]{2}|20[0-9]{2})\b""")
        private val RATING_TAIL_REGEX = Regex("""\b([0-9]\.[0-9])\s*$""")
        private val SERIES_REGEX =
            Regex("""(?i)\b(season|episode|ep\s*\d+|web\s*series|tv\s*show)\b""")
        private val SEASON_EP_REGEX = Regex("""(?i)s(\d{1,2})\s*e(\d{1,3})""")
        private val EP_REGEX = Regex("""(?i)(?:episode|ep)\s*(\d{1,3})""")
        private val SEASON_REGEX = Regex("""(?i)season\s*(\d{1,2})""")
        private val PAGE_INDICATOR_REGEX = Regex("""(\d+)\s*/\s*(\d+)""")
        private const val CARD_SELECTOR =
            "a:has(img), div.card a, article a, li a:has(img), div:has(> img) a"
        fun pageUrl(base: String, page: Int): String {
            if (page <= 1) return base
            return if (base.contains("?")) "$base&page=$page" else "$base?page=$page"
        }
        fun upgradePoster(poster: String?): String? {
            if (poster.isNullOrBlank()) return null
            if (poster.contains("no-image", true) || poster.contains("placeholder", true)) return null
            return poster.replace(TMDB_SMALL, TMDB_LARGE)
        }
    }
    override val mainPage = mainPageOf(
        "$mainUrl$PATH_TEL" to "Telugu Movies",
        "$mainUrl$PATH_TEL_NEW" to "Telugu Latest",
        "$mainUrl$PATH_TEL_2026" to "Telugu 2026",
        "$mainUrl$PATH_TEL_2025" to "Telugu 2025",
        "$mainUrl$PATH_DUBBING" to "Dubbed Movies",
        "$mainUrl$PATH_DUBBING_NEW" to "Dubbed Latest"
    )
    private suspend fun fetchAttempt(candidate: String): Document? {
        try {
            val res = app.get(candidate, headers = BROWSER_HEADERS)
            val elements = res.document.body().select("*").size
            Log.d(TAG, "GET $candidate -> ${res.code} len=${res.text.length} els=$elements")
            if (res.code == 200 && elements > 3) return res.document
        } catch (_: Exception) {
        }
        try {
            Log.d(TAG, "CF retry $candidate")
            val solved = app.get(candidate, interceptor = cfKiller)
            val elements = solved.document.body().select("*").size
            Log.d(TAG, "CF $candidate -> ${solved.code} len=${solved.text.length} els=$elements")
            if (solved.code == 200 && elements > 3) return solved.document
        } catch (_: Exception) {
        }
        return null
    }
    private suspend fun getDocument(url: String): Document? {
        val candidates = MIRRORS.map { mirror ->
            if (url.startsWith(MAIN_URL)) mirror + url.removePrefix(MAIN_URL) else url
        }.distinct()
        for (candidate in candidates) {
            fetchAttempt(candidate)?.let { return it }
            delay(500)
            fetchAttempt(candidate)?.let {
                Log.d(TAG, "retry ok $candidate")
                return it
            }
        }
        Log.d(TAG, "GET $url -> all mirrors failed")
        return null
    }
    private fun cleanSrc(v: String): String {
        val t = v.trim()
        if (t.isBlank() || t.startsWith("data:", true)) return ""
        return t
    }
    private fun Element.toCard(base: String): SearchResponse? {
        val href = this.attr("href").trim()
        if (!href.contains("?d=", true)) return null
        val img = this.selectFirst(".card-img img") ?: this.selectFirst("img")
        val rawTitle = this.selectFirst(".card-name")?.text()?.trim().orEmpty()
            .ifBlank { img?.attr("alt")?.trim().orEmpty() }
            .ifBlank { this.text().trim() }
            .ifBlank { this.attr("title").trim() }
        if (rawTitle.isBlank()) return null
        val title = rawTitle.replace(YEAR_REGEX, "").replace(RATING_TAIL_REGEX, "").trim().trimEnd('-', '–', '·', '|', ' ').ifBlank { rawTitle.trim() }
        val rawPoster = img?.let {
            cleanSrc(it.attr("src")).ifBlank { cleanSrc(it.attr("data-src")) }
                .ifBlank { cleanSrc(it.attr("data-lazy-src")) }
                .ifBlank { cleanSrc(it.attr("srcset").substringBefore(" ").substringBefore(",")) }
                .ifBlank { cleanSrc(it.attr("data-srcset").substringBefore(" ").substringBefore(",")) }
        }
        val poster = upgradePoster(rawPoster?.let { joinUrlNull(base, it) }) ?: return null
        val url = joinUrl(base, href)
        if (url.isBlank()) return null
        return if (SERIES_REGEX.containsMatchIn(title)) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }
    private fun safeSelect(doc: Document, css: String): List<Element> {
        return try {
            doc.select(css)
        } catch (_: Exception) {
            emptyList()
        }
    }
    private fun pageBase(pageUrl: String): String {
        val cut = pageUrl.substringBefore("?")
        return if (cut.endsWith("/")) cut else "$cut/"
    }
    private fun listCards(doc: Document, base: String): List<SearchResponse> {
        for (sel in listOf("a.card", CARD_SELECTOR, "a[href]")) {
            val results = safeSelect(doc, sel).mapNotNull {
                try {
                    it.toCard(base)
                } catch (_: Exception) {
                    null
                }
            }.distinctBy { it.url }
            if (results.isNotEmpty()) {
                Log.d(TAG, "listCards: ${results.size} cards via $sel")
                return results
            }
        }
        Log.d(TAG, "listCards: 0 cards")
        return emptyList()
    }
    private fun hasNextPage(doc: Document, results: List<SearchResponse>): Boolean {
        if (results.isEmpty()) return false
        return try {
            if (safeSelect(doc, "a:containsOwn(Next),a:containsOwn(next),a:containsOwn(›),a:containsOwn(»)").isNotEmpty()) return true
            val indicator = PAGE_INDICATOR_REGEX.find(doc.body().text())
            if (indicator != null) {
                val current = indicator.groupValues.getOrNull(1)?.toIntOrNull() ?: return true
                val total = indicator.groupValues.getOrNull(2)?.toIntOrNull() ?: return true
                return current < total
            }
            true
        } catch (_: Exception) {
            true
        }
    }
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val full = pageUrl(request.data, page)
        val doc = getDocument(full)
        val results = doc?.let { listCards(it, pageBase(full)) } ?: emptyList()
        val hasNext = doc?.let { hasNextPage(it, results) } ?: false
        Log.d(TAG, "${request.name} p$page -> ${results.size} items next=$hasNext")
        return newHomePageResponse(listOf(HomePageList(request.name, results)), hasNext = hasNext)
    }
    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = query.trim().replace(" ", "+")
        if (encoded.isBlank()) return emptyList()
        val targets = listOf("$mainUrl$PATH_SEARCH_TEL$encoded", "$mainUrl$PATH_SEARCH_DUBBING$encoded")
        return targets.amap { target ->
            getDocument(target)?.let { listCards(it, pageBase(target)) } ?: emptyList()
        }.flatten().distinctBy { it.url }
    }
    private fun collectFiles(doc: Document, base: String): List<MediaLink> {
        val items = doc.select(".file-item")
        if (items.isNotEmpty()) {
            return items.mapNotNull { item ->
                val anchor = item.selectFirst("a[href]") ?: return@mapNotNull null
                val href = joinUrl(base, anchor.attr("href").trim())
                if (href.isBlank()) return@mapNotNull null
                val label = item.selectFirst(".file-name")?.text()?.trim().orEmpty().ifBlank { anchor.text().trim() }
                val size = item.selectFirst(".file-size")?.text()?.trim().orEmpty()
                MediaLink(href, label, size, qualityFromText("$label $href"))
            }.distinctBy { it.url }
        }
        return doc.select("a[href*=rating.php]").mapNotNull { a ->
            val href = joinUrl(base, a.attr("href").trim())
            if (href.isBlank()) return@mapNotNull null
            val label = a.text().trim().ifBlank { href.substringAfterLast("/") }
            MediaLink(href, label, "", qualityFromText("$label $href"))
        }.distinctBy { it.url }
    }
    override suspend fun load(url: String): LoadResponse? {
        val fixedUrl = joinUrl(mainUrl, url)
        val doc = getDocument(fixedUrl) ?: return null
        val base = pageBase(fixedUrl)
        val title = doc.selectFirst("h1")?.text()?.trim()
            ?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: doc.selectFirst("h2")?.text()?.trim()
            ?: doc.selectFirst("title")?.text()?.substringBefore("-")?.trim()
            ?: return null
        val sitePoster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.ifBlank { null }
            ?: doc.select("img").map { cleanSrc(it.attr("src")).ifBlank { cleanSrc(it.attr("data-src")) } }
                .firstOrNull { it.contains("tmdb", true) || it.startsWith("http") }
        val poster = upgradePoster(sitePoster?.let { joinUrlNull(mainUrl, it) })
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?.ifBlank { null }
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
            ?: doc.select("p").firstOrNull { it.text().length > 60 }?.text()?.trim()
            .orEmpty()
        val year = YEAR_REGEX.find("$title ${doc.body().text().take(4000)}")
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
        val rating10: String? = RATING_TAIL_REGEX.find(title)?.groupValues?.getOrNull(1)
        val tags: List<String> = doc.select("a[href]")
            .filter { it.attr("href").contains("genre", true) && it.attr("href").contains("value=", true) }
            .map { it.text().trim() }
            .filter { it.isNotBlank() && it.lowercase() !in NAV_WORDS }
            .distinct()
        val background: String? = poster
        val files = collectFiles(doc, base)
        val episodes = if (files.isEmpty()) {
            val internal = doc.select("a[href]").filter { it.isDetailAnchor() }
            val eps = internal.filter {
                val hint = "${it.text()} ${it.attr("href")}"
                SEASON_EP_REGEX.containsMatchIn(hint) || EP_REGEX.containsMatchIn(hint)
            }
            if (SERIES_REGEX.containsMatchIn(title) || eps.isNotEmpty()) {
                (eps.ifEmpty { internal }).mapIndexedNotNull { index, anchor ->
                val pageLink = joinUrl(base, anchor.attr("href").trim())
                if (pageLink.isBlank()) return@mapIndexedNotNull null
                val hint = "${anchor.text()} $pageLink"
                val season = SEASON_EP_REGEX.find(hint)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: SEASON_REGEX.find(hint)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: 1
                val episode = SEASON_EP_REGEX.find(hint)?.groupValues?.getOrNull(2)?.toIntOrNull()
                    ?: EP_REGEX.find(hint)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: (index + 1)
                val name = anchor.text().trim().ifBlank { "Episode $episode" }
                newEpisode(pageLink) {
                    this.name = name
                    this.season = season
                    this.episode = episode
                }
                }
            } else emptyList()
        } else emptyList()
        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, fixedUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.score = rating10?.let { Score.from10(it) }
                this.year = year
                this.backgroundPosterUrl = background
            }
        } else {
            newMovieLoadResponse(title, fixedUrl, TvType.Movie, fixedUrl) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.score = rating10?.let { Score.from10(it) }
                this.year = year
                this.backgroundPosterUrl = background
            }
        }
    }
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fixedData = joinUrl(mainUrl, data)
        val doc = getDocument(fixedData) ?: return false
        val base = pageBase(fixedData)
        val links = collectFiles(doc, base)
        Log.d(TAG, "loadLinks: ${links.size} sources for $data")
        if (links.isEmpty()) return false
        links.amap { item ->
            try {
                val label = if (item.size.isBlank()) item.label else "${item.label} [${item.size}]"
                if (DIRECT_MEDIA_PATTERN.containsMatchIn(item.url)) {
                    emitFile(name, label, item.url, item.quality, "$mainUrl/", callback)
                } else {
                    resolveRatingFile(item.url, label, item.quality, fixedData, subtitleCallback, callback)
                }
            } catch (_: Exception) {
            }
        }
        return true
    }
    data class MediaLink(
        val url: String,
        val label: String,
        val size: String,
        val quality: Int
    )
}
