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
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

private data class HostTarget(val label: String, val url: String)

private data class FoundStream(
    val url: String,
    val label: String,
    val rank: Int,
    val referer: String,
    val headers: Map<String, String>
)

class RulzProvider : MainAPI() {
    override var mainUrl = DEFAULT_BASE
    override var name = "Rulz"
    override val hasMainPage = true
    override var lang = "te"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    companion object {
        const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        val WISH_RULE_HOSTS = setOf("dhcplay.com", "hglink.to", "hgcloud.to")
        val WISH_RULE_MIRRORS = listOf(
            "https://hanerix.com",
            "https://audinifer.com",
            "https://vibuxer.com",
            "https://masukestin.com",
            "https://streamhls.to",
            "https://wishfast.top"
        )
        val WISH_DMCA_MIRRORS = listOf(
            "https://hgplaycdn.com",
            "https://hglamioz.com",
            "https://niramirus.com",
            "https://playnixes.com",
            "https://medixiru.com",
            "https://streamwish.to",
            "https://strwish.xyz",
            "https://hlswish.com",
            "https://kswplayer.info",
            "https://nekowish.my.id",
            "https://multimovies.cloud",
            "https://katomen.store",
            "https://gradehgplus.com",
            "https://stbhg.click",
            "https://sfastwish.com",
            "https://playerwish.com"
        )
        val LIONS_DEAD = setOf(
            "filelions.com", "filelions.to", "filelions.live", "filelions.xyz",
            "filelions.online", "filelions.site", "filelions.co", "ajmidyadfihayh.sbs",
            "alhayabambi.sbs", "vidhideplus.com", "vidhidepro.com", "vidhidevip.com",
            "vidhidepre.com", "vidhidefun.com", "vidhidefast.com", "azipcdn.com",
            "mlions.pro", "alions.pro", "dlions.pro", "mivalyo.com", "motvy55.store",
            "lumiawatch.top", "fviplions.com", "egsyxutd.sbs", "e4xb5c2xnz.sbs",
            "taylorplayer.com", "ryderjet.com", "techradar.ink", "anime7u.com",
            "coolciima.online", "gsfomqu.sbs", "bingezove.com", "katomen.online",
            "6sfkrspw4u.sbs", "dingtezuni.com", "dinisglows.com", "dintezuvio.com",
            "vidhide.com", "minochinos.com"
        )
        val UPER_TLDS = setOf("net", "io", "com", "cx")
        const val LIONS_FALLBACK_HOST = "callistanise.com"
        val WISH_HOSTS =
            WISH_RULE_HOSTS + (WISH_RULE_MIRRORS + WISH_DMCA_MIRRORS).map { it.substringAfter("://") }
        val LIONS_KNOWN = LIONS_DEAD + LIONS_FALLBACK_HOST
    }

