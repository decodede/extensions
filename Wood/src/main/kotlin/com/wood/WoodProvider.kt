package com.wood

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

@Serializable
data class WoodFile(val url: String)

@Serializable
data class WoodPayload(val files: List<WoodFile>)

private val woodJson = Json { ignoreUnknownKeys = true }

private inline fun <T> safe(fallback: T, block: () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: Throwable) {
    fallback
}

class WoodProvider : MainAPI() {

    companion object {
        const val MAIN_URL = "https://movieswood.cloud"
        const val REFERER = "$MAIN_URL/"
        const val PARAM_QUERY = "q"
        const val PARAM_PAGE = "page"
        const val PARAM_DETAIL = "d"
        const val KEY_DETAIL = "?$PARAM_DETAIL"
        const val FIRST_PAGE = 1
        const val PAGE_FULL = 20
        const val QUICK_LIMIT = 20
        const val CONCURRENCY = 7
        const val RANGE_HEADER = "Range"
        const val RANGE_OPEN = "bytes=0-"
        const val SPACE = " "
        const val SUFFIX_M3U8 = ".m3u8"
        const val TMDB_SMALL = "https://image.tmdb.org/t/p/w300"
        const val TMDB_LARGE = "https://image.tmdb.org/t/p/w500"
        const val ENCODING = "UTF-8"

        const val UA =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Mobile Safari/537.36"

        val BROWSER_HEADERS = mapOf(
            "User-Agent" to UA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "Upgrade-Insecure-Requests" to "1",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "none",
        )

        val MEDIA_HEADERS = mapOf(
            "User-Agent" to UA,
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.9",
        )

        val MIRRORS = listOf(MAIN_URL, "https://www.movieswood.cloud")

        val MEDIA_PATTERN = Regex("""\.(mp4|mkv|m3u8|avi|mov)(\?|#|$)""", RegexOption.IGNORE_CASE)
        val YEAR_PATTERN = Regex("""\b(19[0-9]{2}|20[0-9]{2})\b""")
        val SCORE_PATTERN = Regex("""\b([0-9]\.[0-9])\b""")
        val QUALITY_PATTERN = Regex("""(\d{3,4})\s*[pP]""")
        val EPISODE_SE = Regex("""(?i)s\s*(\d{1,2})\s*e(?:p|pisode)?\.?\s*(\d{1,3})""")
        val EPISODE_PLAIN = Regex("""(?i)\bep(?:isode)?\.?\s*(\d{1,3})""")
        val PAGE_PATTERN = Regex("""(\d+)\s*/\s*(\d+)""")
        val PAGE_ARG = Regex("""$PARAM_PAGE=\d+""")
        val PAGE_QUERY = Regex("""\?$PARAM_PAGE=[^&]*&?""")
        val WHITESPACE = Regex("""\s+""")
        val RATING_TAIL = Regex("""\s+[0-9]\.[0-9]$""")
        val SERIES_HINT = Regex("""(?i)\b(season|web\s*series|tv\s*show)\b""")
        val PLACEHOLDERS = listOf("no-image", "placeholder")

        val CATEGORY_SELECTORS = listOf(
            "section.categories div.card > a.row",
            "div.card > a[class=row]",
        )

        @Volatile
        private var discovered: List<Pair<String, String>> = emptyList()

        fun rows(): List<Pair<String, String>> = discovered

        suspend fun warmUp(): List<Pair<String, String>> {
            discovered.takeIf { it.isNotEmpty() }?.let { return it }
            val doc = fetch("$MAIN_URL/")
            val found = doc?.let(::discover).orEmpty()
            discovered = found
            return found
        }

        private fun discover(doc: Document): List<Pair<String, String>> {
            for (selector in CATEGORY_SELECTORS) {
                val found = LinkedHashMap<String, Pair<String, String>>()
                safe(emptyList()) { doc.select(selector) }.forEach { anchor ->
                    val href = anchor.attr("href").trim()
                    if (href.isBlank() || href.startsWith("#") || KEY_DETAIL in href) return@forEach
                    val label = label(anchor)
                    if (label.isBlank()) return@forEach
                    found.putIfAbsent(href, label to absolute(MAIN_URL, href))
                }
                if (found.isNotEmpty()) return found.values.toList()
            }
            return emptyList()
        }

        private fun label(anchor: Element): String {
            val named = anchor.selectFirst(".row-title")?.ownText()?.trim()
            if (!named.isNullOrBlank()) return named
            val clone = anchor.clone()
            clone.select(".badge,.arrow").remove()
            return clone.ownText().replace(WHITESPACE, SPACE).trim()
        }

        fun absolute(base: String, href: String): String {
            val h = href.replace("&amp;", "&").trim()
            if (h.startsWith("http", true)) return h
            if (h.startsWith("//")) return "https:$h"
            val stem = base.substringBefore("?").trimEnd('/')
            return if (h.startsWith("/")) "$stem$h" else "$stem/$h"
        }

        fun directory(url: String): String {
            val cut = url.substringBefore("?")
            return if (cut.endsWith("/")) cut else "$cut/"
        }

        fun host(url: String): String = safe(MAIN_URL) {
            URI(url).host.orEmpty().removePrefix("www.")
        }

        fun quality(text: String): Int {
            QUALITY_PATTERN.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
            val lower = text.lowercase()
            return when {
                lower.contains("4k") || lower.contains("2160") -> 2160
                lower.contains("1440") -> 1440
                lower.contains("1080") -> 1080
                lower.contains("720") -> 720
                lower.contains("480") -> 480
                lower.contains("360") -> 360
                else -> 0
            }
        }

        fun slot(name: String): Pair<Int, Int>? {
            EPISODE_SE.find(name)?.let {
                val season = it.groupValues[1].toIntOrNull()
                val index = it.groupValues[2].toIntOrNull()
                if (season != null && index != null) return season to index
            }
            val index = EPISODE_PLAIN.find(name)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            return FIRST_PAGE to index
        }

        fun episodeName(index: Int, versions: Int): String =
            if (versions > 1) "E$index ($versions)" else "E$index"

        fun paged(base: String, page: Int): String = when {
            page <= FIRST_PAGE && PARAM_PAGE !in base -> base
            page <= FIRST_PAGE -> base.replace(PAGE_QUERY, "?").trimEnd('?', '&')
            PAGE_ARG.containsMatchIn(base) -> PAGE_ARG.replace(base, "$PARAM_PAGE=$page")
            else -> "$base${if (PARAM_QUERY in base) "&" else "?"}$PARAM_PAGE=$page"
        }

        suspend fun fetch(url: String): Document? = safe(null) {
            for (mirror in MIRRORS) {
                val target = absolute(mirror, url)
                val res = app.get(target, headers = BROWSER_HEADERS, referer = REFERER)
                if (res.code == 200 && res.document.body().children().isNotEmpty()) return res.document
            }
            null
        }
    }

