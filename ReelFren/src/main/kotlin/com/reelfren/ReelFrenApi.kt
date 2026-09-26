package com.reelfren

import org.json.JSONObject

data class HomeItem(
    val id: String,
    val provider: String,
    val title: String,
    val cover: String,
    val intro: String,
    val episodes: Int
)

data class VideoEntry(val episode: Int, val duration: Int, val vid: String, val available: Boolean)

data class DetailInfo(
    val id: String,
    val provider: String,
    val title: String,
    val cover: String,
    val intro: String,
    val episodes: Int,
    val videos: List<VideoEntry>
)

data class QualityEntry(val label: String, val url: String, val format: String)
data class SubtitleEntry(val label: String, val lang: String, val url: String)

data class PlaybackInfo(
    val title: String,
    val episodeNumber: Int,
    val totalEpisodes: Int,
    val locked: Boolean,
    val qualities: List<QualityEntry>,
    val subtitles: List<SubtitleEntry>,
    val server: String
)

data class Category(val key: String, val label: String)

object ReelFrenNames {
    private val names = mapOf(
        "anamana" to "Anamana",
        "aniboy" to "AniBoy",
        "blinkdrama" to "BlinkDrama",
        "bonustv" to "BonusTV",
        "candyjar" to "CandyJar",
        "cubetv" to "CubeTV",
        "dramabox" to "DramaBox",
        "dramanova" to "DramaNova",
        "dramawave" to "DramaWave",
        "filmbox" to "FilmBox",
        "flareflow" to "FlareFlow",
        "flextv" to "FlexTV",
        "flextv2" to "FlexTV 2",
        "freereels" to "FreeReels",
        "happyshort" to "HappyShort",
        "iqiyi" to "iQIYI",
        "joyreels" to "JoyReels",
        "kalostv" to "KalosTV",
        "melolo" to "Melolo",
        "moboreels" to "MoboReels",
        "moviebox" to "MovieBox",
        "movieboxshorts" to "MovieBox Shorts",
        "mydrama" to "MyDrama",
        "netshort" to "NetShort",
        "pinedrama" to "PineDrama",
        "rapidtv" to "RapidTV",
        "rapidtv2" to "RapidTV 2",
        "raptdrama" to "RaptDrama",
        "reelife" to "Reelife",
        "reelshort" to "ReelShort",
        "sereal" to "Sereal+",
        "shorten" to "Shorten",
        "shortmax" to "ShortMax",
        "vigloo" to "Vigloo",
        "vibeshort" to "VibeShort",
        "wetv" to "WeTV"
    )

    fun display(slug: String): String {
        names[slug]?.let { return it }
        return slug.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
            .joinToString(" ") { it.replaceFirstChar(Char::titlecase) }
            .ifEmpty { slug }
    }

    fun prettify(key: String): String {
        if (key.isEmpty()) return "Home"
        return key.split('-').filter { it.isNotEmpty() }
            .joinToString(" ") { it.replaceFirstChar(Char::titlecase) }
    }
}

object ReelFrenProbe {
    const val HOME = ""
    const val CAP = 12
    const val BATCH = 8

    val candidates: List<Category> = listOf(
        "popular", "trending", "discover", "hot", "new", "picks", "now", "more",
        "top", "latest", "best",
        "recommended", "original",
        "top-rated", "top-searched", "rising-fast", "ranked", "ranking",
        "monthly-trending", "theater", "heartbeat", "complete-series",
        "full-series", "coming-soon", "now-playing", "new-episodes",
        "latest-episodes", "just-added", "for-you", "top-picks", "anime",
        "drama", "kdrama", "dubbed", "romance", "revenge", "fantasy", "action",
        "comedy", "shorts", "movies", "tv", "series"
    ).map { Category(it, ReelFrenNames.prettify(it)) }

    private val byKey = candidates.associateBy { it.key }

    fun label(key: String): String = byKey[key]?.label ?: ReelFrenNames.prettify(key)

    fun isDistinct(candidate: List<String>, base: Set<String>): Boolean =
        candidate.isNotEmpty() && candidate.toSet() != base

    fun select(samples: Map<String, List<String>>, cap: Int = CAP): List<Category> {
        val out = ArrayList<Category>(cap)
        for (candidate in candidates) {
            val ids = samples[candidate.key] ?: continue
            if (ids.isEmpty()) continue
            out.add(candidate)
            if (out.size >= cap) break
        }
        return out
    }
}

object ReelFrenUrl {
    fun page(slug: String, id: String): String = REEL_DEFAULT_WEB + "/" + slug + "|" + id

    fun episode(slug: String, id: String, episode: Int): String = page(slug, id) + "|" + episode

