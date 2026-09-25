package com.rulz

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
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

class RulzProvider : MainAPI() {
    override var mainUrl = DEFAULT_BASE
    override var name = "Rulz"
    override val hasMainPage = true
    override var lang = "te"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "/" to "Latest Movies",
        "/language/telugu-dubbed" to "Telugu Dubbed",
        "/category/bollywood-featured" to "Bollywood",
        "/category/malayalam-featured" to "Malayalam"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.trimEnd('/')
        val path = when {
            page <= 1 -> base.ifEmpty { "/" }
            base.isEmpty() -> "/page/$page"
            else -> "$base/page/$page"
        }
        val doc = fetchDocFirst(path)
        if (doc == null) return newHomePageResponse(
            listOf(HomePageList(request.name, emptyList())),
            hasNext = false
        )
        val cards = gridItems(doc)
        return newHomePageResponse(
            listOf(HomePageList(request.name, cards)),
            hasNext = cards.isNotEmpty() && doc.select("a[href*=page/]").any { it.text().contains("Next", true) }
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val e = URLEncoder.encode(q, "UTF-8")
        for (path in listOf("/search_movies?s=" + e, "/?s=" + e)) {
            val items = fetchDocFirst(path)?.let { gridItems(it) } ?: continue
            if (items.isNotEmpty()) return items.take(30)
        }
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = fetchDocFirst(url) ?: return null
        val rawTitle = doc.selectFirst("h2.entry-title")?.text()?.trim().orEmpty()
            .ifEmpty { doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty() }
            .ifEmpty { doc.selectFirst("title")?.text()?.trim().orEmpty() }
        val title = cleanTitle(rawTitle).takeIf { it.isNotEmpty() } ?: return null
        if (title == "Unknown") return null
        val poster = doc.selectFirst(".movie-poster-wrapper img")?.attr("src")
            .orEmpty().ifEmpty { doc.selectFirst("img[src*=/uploads/]")?.attr("src").orEmpty() }
            .let { absoluteSiteUrl(it, mainUrl) }
            .ifEmpty { doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim().orEmpty() }
            .takeIf { it.isNotEmpty() }
        val plot = doc.select(".synopsis-section p").map { it.text().trim() }
            .firstOrNull { it.length > 40 }
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
        val genres = infoLinks(doc, "Genre")
        val qualityTag = infoLinks(doc, "Quality").firstOrNull().orEmpty()
        val language = infoLinks(doc, "Language").firstOrNull().orEmpty()
        val tags = (genres + listOf(qualityTag, language)).map { it.trim() }
            .filter { it.isNotEmpty() }.distinct()
        val actors = infoLinks(doc, "Starring")
        val year = yearOf(rawTitle)
        val recommendations = gridItems(doc).filter { it.url != url }.take(12)
        return if (isSeries(rawTitle)) {
            val episode = newEpisode(url) {
                this.name = title
                this.posterUrl = poster
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf(episode)) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags.takeIf { it.isNotEmpty() }
                this.recommendations = recommendations
                addActors(actors)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags.takeIf { it.isNotEmpty() }
                this.recommendations = recommendations
                addActors(actors)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = fetchTextFirst(data) ?: return false
        val doc = try {
            Jsoup.parse(html, mainUrl)
        } catch (error: Exception) {
            RulzResolverLog.failure("Rulz", HostTarget("detail", data), "html-parse", error)
            return false
        }

        val targets = ArrayList<HostTarget>()
        extractPlayerUrls(html, data).filter(::isPublicHttp).forEachIndexed { index, url ->
            if (targets.none { it.url == url }) targets.add(HostTarget("Player ${index + 1}", url))
        }
        for (anchor in doc.select("a.stream-link-btn")) {
            val href = absoluteSiteUrl(anchor.attr("href"), mainUrl)
            if (!isPublicHttp(href)) continue
            val label = anchor.selectFirst(".platform-name")?.text()?.trim().orEmpty()
                .ifEmpty { anchor.text().replace("▶", "").trim() }
                .ifEmpty { "Watch" }
            if (targets.none { it.url == href }) targets.add(HostTarget(label, href))
        }
        if (targets.isEmpty()) return false

        val found = targets.amap { target ->
            val targetContext = RulzResolverContext()
            val resolved = withTimeoutOrNull(90_000L) {
                RulzResolverRouter.resolve(target, data, targetContext)
            }
            if (resolved == null) RulzResolverLog.empty("Rulz", target, "resolver-timeout")
            resolved ?: emptyList()
        }.flatten().distinctBy { "${it.label}\u0000${it.url}" }
        val aggregateTarget = HostTarget("loadLinks", data)
        if (found.isEmpty()) {
            RulzResolverLog.empty("Rulz", aggregateTarget, "all-targets-empty")
            return false
        }
        RulzResolverLog.success("Rulz", aggregateTarget, found.size)

        found.sortedBy { it.rank }.forEach { stream ->
            callback(
                newExtractorLink("Rulz", "[MRZ] ${stream.label}", stream.url, streamType(stream.url)) {
                    this.referer = stream.referer
                    this.quality = qualOf(stream.label, stream.url)
                    this.headers = stream.headers
                }
            )
        }
        return true
    }

    private suspend fun fetchDocFirst(pathOrUrl: String, referer: String? = null): Document? {
        val html = fetchTextFirst(pathOrUrl, referer) ?: return null
        return try {
            Jsoup.parse(html, mainUrl)
        } catch (error: Exception) {
            RulzResolverLog.failure("Rulz", HostTarget("catalog", pathOrUrl), "html-parse", error)
            null
        }
    }

    private suspend fun fetchTextFirst(pathOrUrl: String, referer: String? = null): String? {
        for (candidate in candidates(pathOrUrl)) {
            val body = fetchRulzText(candidate, referer, "catalog") ?: continue
            if (body.isNotBlank() && !isChallengePage(body)) return body
        }
        RulzResolverLog.empty("Rulz", HostTarget("catalog", pathOrUrl), "all-candidates-failed")
        return null
    }

    private fun candidates(pathOrUrl: String): List<String> {
        val clean = pathOrUrl.trim()
        if (!clean.startsWith("http://", ignoreCase = true) && !clean.startsWith("https://", ignoreCase = true)) {
            val path = if (clean.startsWith("/")) clean else "/$clean"
            return listOf(mainUrl.trimEnd('/') + path)
        }
        return listOf(clean)
    }

    internal fun gridItems(doc: Document): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()
        for (box in doc.select("div.boxed.film")) {
            val anchor = box.selectFirst("a[href$=.html]") ?: continue
            val href = absoluteSiteUrl(anchor.attr("href"), mainUrl)
            if (!isPublicHttp(href) || !seen.add(href)) continue
            val img = box.selectFirst("img") ?: anchor.selectFirst("img")
            val posterRaw = img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")?.takeIf { it.isNotBlank() }
                ?: ""
            val poster = if (posterRaw.isEmpty()) "" else absoluteSiteUrl(posterRaw, mainUrl)
            val raw = anchor.attr("title").ifBlank {
                box.selectFirst("p b")?.text().orEmpty().ifBlank { img?.attr("alt").orEmpty() }
            }
            val title = cleanTitle(raw)
            if (title == "Unknown" || title.length < 3) continue
            if (isSeries(raw)) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster
                }.let { out.add(it) }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                }.let { out.add(it) }
            }
        }
        return out
    }

    private fun infoLinks(doc: Document, key: String): List<String> {
        for (p in doc.select("div.movie-info-block p")) {
            val strong = p.selectFirst("strong")?.text().orEmpty()
            if (!strong.contains(key, ignoreCase = true)) continue
            return p.select("a").map { it.text().trim() }.filter { it.isNotEmpty() }
        }
        return emptyList()
    }

    private fun qualOf(vararg parts: String): Int {
        val t = parts.joinToString(" ").lowercase()
        return when {
            t.contains("2160") || t.contains("4k") || t.contains("uhd") -> Qualities.P2160.value
            t.contains("1080") -> Qualities.P1080.value
            t.contains("720") -> Qualities.P720.value
            t.contains("480") -> Qualities.P480.value
            t.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun cleanTitle(raw: String): String {
        var t = raw.replace(Regex("\\s+"), " ").trim()
        t = Regex("""(?i)\s+(Full\s+)?Movie\s+Watch\s+Online\s+(Free|HD).*""").replace(t, "").trim()
        t = Regex("""(?i)\s+(DVDScr|DVDRip|HDRip|BRRip|WEBRip|WEB-DL|BluRay|CAMRip|PreDVD)\s+.*""").replace(t, "").trim()
        if (t.isEmpty()) return "Unknown"
        return t
    }

    private fun isSeries(title: String): Boolean {
        return Regex("""\bSeason\s*\d|\bS\d{1,2}E\d|\bEP\s*\d|Episode\s*\d""", RegexOption.IGNORE_CASE)
            .containsMatchIn(title)
    }

    private fun yearOf(s: String): Int? {
        return Regex("""\b(19\d{2}|20\d{2})\b""").find(s)?.value?.toIntOrNull()
    }
}
