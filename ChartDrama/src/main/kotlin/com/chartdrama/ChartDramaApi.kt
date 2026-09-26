package com.chartdrama

import org.json.JSONObject

const val CHARTDRAMA_API = "https://chartdrama.com"
const val CHARTDRAMA_SITE = "https://chartdrama.com"
const val CHARTDRAMA_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"

data class Series(
    val slug: String,
    val dramaId: String,
    val title: String,
    val cover: String,
    val episodeLabel: String,
    val playCount: Int,
    val source: Int
) {
    val episodeCount: Int
        get() = Regex("""(\d+)""").find(episodeLabel)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
}

data class Watch(
    val slug: String,
    val title: String,
    val synopsis: String,
    val cover: String,
    val tags: List<String>,
    val source: Int,
    val embedUrl: String
)

object ChartDramaUrl {
    fun slugOf(raw: String?): String {
        var v = raw?.substringBefore("|")?.trim().orEmpty()
        if (v.isEmpty()) return ""
        v = v.substringBefore("?").substringBefore("#")
        v = v.substringAfter("://", v)
        val slash = v.indexOf('/')
        if (slash >= 0 && v.substring(0, slash).contains(".")) v = v.substring(slash + 1)
        if (v.startsWith("d/")) v = v.removePrefix("d/")
        return v.trim('/')
    }
}

object ChartDramaNames {
    fun label(source: Int): String = "ChartDrama #$source"
}

object ChartDramaApi {
    fun seriesUrl(source: Int, page: Int, limit: Int, query: String = "", tag: String = ""): String {
        val sb = StringBuilder(CHARTDRAMA_API)
        sb.append("/api/series?limit=").append(limit)
        sb.append("&page=").append(page)
        if (source > 0) sb.append("&sources=").append(source)
        if (query.isNotEmpty()) sb.append("&q=").append(enc(query))
        if (tag.isNotEmpty()) sb.append("&tag=").append(enc(tag))
        return sb.toString()
    }

    fun randomUrl(source: Int, limit: Int, offset: Int = 0): String {
        val sb = StringBuilder(CHARTDRAMA_API)
        sb.append("/api/random?limit=").append(limit)
        sb.append("&offset=").append(offset)
        if (source > 0) sb.append("&sources=").append(source)
        return sb.toString()
    }

    fun tagsUrl(limit: Int): String = CHARTDRAMA_API + "/api/tags?limit=" + limit

    fun watchUrl(slug: String): String = CHARTDRAMA_API + "/api/watch/" + slug

    fun seriesUrlFor(slug: String): String = CHARTDRAMA_API + "/api/series?slug=" + enc(slug)

    fun pageUrl(slug: String): String = CHARTDRAMA_SITE + "/d/" + slug

    fun enc(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
}

object ChartDramaParse {
    fun series(body: String): List<Series> {
        val array = runCatching { JSONObject(body).optJSONArray("items") }.getOrNull() ?: return emptyList()
        val out = ArrayList<Series>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val slug = o.optString("slug").trim()
            val title = o.optString("title").trim()
            if (slug.isEmpty() || title.isEmpty()) continue
            out.add(
                Series(
                    slug = slug,
                    dramaId = o.opt("dramaId").toString(),
                    title = title,
                    cover = o.optString("cover").trim(),
                    episodeLabel = o.optString("latestEpisodeLabel").trim(),
                    playCount = o.optInt("playCount"),
                    source = o.optInt("source")
                )
            )
        }
        return out
    }

    fun total(body: String): Int =
        runCatching { JSONObject(body).optInt("total") }.getOrDefault(0)

    fun tags(body: String): List<String> {
        val array = runCatching { JSONObject(body).optJSONArray("items") }.getOrNull() ?: return emptyList()
        val out = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            val t = array.optString(i).trim()
            if (t.isNotEmpty() && !out.contains(t)) out.add(t)
        }
        return out
    }

    fun watch(body: String): Watch? {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val slug = o.optString("slug").trim()
        val title = o.optString("title").trim()
        if (slug.isEmpty() || title.isEmpty()) return null
        val tags = ArrayList<String>()
        val array = o.optJSONArray("tags")
        if (array != null) for (i in 0 until array.length()) {
            val t = array.optString(i).trim()
            if (t.isNotEmpty()) tags.add(t)
        }
        return Watch(
            slug = slug,
            title = title,
            synopsis = o.optString("synopsis").trim(),
            cover = o.optString("cover").trim(),
            tags = tags,
            source = o.optInt("source"),
            embedUrl = o.optString("embedUrl").trim()
        )
    }

    fun qualityOf(embedUrl: String): Int {
        Regex("""/(\d{3,4})/""").find(embedUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
            return when {
                it >= 1080 -> 1080
                it >= 720 -> 720
                it >= 480 -> 480
                it >= 360 -> 360
                else -> 400
            }
        }
        return 0
    }
}
