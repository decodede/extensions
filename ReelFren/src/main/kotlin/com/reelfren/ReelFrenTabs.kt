package com.reelfren

object ReelFrenTabs {
    fun exploreUrl(slug: String): String =
        REEL_SITE + "/explore?provider=" + ReelFrenClient.query(slug) + "&lang=en"

    private val anchor = Regex("href=\"([^\"]*category=[^\"]*)\"[^>]*>([^<]*)</a>")

    fun parse(html: String, slug: String): List<Category> {
        val found = LinkedHashMap<String, Category>()
        for (match in anchor.findAll(html)) {
            val href = match.groupValues[1].replace("&amp;", "&")
            if (!href.contains("provider=" + slug)) continue
            val key = decode(param(href, "category"))
            val label = unescape(match.groupValues[2]).trim()
            if (key.isEmpty() || label.isEmpty()) continue
            val lower = label.lowercase()
            if (lower == "home" || lower == "all") continue
            if (!found.containsKey(key)) found[key] = Category(key, label)
        }
        return found.values.toList()
    }

    private fun param(href: String, name: String): String {
        val prefix = name + "="
        return href.split("&")
            .firstOrNull { it.startsWith(prefix) || it.startsWith("?" + prefix) }
            ?.substringAfter(prefix)
            .orEmpty()
    }

    private fun decode(value: String): String {
        if (!value.contains('%') && !value.contains('+')) return value
        return runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }

    private fun unescape(value: String): String = value
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")
}