    fun parse(raw: String?): Triple<String, String, Int> {
        val input = raw?.trim().orEmpty()
        if (input.isEmpty()) return Triple("", "", 1)
        val path = input.substringAfter("://", input)
        val afterHost = if (path.contains('/')) path.substringAfter('/', "") else path
        val body = afterHost.substringBefore('?').substringBefore('#')
        val parts = body.split("|")
        val slug = parts.getOrElse(0) { "" }.trim().lowercase()
        val id = parts.getOrElse(1) { "" }.trim()
        val episode = parts.getOrElse(2) { "1" }.trim().toIntOrNull() ?: 1
        if (slug.isEmpty() || id.isEmpty()) return Triple("", "", 1)
        return Triple(slug, id, episode)
    }

    fun parseMain(raw: String?): Pair<String, String> {
        val input = raw?.trim().orEmpty()
        if (input.isEmpty()) return Pair("", "")
        if (input.contains("://")) return Pair("", "")
        val parts = input.split("|")
        return Pair(
            parts.getOrElse(0) { "" }.trim().lowercase(),
            parts.getOrElse(1) { "" }.trim()
        )
    }
}

object ReelFrenQuality {
    fun of(label: String): Int {
        Regex("""(?i)(\d{3,4})\s*p\b""").find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
            return when {
                it >= 1080 -> 1080
                it >= 720 -> 720
                it >= 480 -> 480
                it >= 360 -> 360
                else -> 400
            }
        }
        val t = label.lowercase()
        return when {
            t.contains("2160") || t.contains("4k") || t.contains("uhd") -> 2160
            t.contains("1080") -> 1080
            t.contains("720") -> 720
            t.contains("480") -> 480
            t.contains("360") -> 360
            else -> 400
        }
    }
}

object ReelFrenParse {
    fun homeItems(body: String): List<HomeItem> {
        val arr = runCatching { JSONObject(body).optJSONArray("data") }.getOrNull() ?: return emptyList()
        val out = ArrayList<HomeItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").trim()
            val title = o.optString("title").trim()
            if (id.isEmpty() || title.isEmpty()) continue
            out.add(
                HomeItem(
                    id,
                    o.optString("provider").trim(),
                    title,
                    o.optString("cover").trim(),
                    o.optString("intro").trim(),
                    o.optInt("episodes")
                )
            )
        }
        return out
    }

    fun detail(body: String): DetailInfo? {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val id = o.optString("id").trim()
        val title = o.optString("title").trim()
        if (id.isEmpty() || title.isEmpty()) return null
        val videos = ArrayList<VideoEntry>()
        val arr = o.optJSONArray("videos")
        if (arr != null) for (i in 0 until arr.length()) {
            val v = arr.optJSONObject(i) ?: continue
            videos.add(
                VideoEntry(
                    v.optInt("episode", i + 1),
                    v.optInt("duration"),
                    v.optString("vid"),
                    v.optBoolean("available", true)
                )
            )
        }
        return DetailInfo(
            id,
            o.optString("provider").trim(),
            title,
            o.optString("cover").trim(),
            o.optString("intro").trim(),
            o.optInt("episodes", videos.size),
            videos.sortedBy { it.episode }
        )
    }

    fun playback(body: String): PlaybackInfo? {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val qualities = ArrayList<QualityEntry>()
        val arr = o.optJSONArray("qualityList")
        if (arr != null) for (i in 0 until arr.length()) {
            val q = arr.optJSONObject(i) ?: continue
            val url = q.optString("url").trim()
            if (url.isEmpty()) continue
            qualities.add(QualityEntry(q.optString("label").trim(), url, q.optString("format").trim()))
        }
        val direct = o.optString("videoUrl").trim()
        if (direct.isNotEmpty() && qualities.none { it.url == direct }) {
            val format = if (direct.contains(".m3u8", true) || direct.contains(".mpd", true)) "hls" else "video"
            qualities.add(0, QualityEntry("Auto", direct, format))
        }
        val subtitles = ArrayList<SubtitleEntry>()
        val subtitleArray = o.optJSONArray("subtitles")
        if (subtitleArray != null) for (i in 0 until subtitleArray.length()) {
            val s = subtitleArray.optJSONObject(i) ?: continue
            val url = s.optString("url").trim()
            if (url.isEmpty()) continue
            subtitles.add(
                SubtitleEntry(
                    s.optString("label").trim(),
                    s.optString("srclang").trim(),
                    url
                )
            )
        }
        if (qualities.isEmpty()) return null
        return PlaybackInfo(
            o.optString("title").trim(),
            o.optInt("episodeNumber", 1),
            o.optInt("totalEpisodes", 1),
            o.optBoolean("locked"),
            qualities,
            subtitles,
            o.optString("sourceServer", "1").trim().ifEmpty { "1" }
        )
    }
}
