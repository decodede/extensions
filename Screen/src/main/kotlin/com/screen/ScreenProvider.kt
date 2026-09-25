package com.screen

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray

private data class TsMovie(
    val id: String,
    val title: String,
    val year: String,
    val rating: String,
    val quality: String,
    val genre: String,
    val plot: String,
    val director: String,
    val music: String,
    val production: String,
    val duration: String,
    val actors: String,
    val poster: String,
    val moviePath: String,
    val path360p: String,
    val path480p: String,
    val path720p: String,
    val q360p: String,
    val q480p: String,
    val q720p: String,
    val size360p: String,
    val size480p: String,
    val size720p: String
)

class ScreenProvider : MainAPI() {
    override var mainUrl = DEFAULT_BASE
    override var name = "Screen"
    override val hasMainPage = true
    override var lang = "te"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie)

    companion object {
        const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        const val PAGE_SIZE = 20
        val BLOCKED_IDS = setOf("79601436077", "13297974909")
    }

    @Volatile private var catalog: List<TsMovie>? = null

    override val mainPage = mainPageOf(
        "/movies" to "Latest Movies"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val all = loadCatalog() ?: return newHomePageResponse(
            listOf(HomePageList(request.name, emptyList())),
            hasNext = false
        )
        val from = (page - 1) * PAGE_SIZE
        if (from >= all.size) return newHomePageResponse(
            listOf(HomePageList(request.name, emptyList())),
            hasNext = false
        )
        val to = minOf(from + PAGE_SIZE, all.size)
        val cards = all.subList(from, to).map { it.toSearchResponse() }
        return newHomePageResponse(
            listOf(HomePageList(request.name, cards)),
            hasNext = to < all.size
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val all = loadCatalog() ?: return emptyList()
        return all.filter { m ->
            m.title.lowercase().contains(q) || m.year.contains(q) ||
                m.genre.lowercase().contains(q) || m.actors.lowercase().contains(q) ||
                m.director.lowercase().contains(q)
        }.take(30).map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = Regex("""[?&]id=(\d+)""").find(url)?.groupValues?.get(1) ?: return null
        val m = (loadCatalog() ?: return null).find { it.id == id } ?: return null
        if (m.title.isEmpty()) return null
        val year = m.year.toIntOrNull()
        val tags = (m.genre.split(",").map { it.trim() }.take(3) + m.quality)
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return newMovieLoadResponse(m.title, url, TvType.Movie, url) {
            this.posterUrl = m.poster.takeIf { it.isNotEmpty() }
            this.year = year
            this.plot = m.plot.takeIf { it.isNotEmpty() }
            this.tags = tags.takeIf { it.isNotEmpty() }
            this.duration = parseDuration(m.duration)
            addActors(m.actors.split(",").map { it.trim() }.filter { it.isNotEmpty() })
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = Regex("""[?&]id=(\d+)""").find(data)?.groupValues?.get(1) ?: return false
        val m = (loadCatalog() ?: return false).find { it.id == id } ?: return false
        val referer = mainUrl.trimEnd('/') + "/"
        val headers = mapOf("User-Agent" to UA, "Referer" to referer)
        val seen = HashSet<String>()
        suspend fun emit(raw: String, label: String, quality: Int) {
            val u = raw.trim()
            if (u.isEmpty() || !u.startsWith("http") || !seen.add(u)) return
            callback(
                newExtractorLink(
                    "Screen",
                    "[TS] " + label,
                    u,
                    ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.quality = quality
                    this.headers = headers
                }
            )
        }
        emit(m.path720p, "720p" + sizeSuffix(m.size720p), Qualities.P720.value)
        emit(m.q720p, "720p" + sizeSuffix(m.size720p), Qualities.P720.value)
        emit(m.path480p, "480p" + sizeSuffix(m.size480p), Qualities.P480.value)
        emit(m.q480p, "480p" + sizeSuffix(m.size480p), Qualities.P480.value)
        emit(m.path360p, "360p" + sizeSuffix(m.size360p), Qualities.P360.value)
        emit(m.q360p, "360p" + sizeSuffix(m.size360p), Qualities.P360.value)
        emit(m.moviePath, m.quality.ifEmpty { "HD" }, qualOf(m.quality))
        return seen.isNotEmpty()
    }

    private suspend fun loadCatalog(): List<TsMovie>? {
        catalog?.let { return it }
        val list = try {
            val text = app.get(
                mainUrl.trimEnd('/') + "/movies.json",
                headers = mapOf("User-Agent" to UA)
            ).text
            parseCatalog(text)
        } catch (_: Exception) {
            null
        }
        if (list != null) catalog = list
        return list
    }

    private fun parseCatalog(text: String): List<TsMovie>? {
        return try {
            val arr = JSONArray(text)
            val out = ArrayList<TsMovie>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id").trim()
                if (id.isEmpty() || BLOCKED_IDS.contains(id)) continue
                val title = o.optString("title").trim()
                if (title.isEmpty()) continue
                val q = o.optJSONObject("qualities")
                val sizes = q?.optJSONObject("Sizes")
                out.add(
                    TsMovie(
                        id = id,
                        title = title,
                        year = o.optString("year").trim(),
                        rating = o.optString("rating").trim(),
                        quality = o.optString("quality").trim(),
                        genre = o.optString("genre").trim(),
                        plot = o.optString("plot").trim(),
                        director = o.optString("director").trim(),
                        music = o.optString("music").trim(),
                        production = o.optString("production").trim(),
                        duration = o.optString("duration").trim(),
                        actors = o.optString("actors").trim(),
                        poster = o.optString("imagePath").trim()
                            .ifEmpty { o.optString("pic").trim() },
                        moviePath = o.optString("moviePath").trim(),
                        path360p = o.optString("moviePath360p").trim(),
                        path480p = o.optString("moviePath480p").trim(),
                        path720p = o.optString("moviePath720p").trim(),
                        q360p = q?.optString("Q360p").orEmpty().trim(),
                        q480p = q?.optString("Q480p").orEmpty().trim(),
                        q720p = q?.optString("Q720p").orEmpty().trim(),
                        size360p = sizes?.optString("Q360p").orEmpty().trim(),
                        size480p = sizes?.optString("Q480p").orEmpty().trim(),
                        size720p = sizes?.optString("Q720p").orEmpty().trim()
                    )
                )
            }
            out
        } catch (_: Exception) {
            null
        }
    }

    private fun TsMovie.toSearchResponse(): SearchResponse {
        val url = mainUrl.trimEnd('/') + "/player.html?id=" + id
        val label = year.ifEmpty { quality }.let {
            if (it.isNotEmpty()) "$title ($it)" else title
        }
        return newMovieSearchResponse(label, url, TvType.Movie) {
            this.posterUrl = poster.takeIf { it.isNotEmpty() }
        }
    }

    private fun parseDuration(raw: String): Int? {
        val m = Regex("""(\d+)\s*h\s*:?\s*(\d+)\s*m""", RegexOption.IGNORE_CASE).find(raw.trim()) ?: return null
        val h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: return null
        val total = h * 60 + min
        return total.takeIf { it > 0 }
    }

    private fun sizeSuffix(size: String): String {
        val s = size.trim()
        if (s.isEmpty() || s.equals("null", ignoreCase = true)) return ""
        return " [$s]"
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
}