    override val mainPage = mainPageOf(
        "/category/telugu-movies-2026" to "Telugu Movies 2026",
        "/category/telugu-movies-2025" to "Telugu Movies 2025",
        "/language/telugu-dubbed" to "Telugu Dubbed"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = if (page <= 1) request.data else request.data.trimEnd('/') + "/page/" + page
        val doc = fetchDocFirst(path)
        if (doc == null) return newHomePageResponse(
            listOf(HomePageList(request.name, emptyList())),
            hasNext = false
        )
        val cards = gridItems(doc)
        return newHomePageResponse(
            listOf(HomePageList(request.name, cards)),
            hasNext = cards.isNotEmpty() && doc.select("a[href*=page/]").any { it.text().contains("Next") }
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val e = URLEncoder.encode(q, "UTF-8")
        for (path in listOf("/search_movies?s=" + e, "/?s=" + e)) {
            val items = fetchDocFirst(path)?.let { gridItems(it) } ?: continue
            if (items.isNotEmpty()) return items.take(30)
            return emptyList()
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
            .let { absUrl(it) }
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
        } catch (_: Exception) {
            return false
        }
        val targets = ArrayList<HostTarget>()
        for (a in doc.select("a.stream-link-btn")) {
            val href = absUrl(a.attr("href"))
            if (!href.startsWith("http")) continue
            val label = a.selectFirst(".platform-name")?.text()?.trim().orEmpty()
                .ifEmpty { a.text().replace("▶", "").trim() }.ifEmpty { "Watch" }
            if (targets.none { it.url == href }) targets.add(HostTarget(label, href))
        }
        playerUrls(html).forEachIndexed { index, u ->
            if (targets.none { it.url == u }) targets.add(HostTarget("Player " + (index + 1), u))
        }
        if (targets.isEmpty()) return false
        val found = targets.amap { resolveTarget(it, data) }.flatten().distinctBy { it.url }
        if (found.isEmpty()) return false
        found.sortedBy { it.rank }.forEach {
            callback(
                newExtractorLink(
                    "Rulz",
                    "[MRZ] " + it.label,
                    it.url,
                    if (it.url.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = it.referer
                    this.quality = qualOf(it.label, it.url)
                    this.headers = it.headers
                }
            )
        }
        return true
    }

    private suspend fun resolveTarget(t: HostTarget, referer: String): List<FoundStream> {
        val key = (t.label + " " + t.url).lowercase()
        val host = hostOf(t.url)
        return try {
            when {
                key.contains("streamlare") || key.contains("vcdnlare") || key.contains("slmaxed") || key.contains("vcdnx") ->
                    resolveStreamlare(t, referer)
                key.contains("uperbox") -> resolveUperbox(t, referer)
                key.contains("easysyncr") || key.contains("easysync") -> resolveTokenChain(t, referer)
                key.contains("streamvin") || key.contains("fireplayer") || key.contains("playerlare") ->
                    resolveStreamvin(t, referer)
                WISH_HOSTS.contains(host) || key.contains("wish") -> resolveStreamwish(t, referer)
                    .ifEmpty { resolveGeneric(t, referer) }
                LIONS_KNOWN.contains(host) || key.contains("filelion") || key.contains("vidhide") ||
                    key.contains("minochinos") || key.contains("callistanise") || key.contains("morencius") ||
                    key.contains("kinoger") || key.contains("earnvids") || key.contains("/f/") ||
                    key.contains("/v/") || key.contains("/s/") || key.contains("/embed/") ->
                    resolveFilelions(t, referer).ifEmpty { resolveGeneric(t, referer) }
                key.contains("/e/") || key.contains("/d/") -> resolveStreamwish(t, referer)
                    .ifEmpty { resolveGeneric(t, referer) }
                key.contains(".m3u8") || key.contains(".mp4") || key.contains(".mkv") ->
                    listOf(directStream(t, referer))
                else -> resolveGeneric(t, referer)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun resolveStreamlare(t: HostTarget, referer: String): List<FoundStream> {
        val html = fetchTextFirst(t.url, referer) ?: return emptyList()
        val headers = embedHeaders(t.url, referer)
        val out = LinkedHashMap<String, FoundStream>()
        for (m in Regex("""<source[^>]+src="([^"]+)"[^>]*>""").findAll(html)) {
            putStream(out, decodeEntities(m.groupValues[1]), "Auto", t, headers)
        }
        if (out.isNotEmpty()) return out.values.toList()
        val code = html + unpackPacker(html)
        for (m in Regex("""file\s*:\s*["'](https?://[^"']+)["']""").findAll(code)) {
            val u = decodeEntities(m.groupValues[1])
            putStream(out, u, if (u.contains(".m3u8", ignoreCase = true)) "Auto" else "HD", t, headers)
        }
        for (m in Regex("""(https?://[^\s"'<>]+\.(m3u8|mp4)[^\s"'<>]*)""").findAll(code)) {
            val u = decodeEntities(m.groupValues[1])
            putStream(out, u, if (u.contains(".m3u8", ignoreCase = true)) "Auto" else "HD", t, headers)
        }
        return out.values.toList()
    }

    private suspend fun resolveUperbox(t: HostTarget, referer: String): List<FoundStream> {
        val normalized = normalizeUperbox(t.url)
        val chained = resolveTokenChain(HostTarget(t.label, normalized), referer)
        if (chained.isNotEmpty()) return chained
        val landing = fetchTextFirst(normalized, referer) ?: return emptyList()
        val title = pageTitle(landing)
        val hop = Regex("""href="([^"]+)"\s*class="btn""").find(landing)?.groupValues?.get(1)
            ?.let { absAgainst(it, normalized) } ?: return emptyList()
        if (!hop.startsWith("http")) return emptyList()
        val second = fetchTextFirst(hop, normalized) ?: return emptyList()
        val next = Regex("""href="([^"]+)"\s*class="btn""").find(second)?.groupValues?.get(1)
            ?.let { absAgainst(it, hop) } ?: return emptyList()
        if (!isPublicHttp(next)) return emptyList()
        return listOf(
            FoundStream(
                next,
                t.label + sizeSuffix(title),
                qualityRank(parseQuality(title)),
                hop,
                embedHeaders(hop, normalized)
            )
        )
    }

    private suspend fun resolveTokenChain(t: HostTarget, referer: String): List<FoundStream> {
        if (t.url.contains(".m3u8", ignoreCase = true) || t.url.contains(".mp4", ignoreCase = true)) {
            return listOf(directStream(t, referer))
        }
        val landing = fetchTextFirst(t.url, referer) ?: return emptyList()
        val title = pageTitle(landing)
        val dlPage = Regex("""href=["']([^"']*download\?token=[^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(landing)?.groupValues?.get(1)?.let { absAgainst(it, t.url) } ?: return emptyList()
        if (!dlPage.startsWith("http")) return emptyList()
        val page = fetchTextFirst(dlPage, t.url) ?: return emptyList()
        val fileUrl = Regex("""href=["']([^"']*dl\?code=[^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(page)?.groupValues?.get(1)?.let { absAgainst(it, dlPage) } ?: return emptyList()
        if (!isPublicHttp(fileUrl)) return emptyList()
        val measured = Regex("""(\d+(?:\.\d+)?)\s*MB""").find(page)?.groupValues?.get(1)
        return listOf(
            FoundStream(
                fileUrl,
                t.label + sizeSuffix(measured?.let { it + "MB" }.orEmpty().ifEmpty { parseSize(title) }),
                qualityRank(parseQuality(title)),
                dlPage,
                embedHeaders(dlPage, t.url)
            )
        )
    }

    private suspend fun resolveStreamwish(t: HostTarget, referer: String): List<FoundStream> {
        val mediaId = Regex("""/(e|f|d)/([A-Za-z0-9]+)""").find(t.url)?.groupValues?.get(2)
            ?: Regex("""/([A-Za-z0-9]{6,})[/?#]?$""").find(t.url)?.groupValues?.get(1)
            ?: return emptyList()
        val first = fetchWish(t.url, t.label, referer)
        if (first.isNotEmpty()) return first
        val host = hostOf(t.url)
        val mirrors = if (WISH_RULE_HOSTS.contains(host)) WISH_RULE_MIRRORS else WISH_DMCA_MIRRORS
        val results = mirrors.amap { fetchWish(it.trimEnd('/') + "/e/" + mediaId, t.label, referer) }
        return results.firstOrNull { it.isNotEmpty() } ?: emptyList()
    }

    private suspend fun fetchWish(candidate: String, label: String, referer: String): List<FoundStream> {
        var html = fetchTextFirst(candidate, referer) ?: return emptyList()
        if (html.length < 3000 && html.contains("Page is loading")) return emptyList()
        if (!html.contains("eval(function(p,a,c,k,e")) {
            val fileId = Regex("""\$\.cookie\('file_id',\s*'([^']+)'""").find(html)?.groupValues?.get(1).orEmpty()
            if (fileId.isNotEmpty()) {
                val aff = Regex("""\$\.cookie\('aff',\s*'([^']+)'""").find(html)?.groupValues?.get(1).orEmpty()
                val headers = HashMap(embedHeaders(candidate, candidate))
                headers["Cookie"] = "file_id=" + fileId +
                    (if (aff.isNotEmpty()) "; aff=" + aff else "") + "; ref_url=" + hostOf(candidate)
                val second = try {
                    withTimeoutOrNull(15000L) { app.get(candidate, headers = headers, referer = referer).text }
                } catch (_: Exception) {
                    null
                }
                if (second.isNullOrEmpty()) return emptyList()
                html = second
            }
        }
        val headers = embedHeaders(candidate, candidate)
        val code = html + unpackPacker(html)
        val out = LinkedHashMap<String, FoundStream>()
        for (m in Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*["']([^"']+)["']""").findAll(code)) {
            putStream(out, protocolFix(m.groupValues[1]), "HD", HostTarget(label, candidate), headers)
        }
        if (out.isNotEmpty()) return out.values.toList()
        for (key in listOf("hls2", "hls3", "hls4")) {
            val m = Regex(""""$key"\s*:\s*"((?:https?:)?//[^"]+)"\s*[,}]""").find(code) ?: continue
            putStream(out, protocolFix(m.groupValues[1]), "Auto", HostTarget(label, candidate), headers)
            if (out.isNotEmpty()) break
        }
        if (out.isNotEmpty()) return out.values.toList()
        for (m in Regex("""file\s*:\s*["'](https?://[^"']+\.(m3u8|mp4)[^"']*)["']""").findAll(code)) {
            val u = protocolFix(m.groupValues[1])
            putStream(out, u, if (u.contains(".m3u8", ignoreCase = true)) "Auto" else "HD", HostTarget(label, candidate), headers)
        }
        return out.values.toList()
    }

    private suspend fun resolveFilelions(t: HostTarget, referer: String): List<FoundStream> {
        val target = lionsTarget(t.url) ?: return emptyList()
        val html = fetchTextFirst(target, referer) ?: return emptyList()
        val host = hostOf(target)
        val headers = embedHeaders(target, target)
        val code = html + unpackPacker(html)
        val out = LinkedHashMap<String, FoundStream>()
        val links = Regex("""var\s+links\s*=\s*(\{[^}]+\})""").find(code)?.groupValues?.get(1)
        if (links != null) {
            for (key in listOf("hls2", "hls3", "hls4")) {
                val m = Regex(""""$key"\s*:\s*"([^"]+)"\s*[,}]""").find(links) ?: continue
                putStream(out, lionsFix(m.groupValues[1], host), "Auto", t, headers)
                if (out.isNotEmpty()) break
            }
        }
        if (out.isNotEmpty()) return out.values.toList()
        for (m in Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*["']([^"']+)["']""").findAll(code)) {
            putStream(out, lionsFix(m.groupValues[1], host), "Auto", t, headers)
        }
        return out.values.toList()
    }

    private suspend fun resolveStreamvin(t: HostTarget, referer: String): List<FoundStream> {
        val headers = embedHeaders(t.url, t.url)
        val out = LinkedHashMap<String, FoundStream>()
        val id = Regex("/video/([A-Za-z0-9]+)").find(t.url)?.groupValues?.get(1).orEmpty()
        var html = ""
        try {
            val page = withTimeoutOrNull(15000L) { app.get(t.url, referer = referer) }
            html = page?.text.orEmpty()
            if (id.isNotEmpty()) {
                val cookie = page?.cookies?.entries?.joinToString("; ") { it.key + "=" + it.value }.orEmpty()
                val postHeaders = HashMap(headers)
                postHeaders["X-Requested-With"] = "XMLHttpRequest"
                if (cookie.isNotEmpty()) postHeaders["Cookie"] = cookie
                val data = withTimeoutOrNull(15000L) {
                    app.post(
                        originOf(t.url) + "/player/index.php?data=" + id + "&do=getVideo",
                        headers = postHeaders,
                        referer = t.url,
                        data = mapOf("hash" to id, "r" to referer)
                    ).text
                }.orEmpty()
                val secured =
                    Regex(""""securedLink"\s*:\s*"([^"]+)"\s*[,}]""").find(data)?.groupValues?.get(1).orEmpty()
                val source =
                    Regex(""""videoSource"\s*:\s*"([^"]+)"\s*[,}]""").find(data)?.groupValues?.get(1).orEmpty()
                putStream(out, secured.ifEmpty { source }, "Auto", t, headers)
                if (out.isNotEmpty()) return out.values.toList()
            }
        } catch (_: Exception) {
        }
        if (html.isEmpty()) html = fetchTextFirst(t.url, referer) ?: return out.values.toList()
        val code = html + unpackPacker(html)
        for (m in Regex("""file\s*:\s*["'](https?://[^"']+)["']""").findAll(code)) {
            val u = decodeEntities(m.groupValues[1])
            if (u.contains(".vtt", ignoreCase = true)) continue
            putStream(out, u, if (u.contains(".m3u8", ignoreCase = true)) "Auto" else "HD", t, headers)
        }
        for (m in Regex("""(https?://[^\s"'\\]+\.(m3u8|mp4)[^\s"'\\]*)""").findAll(code)) {
            putStream(out, decodeEntities(m.groupValues[1]), "Auto", t, headers)
        }
        return out.values.toList()
    }