    override var mainUrl = MAIN_URL
    override var name = "Wood"
    override var lang = "te"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage: List<MainPageData>
        get() = rows().map { MainPageData(it.first, it.second) }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor = Interceptor { chain ->
        val request = chain.request()
        if (request.header(RANGE_HEADER) != null) chain.proceed(request)
        else chain.proceed(request.newBuilder().header(RANGE_HEADER, RANGE_OPEN).build())
    }

    private suspend fun <T, R> parallel(items: List<T>, limit: Int, block: suspend (T) -> R?): List<R> =
        safe(emptyList()) {
            coroutineScope {
                val gate = Semaphore(limit)
                items.map { item -> async { gate.withPermit { safe(null) { block(item) } } } }
                    .awaitAll()
                    .filterNotNull()
            }
        }

    private fun poster(src: String?): String? = src
        ?.takeIf { it.isNotBlank() && PLACEHOLDERS.none { it in src.lowercase() } }
        ?.replace(TMDB_SMALL, TMDB_LARGE)

    private fun cards(doc: Document, base: String): List<SearchResponse> =
        doc.select("a.card")
            .filter { KEY_DETAIL in it.attr("href") }
            .mapNotNull { anchor ->
                val href = anchor.attr("href").trim()
                if (href.isBlank()) return@mapNotNull null
                val name = anchor.selectFirst(".card-name")?.ownText()?.replace(WHITESPACE, SPACE)
                    ?.trim()?.replace(RATING_TAIL, "")?.trim()
                    ?: anchor.selectFirst("img")?.attr("alt")?.trim()
                    ?: return@mapNotNull null
                if (name.isBlank()) return@mapNotNull null
                val meta = anchor.select(".card-meta span, .year").joinToString(SPACE) { it.text() }
                val year = YEAR_PATTERN.find(meta)?.groupValues?.get(1)?.toIntOrNull()
                val image = poster(anchor.selectFirst(".card-img img")?.attr("abs:src"))
                val url = absolute(base, href)
                if (SERIES_HINT.containsMatchIn(name) || slot(meta) != null) {
                    newTvSeriesSearchResponse(name, url, TvType.TvSeries) {
                        this.posterUrl = image
                        this.year = year
                    }
                } else {
                    newMovieSearchResponse(name, url, TvType.Movie) {
                        this.posterUrl = image
                        this.year = year
                    }
                }
            }
            .distinctBy { it.url }

