package com.reelfren

import org.json.JSONObject

data class CatalogEntry(val slug: String, val name: String)
data class Category(val key: String, val label: String)

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

object ReelFrenCatalog {
    val providers = listOf(
        CatalogEntry("anamana", "Anamana"),
        CatalogEntry("aniboy", "AniBoy"),
        CatalogEntry("melolo", "Melolo"),
        CatalogEntry("sereal", "Sereal+"),
        CatalogEntry("pinedrama", "PineDrama"),
        CatalogEntry("shorten", "Shorten"),
        CatalogEntry("happyshort", "HappyShort"),
        CatalogEntry("vigloo", "Vigloo"),
        CatalogEntry("rapidtv", "RapidTV"),
        CatalogEntry("rapidtv2", "RapidTV 2"),
        CatalogEntry("raptdrama", "RaptDrama"),
        CatalogEntry("cubetv", "CubeTV"),
        CatalogEntry("joyreels", "JoyReels"),
        CatalogEntry("reelife", "Reelife"),
        CatalogEntry("reelshort", "ReelShort"),
        CatalogEntry("dramabox", "DramaBox"),
        CatalogEntry("dramawave", "DramaWave"),
        CatalogEntry("dramanova", "DramaNova"),
        CatalogEntry("kalostv", "KalosTV"),
        CatalogEntry("vibeshort", "VibeShort"),
        CatalogEntry("freereels", "FreeReels"),
        CatalogEntry("wetv", "WeTV"),
        CatalogEntry("storyreel", "StoryReel"),
        CatalogEntry("moviebox", "MovieBox"),
        CatalogEntry("movieboxshorts", "MovieBox Shorts"),
        CatalogEntry("bonustv", "BonusTV"),
        CatalogEntry("moboreels", "MoboReels"),
        CatalogEntry("netshort", "NetShort"),
        CatalogEntry("mydrama", "MyDrama"),
        CatalogEntry("flareflow", "FlareFlow"),
        CatalogEntry("shortmax", "ShortMax"),
        CatalogEntry("flextv", "FlexTV"),
        CatalogEntry("flextv2", "FlexTV 2"),
        CatalogEntry("candyjar", "CandyJar"),
        CatalogEntry("blinkdrama", "BlinkDrama"),
        CatalogEntry("iqiyi", "iQIYI"),
        CatalogEntry("filmbox", "FilmBox")
    )
    val bySlug = providers.associateBy { it.slug }

    fun categories(slug: String): List<Category> = when (slug) {
        "anamana" -> listOf(
            Category("home", "Home"),
            Category("new", "New"),
            Category("rankings", "Rankings"),
            Category("fantasy", "Fantasy"),
            Category("romance", "Romance"),
            Category("revenge", "Revenge")
        )
        "kalostv" -> listOf(
            Category("all", "All"),
            Category("popular", "Popular"),
            Category("new", "New"),
            Category("anime", "Anime"),
            Category("monthly-trending", "Monthly Trending"),
            Category("top-searched", "Top Searched"),
            Category("rising-fast", "Rising Fast")
        )
        else -> listOf(Category("all", "All"), Category("popular", "Popular"), Category("new", "New"))
    }

    fun categoryLabel(slug: String, key: String): String {
        return categories(slug).firstOrNull { it.key == key }?.label
            ?: key.split("-").joinToString(" ") { it.replaceFirstChar(Char::titlecase) }
    }

    fun displayName(slug: String): String {
        bySlug[slug]?.let { return it.name }
        return slug.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
            .joinToString(" ") { it.replaceFirstChar(Char::titlecase) }
            .ifEmpty { slug }
    }
}

object ReelFrenCodec {
    fun encodeMainData(slug: String, feed: String): String = slug + "|" + feed
    fun decodeMainData(data: String): Pair<String, String> {
        val parts = data.split("|")
        return Pair(parts.getOrElse(0) { "" }, parts.getOrElse(1) { "" })
    }
    fun encodeLoadData(slug: String, id: String): String = slug + "|" + id
    fun decodeLoadData(data: String): Pair<String, String> {
        val i = data.indexOf("|")
        if (i < 0) return Pair("", data)
        return Pair(data.substring(0, i), data.substring(i + 1))
    }
    fun encodeEpisodeData(slug: String, id: String, episode: Int): String =
        slug + "|" + id + "|" + episode
    fun decodeEpisodeData(data: String): Triple<String, String, Int> {
        val parts = data.split("|")
        return Triple(
            parts.getOrElse(0) { "" },
            parts.getOrElse(1) { "" },
            parts.getOrElse(2) { "1" }.toIntOrNull() ?: 1
        )
    }
    fun encodeFeeds(feeds: Map<String, List<String>>): String =
        feeds.entries.joinToString(";") { it.key + ":" + it.value.joinToString(",") }
    fun decodeFeeds(raw: String): Map<String, List<String>> {
        if (raw.isBlank()) return emptyMap()
        return raw.split(";").mapNotNull {
            val i = it.indexOf(":")
            if (i < 0) return@mapNotNull null
            it.substring(0, i) to it.substring(i + 1).split(",")
        }.toMap()
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
        val arr = runCatching { JSONObject(body).optJSONArray("data") } .getOrNull() ?: return emptyList()
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