    private suspend fun resolveGeneric(t: HostTarget, referer: String): List<FoundStream> {
        val html = fetchTextFirst(t.url, referer) ?: return emptyList()
        val headers = embedHeaders(t.url, t.url)
        val code = html + unpackPacker(html)
        val out = LinkedHashMap<String, FoundStream>()
        for (m in Regex("""file\s*:\s*["'](https?://[^"']+)["']""").findAll(code)) {
            val u = decodeEntities(m.groupValues[1])
            if (u.contains(".vtt", ignoreCase = true)) continue
            putStream(out, u, if (u.contains(".m3u8", ignoreCase = true)) "Auto" else "HD", t, headers)
            if (out.isNotEmpty()) break
        }
        if (out.isNotEmpty()) return out.values.toList()
        for (m in Regex("""(https?://[^\s"'<>]+\.(m3u8|mp4)[^\s"'<>]*)""").findAll(code)) {
            putStream(out, decodeEntities(m.groupValues[1]), "Auto", t, headers)
            if (out.isNotEmpty()) break
        }
        return out.values.toList()
    }

    private fun directStream(t: HostTarget, referer: String): FoundStream {
        val quality = if (t.url.contains(".m3u8", ignoreCase = true)) "Auto" else parseQuality(t.url).ifEmpty { "HD" }
        return FoundStream(t.url, t.label + sizeSuffix(parseSize(t.url)), qualityRank(quality), referer, embedHeaders(t.url, referer))
    }

