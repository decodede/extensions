package com.wap

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
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
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.net.URLEncoder

private data class RawEntry(val title: String, val url: String)

private data class DwEntry(val href: String, val label: String, val context: String)

class WapProvider : MainAPI() {
    override var mainUrl = BASE_URL
    override var name = "Wap"
    override val hasMainPage = true
    override var lang = "te"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    companion object {
        const val BASE_URL = "https://www.moviezwap.best"
        const val CINEMETA_URL = "https://v3-cinemeta.strem.io"
    }

    override val mainPage = mainPageOf(
        "$BASE_URL/category/Telugu-(2026)-Movies.html" to "Telugu 2026",
        "$BASE_URL/category/Telugu-(2025)-Movies.html" to "Telugu 2025",
        "$BASE_URL/category/Telugu-Web-Series.html" to "Telugu Web Series",
        "$BASE_URL/category/Telugu-Dubbed-Movies-[Hollywood].html" to "Hollywood Telugu Dubbed",
        "$BASE_URL/category/Telugu-Dubbed-Hollywood-Movies-Complete-Set.html" to "Hollywood Complete Sets"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = fetchDoc(pageUrl(request.data, page)) ?: return newHomePageResponse(
            listOf(HomePageList(request.name, emptyList())),
            hasNext = false
        )
        val raw = rawEntries(doc, "img[src*=arroww]")
        val cards = buildCards(raw)
        return newHomePageResponse(listOf(HomePageList(request.name, cards)), hasNext = hasNextPage(doc, page, raw))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val doc = fetchDoc("$BASE_URL/search.php?q=${URLEncoder.encode(q, "UTF-8")}") ?: return emptyList()
        return buildCards(rawEntries(doc, "img[src*=arrow]"))
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = fetchDoc(normalize(url), 20000L) ?: return null
        val title = doc.selectFirst("h2")?.text()?.trim()?.ifEmpty { null }
            ?: doc.selectFirst("title")?.text()?.substringBefore("Free Download")?.trim()?.ifEmpty { null }
            ?: return null
        val poster = fixUrlNull(doc.selectFirst("img[src*=/poster/]")?.attr("src"))
        val backdrop = fixUrlNull(
            doc.selectFirst("a[href*=/ss/] img")?.attr("src")
                ?: doc.selectFirst("img[src*=/ss/]")?.attr("src")
        )
        val year = extractYear(field(doc, "Release Date") + " " + field(doc, "Category") + " " + title)
        val plot = field(doc, "Desc/Plot").ifEmpty {
            doc.selectFirst("meta[name=description]")?.attr("content")?.trim() ?: ""
        }.ifEmpty { null }
        val tags = (field(doc, "Genre").split(",") + listOf(field(doc, "Quality"), field(doc, "Audio"), field(doc, "Category")))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        val actors = field(doc, "Starring").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val recommendations = recommendationEntries(doc, url).map { entry ->
            if (isSeriesTitle(entry.title)) newTvSeriesSearchResponse(entry.title, entry.url, TvType.TvSeries)
            else newMovieSearchResponse(entry.title, entry.url, TvType.Movie)
        }
        val inners = doc.select("div.catList a[href*=/movie/]")
            .map { normalize(it.attr("href")) }
            .filter { it.isNotBlank() }
            .distinct()
        if (inners.isNotEmpty()) {
            val episodes = fetchInnerEpisodes(inners, poster)
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.backgroundPosterUrl = backdrop
                this.recommendations = recommendations
                addActors(actors)
            }
        }
        if (isSeriesTitle(title)) {
            val episode = newEpisode(url) {
                this.name = title
                this.season = seasonNumber(title)
                this.episode = episodeNumber(title)
                this.posterUrl = poster
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf(episode)) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.backgroundPosterUrl = backdrop
                this.recommendations = recommendations
                addActors(actors)
            }
        }
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
            this.backgroundPosterUrl = backdrop
            this.recommendations = recommendations
            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = fetchDoc(normalize(data), 20000L) ?: return false
        val direct = dwEntries(doc)
        val targets = if (direct.isNotEmpty()) direct else {
            val inners = doc.select("div.catList a[href*=/movie/]")
                .map { normalize(it.attr("href")) }
                .filter { it.isNotBlank() }
                .distinct()
            if (inners.isEmpty()) return false
            inners.amap { inner -> dwEntries(fetchDoc(inner, 15000L) ?: return@amap emptyList()) }
                .flatten().distinctBy { it.href }
        }
        if (targets.isEmpty()) return false
        val links = targets.amap { entry -> resolveFinal(entry) }.filterNotNull()
        if (links.isEmpty()) return false
        links.forEach { callback(it) }
        return true
    }

    private suspend fun fetchDoc(url: String, timeoutMs: Long = 15000L): Document? {
        return try {
            withTimeoutOrNull(timeoutMs) { app.get(fixUrl(url)).document }
        } catch (_: Exception) {
            null
        }
    }

    private fun rawEntries(doc: Document, arrow: String): List<RawEntry> {
        val out = ArrayList<RawEntry>()
        for (el in doc.select("div.mylist, div.cat")) {
            if (el.hasClass("cat")) {
                if (el.text().contains("Movies Of The Day", ignoreCase = true)) break
                continue
            }
            if (el.selectFirst(arrow) == null) continue
            val anchor = el.selectFirst("a[href*=/movie/]") ?: continue
            val href = normalize(anchor.attr("href"))
            if (href.isBlank()) continue
            val title = anchor.text().trim().ifEmpty {
                href.substringAfterLast("/").removeSuffix(".html").replace("-", " ")
            }
            if (title.isBlank()) continue
            out.add(RawEntry(title, href))
        }
        return out.distinctBy { it.url }
    }

    private fun recommendationEntries(doc: Document, selfUrl: String): List<RawEntry> {
        val self = fixUrl(normalize(selfUrl))
        return doc.select("div.mylist").mapNotNull { div ->
            if (div.selectFirst("img[src*=arrow.gif]") == null) return@mapNotNull null
            val anchor = div.selectFirst("a[href*=/movie/]") ?: return@mapNotNull null
            val href = normalize(anchor.attr("href"))
            val title = anchor.text().trim()
            if (href.isBlank() || title.isBlank() || href == self) return@mapNotNull null
            RawEntry(title, href)
        }.distinctBy { it.url }.take(12)
    }

    private fun dwEntries(doc: Document): List<DwEntry> {
        return doc.select("div.catList a[href*=dwload.php], div.catList a[href*=download.php]").mapNotNull { anchor ->
            val href = normalize(anchor.attr("href"))
            if (!href.contains("file=")) return@mapNotNull null
            DwEntry(href, anchor.text().trim(), anchor.parent()?.text()?.trim() ?: "")
        }.distinctBy { it.href }
    }

    private suspend fun buildCards(raw: List<RawEntry>): List<SearchResponse> {
        if (raw.isEmpty()) return emptyList()
        return raw.amap { entry ->
            val poster = fetchPosterDirect(entry.url) ?: fetchPosterFallback(entry.title)
            if (isSeriesTitle(entry.title)) {
                newTvSeriesSearchResponse(entry.title, entry.url, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            } else {
                newMovieSearchResponse(entry.title, entry.url, TvType.Movie) {
                    this.posterUrl = poster
                }
            }
        }
    }

    private suspend fun fetchPosterDirect(url: String): String? {
        return try {
            val doc = fetchDoc(url, 12000L) ?: return null
            fixUrlNull(doc.selectFirst("img[src*=/poster/]")?.attr("src"))
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchPosterFallback(title: String): String? {
        return try {
            val clean = cleanTitle(title)
            if (clean.isBlank()) return null
            val body = fetchBody("$CINEMETA_URL/catalog/movie/top/search=${URLEncoder.encode(clean, "UTF-8")}.json", 10000L) ?: return null
            parseCinemetaPoster(body, extractYear(title)?.toString())
        } catch (_: Exception) {
            null
        }
    }

    private fun parseCinemetaPoster(body: String, year: String?): String? {
        return try {
            val metas = JSONObject(body).optJSONArray("metas") ?: return null
            var fallback: String? = null
            for (i in 0 until metas.length()) {
                val meta = metas.optJSONObject(i) ?: continue
                val poster = meta.optString("poster").ifEmpty { null } ?: continue
                if (fallback == null) fallback = poster
                if (year != null && meta.optString("releaseInfo") == year) return poster
            }
            fallback
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchBody(url: String, timeoutMs: Long): String? {
        return try {
            withTimeoutOrNull(timeoutMs) { app.get(url).text }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchInnerEpisodes(inners: List<String>, fallbackPoster: String?): List<Episode> {
        return inners.mapIndexed { index, inner -> index to inner }.amap { (index, inner) ->
            val child = fetchDoc(inner, 10000L)
            val name = child?.selectFirst("h2")?.text()?.trim()?.ifEmpty { null }
                ?: inner.substringAfterLast("/").removeSuffix(".html").replace("-", " ")
            val poster = child?.let { fixUrlNull(it.selectFirst("img[src*=/poster/]")?.attr("src")) } ?: fallbackPoster
            newEpisode(inner) {
                this.name = name
                this.season = 1
                this.episode = index + 1
                this.posterUrl = poster
            }
        }
    }

    private suspend fun resolveFinal(entry: DwEntry): ExtractorLink? {
        return try {
            val hop = fetchDoc(entry.href, 15000L) ?: return null
            val downloadPage = fixUrl(hop.selectFirst("a[href*=download.php?file=]")?.attr("href") ?: entry.href)
            val final = fetchDoc(downloadPage, 15000L)?.let { last ->
                last.selectFirst("a:contains(Fast Download Server)")?.attr("href")?.trim()?.ifEmpty { null } ?: downloadPage
            } ?: downloadPage
            val size = Regex("""\(([\d.]+\s*[MG]B)\)""").find(entry.context)?.groupValues?.get(1)?.trim() ?: ""
            val label = if (size.isEmpty() || entry.label.isEmpty()) entry.label.ifEmpty { "Wap" } else "${entry.label} [$size]"
            newExtractorLink("Wap", label, finalUrl(final), ExtractorLinkType.VIDEO) {
                this.quality = qualityFrom(entry.label)
                this.referer = "$BASE_URL/"
                this.headers = mapOf("Referer" to "$BASE_URL/", "Connection" to "keep-alive", "Accept" to "*/*")
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun finalUrl(url: String): String {
        return if (url.startsWith("http")) url else fixUrl(url)
    }

    private fun fixUrl(url: String): String {
        val u = url.trim()
        if (u.isEmpty() || u.startsWith("http") || u.startsWith("{") || u.startsWith("[")) return u
        if (u.startsWith("//")) return "https:$u"
        if (u.startsWith("/")) return "$BASE_URL$u"
        return "$BASE_URL/$u"
    }

    private fun fixUrlNull(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return fixUrl(url)
    }

    private fun normalize(href: String): String {
        val cleaned = href.trim().replace("/movie//movie/", "/movie/")
        if (cleaned.isBlank()) return ""
        return fixUrl(cleaned)
    }

    private fun pageUrl(base: String, page: Int): String {
        if (page <= 1) return base
        return base.removeSuffix(".html") + "/$page.html"
    }

    private fun hasNextPage(doc: Document, page: Int, raw: List<RawEntry>): Boolean {
        if (raw.isEmpty()) return false
        val total = Regex("""Page\s+\d+\s+of\s+(\d+)""", RegexOption.IGNORE_CASE)
            .find(doc.body().text())?.groupValues?.get(1)?.toIntOrNull()
        if (total != null) return page < total
        return raw.isNotEmpty()
    }

    private fun field(doc: Document, label: String): String {
        val row = doc.select("div.movie").firstOrNull { it.text().contains(label) } ?: return ""
        return row.text().substringAfter(":").trim()
    }

    private fun isSeriesTitle(title: String): Boolean {
        return Regex("""(?i)(season|\bepisodes?\b|\beps?\b|web.?series|all.?parts|complete.?set|trilogy|collection)""").containsMatchIn(title)
    }

    private fun seasonNumber(title: String): Int {
        return Regex("""(?i)(?:season|se|s)\s*0*(\d{1,2})""").find(title)?.groupValues?.get(1)?.toIntOrNull() ?: 1
    }

    private fun episodeNumber(title: String): Int {
        return Regex("""(?i)(?:episode|eps?)\s*\(?\s*0*(\d{1,3})""").find(title)?.groupValues?.get(1)?.toIntOrNull() ?: 1
    }

    private fun extractYear(text: String): Int? {
        return Regex("""(19\d{2}|20\d{2})""").find(text)?.value?.toIntOrNull()
    }

    private fun cleanTitle(title: String): String {
        var s = title.replace(Regex("""\([^)]*\)"""), " ")
        val marker = Regex("""(?i)\b(se\d+|s\d{1,2}|season|episode|eps?)\b""").find(s)
        if (marker != null) s = s.substring(0, marker.range.first)
        val stop = setOf(
            "telugu", "tamil", "hindi", "dubbed", "org", "hdrip", "brrip", "dvdscr", "hdcam",
            "hdts", "hdtc", "hq", "esub", "predvd", "camrip", "hevc", "x264", "x265",
            "single", "part", "multi", "auds", "audios", "audio", "original", "hd", "mp4"
        )
        return s.split(Regex("""\s+""")).filter { it.isNotBlank() && !stop.contains(it.lowercase()) }.joinToString(" ").replace(Regex("""\s+"""), " ").trim()
    }

    private fun qualityFrom(text: String): Int {
        val t = text.lowercase()
        return when {
            t.contains("2160") || t.contains("4k") -> 2160
            t.contains("1080") -> Qualities.P1080.value
            t.contains("720") -> Qualities.P720.value
            t.contains("480") -> Qualities.P480.value
            t.contains("360") -> Qualities.P360.value
            t.contains("320") -> 320
            else -> Qualities.Unknown.value
        }
    }
}