    private fun hasNext(doc: Document, found: List<SearchResponse>, page: Int): Boolean {
        if (found.isEmpty()) return false
        val indicator = safe(null) {
            doc.selectFirst("div.pag span.cur")?.text()?.let { PAGE_PATTERN.find(it) }
        } ?: return found.size >= PAGE_FULL
        val current = indicator.groupValues.getOrNull(1)?.toIntOrNull() ?: return true
        val total = indicator.groupValues.getOrNull(2)?.toIntOrNull() ?: return true
        return current < total && page < total
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse = safe(newHomePageResponse(emptyList(), false)) {
        val index = page.coerceAtLeast(FIRST_PAGE)
        val doc = fetch(paged(request.data, index))
        val found = doc?.let { cards(it, directory(request.data)) }.orEmpty()
        newHomePageResponse(
            HomePageList(request.name, found),
            hasNext = doc?.let { hasNext(it, found, index) } ?: false,
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList =
        safe(newSearchResponseList(emptyList(), false)) {
            val term = query.trim()
            if (term.isEmpty()) newSearchResponseList(emptyList(), false)
            else {
                val index = page.coerceAtLeast(FIRST_PAGE)
                val encoded = URLEncoder.encode(term, ENCODING).replace("+", "%20")
                val categories = warmUp().map { it.second }.ifEmpty { listOf(MAIN_URL) }
                val perCategory = parallel(categories, CONCURRENCY) { base ->
                    val target = paged("$base?$PARAM_QUERY=$encoded", index)
                    val doc = fetch(target)
                    val items = doc?.let { cards(it, directory(target)) }.orEmpty()
                    Pair(items, doc?.let { hasNext(it, items, index) } ?: false)
                }
                val found = perCategory.flatMap { it.first }.distinctBy { it.url }
                newSearchResponseList(found, hasNext = perCategory.any { it.second })
            }
        }

    override suspend fun search(query: String): List<SearchResponse>? = search(query, FIRST_PAGE).items

    override suspend fun quickSearch(query: String): List<SearchResponse>? =
        search(query, FIRST_PAGE).items.take(QUICK_LIMIT)

    override suspend fun load(url: String): LoadResponse? = safe(null) {
        val doc = fetch(url) ?: return@safe null
        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("title")?.text()?.substringBefore("-")?.trim()
            ?: return@safe null
        if (title.isBlank()) return@safe null
        val image = poster(doc.selectFirst(".movie-poster img")?.attr("abs:src"))
        val plot = doc.selectFirst(".movie-overview")?.text()?.trim()
        val tags = doc.select(".meta-tag").map { it.text().trim() }.filter { it.isNotBlank() }
        val blob = tags.joinToString(SPACE)
        val year = YEAR_PATTERN.find(blob)?.groupValues?.get(1)?.toIntOrNull()
        val score = SCORE_PATTERN.find(blob)?.groupValues?.get(1)?.let { Score.from10(it) }
        val files = doc.select(".file-item").mapNotNull { item ->
            val anchor = item.selectFirst("a[href]") ?: return@mapNotNull null
            val href = anchor.attr("href").trim()
            if (href.isBlank()) return@mapNotNull null
            WoodEntry(
                name = item.selectFirst(".file-name")?.text()?.replace(WHITESPACE, SPACE)?.trim().orEmpty(),
                url = absolute(directory(url), href),
            )
        }.distinctBy { it.url }
        if (files.isEmpty()) return@safe null
        val grouped = LinkedHashMap<Pair<Int, Int>, MutableList<WoodEntry>>()
        files.forEach { file ->
            val key = slot(file.name) ?: return@forEach
            grouped.getOrPut(key) { mutableListOf() }.add(file)
        }
        val isSeries = grouped.isNotEmpty()
        if (isSeries) {
            val episodes = grouped.toSortedMap(compareBy({ it.first }, { it.second })).map { (key, group) ->
                newEpisode(woodJson.encodeToString(WoodPayload(group.map { WoodFile(it.url) }))) {
                    this.name = episodeName(key.second, group.size)
                    this.season = key.first
                    this.episode = key.second
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = image
                this.plot = plot
                this.year = year
                this.score = score
                this.tags = tags
            }
        } else {
            val payload = woodJson.encodeToString(WoodPayload(files.map { WoodFile(it.url) }))
            newMovieLoadResponse(title, url, TvType.Movie, payload) {
                this.posterUrl = image
                this.plot = plot
                this.year = year
                this.score = score
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean = safe(false) {
        val decoded = safe(emptyList()) { woodJson.decodeFromString<WoodPayload>(data).files.map { it.url } }
        val targets = decoded.filter { it.isNotBlank() }.ifEmpty { listOf(data) }
        val resolved = parallel(targets, CONCURRENCY) { target -> resolve(target) }
        var emitted = false
        resolved.forEach { bundle ->
            bundle.media.forEach { link ->
                callback(
                    newExtractorLink(
                        source = host(link.url),
                        name = bundle.label.ifBlank { host(link.url) },
                        url = link.url,
                        type = if (link.url.contains(SUFFIX_M3U8, true)) {
                            ExtractorLinkType.M3U8
                        } else {
                            ExtractorLinkType.VIDEO
                        },
                    ) {
                        this.quality = quality(bundle.label)
                        this.referer = REFERER
                        this.headers = MEDIA_HEADERS
                    }
                )
                emitted = true
            }
            bundle.embeds.forEach { loadExtractor(it, REFERER, subtitleCallback, callback) }
        }
        emitted
    }

    private suspend fun resolve(target: String): WoodBundle = safe(WoodBundle("", emptyList(), emptyList())) {
        val doc = fetch(target) ?: return@safe WoodBundle("", emptyList(), emptyList())
        val label = doc.selectFirst("title")?.text()?.substringBefore("-")?.trim().orEmpty()
        val base = host(target)
        val hrefs = doc.select("a[href]").map { absolute(base, it.attr("href")) }.distinct()
        WoodBundle(
            label = label,
            media = hrefs.filter { MEDIA_PATTERN.containsMatchIn(it) }.map { WoodFile(it) },
            embeds = hrefs.filter {
                it.startsWith("http") && !MEDIA_PATTERN.containsMatchIn(it) && host(it) != base
            },
        )
    }
}

data class WoodBundle(
    val label: String,
    val media: List<WoodFile>,
    val embeds: List<String>,
)

data class WoodEntry(val name: String, val url: String)