    private suspend fun fetchDocFirst(pathOrUrl: String, referer: String? = null): Document? {
        val html = fetchTextFirst(pathOrUrl, referer) ?: return null
        return try {
            Jsoup.parse(html, mainUrl)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchTextFirst(pathOrUrl: String, referer: String? = null): String? {
        for (candidate in candidates(pathOrUrl)) {
            val body = getText(candidate, referer) ?: continue
            if (body.isBlank() || body.contains("<title>Just a moment")) continue
            if (body.contains("challenge-platform")) continue
            return body
        }
        return null
    }

    private suspend fun getText(url: String, referer: String?): String? {
        return try {
            withTimeoutOrNull(15000L) {
                val res = if (referer == null) app.get(url) else app.get(url, referer = referer)
                res.text
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun candidates(pathOrUrl: String): List<String> {
        val clean = pathOrUrl.trim()
        if (!clean.startsWith("http")) {
            val path = if (clean.startsWith("/")) clean else "/$clean"
            return listOf(mainUrl.trimEnd('/') + path)
        }
        return listOf(clean)
    }

    private fun gridItems(doc: Document): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()
        for (box in doc.select("div.boxed.film")) {
            val anchor = box.selectFirst("a[href$=.html]") ?: continue
            val href = absUrl(anchor.attr("href"))
            if (href.isEmpty() || !seen.add(href)) continue
            val img = box.selectFirst("img[src*=uploads]") ?: anchor.selectFirst("img[src*=uploads]") ?: continue
            val poster = absUrl(img.attr("src"))
            if (poster.isEmpty()) continue
            val raw = anchor.attr("title").ifEmpty {
                box.selectFirst("p b")?.text().orEmpty().ifEmpty { img.attr("alt") }
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

    private fun playerUrls(html: String): List<String> {
        val m = Regex("""var\s+locations\s*=\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(html)
            ?: return emptyList()
        return m.groupValues[1].split(",")
            .map { it.replace("\\/", "/").replace("\"", "").replace("'", "").trim() }
            .filter { it.startsWith("http") && isPublicHttp(it) }
    }

    private fun putStream(
        out: LinkedHashMap<String, FoundStream>,
        rawUrl: String,
        quality: String,
        t: HostTarget,
        headers: Map<String, String>
    ) {
        val u = protocolFix(decodeEntities(rawUrl))
        if (!isPublicHttp(u)) return
        if (u.contains(".png", ignoreCase = true) || u.contains(".jpg", ignoreCase = true) ||
            u.contains(".jpeg", ignoreCase = true) || u.contains(".gif", ignoreCase = true) ||
            u.contains(".webp", ignoreCase = true) || u.contains(".svg", ignoreCase = true) ||
            u.contains(".css", ignoreCase = true) || u.contains(".js", ignoreCase = true) ||
            u.contains(".vtt", ignoreCase = true) || u.contains(".srt", ignoreCase = true)
        ) return
        if (out.containsKey(u)) return
        out[u] = FoundStream(u, t.label, qualityRank(quality), t.url, headers)
    }

    private fun unpackPacker(html: String): String {
        val src = if (html.length > 500000) html.substring(0, 500000) else html
        val re = Regex("""eval\(function\(p,a,c,k,e,d?\)[\s\S]*?\}\('([\s\S]*?)',(\d+),(\d+),'([\s\S]*?)'\.split\('\|'\)""")
        val sb = StringBuilder()
        var rounds = 0
        for (m in re.findAll(src)) {
            if (rounds++ >= 10) break
            val a = m.groupValues[2].toIntOrNull() ?: continue
            val c = m.groupValues[3].toIntOrNull() ?: continue
            if (a < 2 || a > 62 || c < 0 || c > 2000) continue
            val k = m.groupValues[4].split("|")
            if (k.isEmpty()) continue
            var p = m.groupValues[1]
            for (i in c - 1 downTo 0) {
                val rep = if (i < k.size) k[i] else ""
                if (rep.isEmpty()) continue
                p = Regex("\\b" + baseN(i, a) + "\\b").replace(p) { rep }
            }
            sb.append(p).append('\n')
        }
        return sb.toString()
    }

    private fun baseN(num: Int, base: Int): String {
        if (num == 0) return "0"
        var n = num
        var s = ""
        val alpha = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        while (n > 0 && s.length < 12) {
            s = alpha[n % base] + s
            n /= base
        }
        return s
    }

    private fun normalizeUperbox(pageUrl: String): String {
        return try {
            val u = java.net.URI(pageUrl)
            val parts = u.host.lowercase().split(".")
            if (parts.size == 2 && parts[0] == "uperbox" && UPER_TLDS.contains(parts[1])) {
                "https://www." + u.host + u.path + (u.query?.let { "?$it" }.orEmpty())
            } else pageUrl
        } catch (_: Exception) {
            pageUrl
        }
    }

    private fun lionsTarget(pageUrl: String): String? {
        return try {
            val u = java.net.URI(pageUrl)
            var host = u.host.lowercase()
            if (LIONS_DEAD.contains(host) || LIONS_DEAD.any { host.endsWith(".$it") }) host = LIONS_FALLBACK_HOST
            "https://$host" + u.path + (u.query?.let { "?$it" }.orEmpty())
        } catch (_: Exception) {
            null
        }
    }

    private fun lionsFix(link: String, host: String): String {
        val l = link.trim()
        if (l.startsWith("//")) return "https:$l"
        if (l.startsWith("/")) return "https://$host$l"
        return l
    }

    private fun protocolFix(u: String): String {
        val t = u.trim()
        if (t.startsWith("//")) return "https:$t"
        return t
    }

    private fun decodeEntities(u: String): String {
        return u.replace("\\/", "/").replace("&amp;", "&").trim()
    }

    private fun absAgainst(href: String?, base: String): String {
        val h = href?.replace(Regex("[\\r\\n\\t ]"), "")?.trim().orEmpty()
        if (h.isEmpty()) return ""
        if (h.startsWith("http")) return h
        return try {
            java.net.URI(base).resolve(h).toString()
        } catch (_: Exception) {
            ""
        }
    }

    private fun absUrl(href: String): String {
        val h = href.replace(Regex("[\\r\\n\\t ]"), "").trim().replace("&amp;", "&")
        if (h.isEmpty()) return ""
        if (h.startsWith("http")) return h
        if (h.startsWith("//")) return "https:$h"
        if (h.startsWith("/")) return mainUrl + h
        return mainUrl + "/" + h
    }

    private fun embedHeaders(pageUrl: String, referer: String): Map<String, String> {
        return mapOf(
            "User-Agent" to UA,
            "Referer" to referer,
            "Origin" to originOf(pageUrl),
            "Connection" to "keep-alive"
        )
    }

    private fun originOf(pageUrl: String): String {
        return try {
            val u = java.net.URI(pageUrl)
            u.scheme + "://" + u.host
        } catch (_: Exception) {
            mainUrl
        }
    }

    private fun hostOf(pageUrl: String): String {
        return try {
            java.net.URI(pageUrl).host.lowercase()
        } catch (_: Exception) {
            ""
        }
    }

    private fun isPublicHttp(u: String): Boolean {
        if (!u.startsWith("https://") && !u.startsWith("http://")) return false
        val h = try {
            java.net.URI(u).host.lowercase()
        } catch (_: Exception) {
            return false
        }
        if (h == "localhost" || h.startsWith("127.") || h.startsWith("10.") ||
            h.startsWith("192.168.") || h.startsWith("169.254.") || h == "0.0.0.0" ||
            h == "[::1]" || h.startsWith("fc00:") || h.startsWith("fe80:")
        ) return false
        if (h.startsWith("172.")) {
            val second = h.split(".").getOrNull(1)?.toIntOrNull() ?: return true
            if (second in 16..31) return false
        }
        return true
    }

    private fun pageTitle(html: String): String {
        return Regex("""<title>([^<]+)</title>""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
    }

    private fun parseQuality(s: String): String {
        val t = s.lowercase()
        return when {
            t.contains("2160") || t.contains("4k") || t.contains("uhd") -> "2160p"
            t.contains("1080") -> "1080p"
            t.contains("720") -> "720p"
            t.contains("480") -> "480p"
            else -> ""
        }
    }

    private fun parseSize(s: String): String {
        return Regex("""(\d+(?:\.\d+)?)\s*(mb|gb)""", RegexOption.IGNORE_CASE)
            .find(s)?.let { it.groupValues[1] + it.groupValues[2].uppercase() }.orEmpty()
    }

    private fun sizeSuffix(size: String): String {
        if (size.isEmpty()) return ""
        return " [$size]"
    }

    private fun qualityRank(q: String): Int {
        return when (q.lowercase()) {
            "2160p" -> 0
            "1080p" -> 1
            "720p" -> 2
            "480p" -> 3
            "hd" -> 4
            else -> 5
        }
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
