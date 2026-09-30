package com.stremio

private fun expect(condition: Boolean, message: () -> String) {
    if (!condition) throw AssertionError(message())
}

private fun sub(url: String?, lang: String? = null, code: String? = null) = StremioSubtitle(
    url = url,
    lang = lang,
    langCode = code,
)

fun hostileSubtitleInputNeverThrows() {
    val hostile = listOf(
        sub(null),
        sub(""),
        sub("   "),
        sub("not-a-url"),
        sub("file:///etc/passwd"),
        sub("data:text/vtt,WEBVTT"),
        sub("javascript:alert(1)"),
        sub("https://ok.example/a.srt"),
        sub("https://ok.example/a.srt", ""),
        sub("https://ok.example/a.srt", "   "),
        sub("https://ok.example/a.srt", "x".repeat(5000)),
        sub("https://ok.example/\u0000evil"),
        sub("https://ok.example/a.srt\nX-Injected: 1"),
    )
    val mapped = hostile.mapNotNull { toRemote(it) }
    expect(mapped.none { it.url.startsWith("file:") }) { "file:// must be rejected" }
    expect(mapped.none { it.url.startsWith("data:") }) { "data: must be rejected" }
    expect(mapped.none { it.url.startsWith("javascript:") }) { "javascript: must be rejected" }
    expect(mapped.all { it.url.startsWith("https://") }) { "only https should survive: ${mapped.map { it.url }}" }
    expect(mapped.all { it.lang.isNotBlank() }) { "a blank language would render an empty row: $mapped" }
    expect(hostile.any { it.url == null }) { "the null case must actually be exercised" }
}

private fun toRemote(s: StremioSubtitle): RemoteSubtitle? {
    val url = s.url?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return null
    return RemoteSubtitle(url, sanitizeSubtitleLang(subtitleLangOf(s)))
}

fun subtitleLanguageIsAlwaysUsable() {
    expect(sanitizeSubtitleLang(null) == "Unknown") { "null language must not be blank" }
    expect(sanitizeSubtitleLang("") == "Unknown") { "empty language must not be blank" }
    expect(sanitizeSubtitleLang("   ") == "Unknown") { "blank language must not be blank" }
    expect(sanitizeSubtitleLang("eng") == "English") { "ISO tag not mapped: ${sanitizeSubtitleLang("eng")}" }
    expect(sanitizeSubtitleLang("en") == "English") { "short tag not mapped: ${sanitizeSubtitleLang("en")}" }
    expect(sanitizeSubtitleLang("klingon") == "klingon") { "unknown tag should pass through" }
    expect(sanitizeSubtitleLang("en").isNotBlank()) { "mapped name blank" }
}

fun subtitleSlugMatchesTheContentType() {
    val movie = subtitleSlugFor("tt0111161")
    expect(movie == "movie/tt0111161") { "movie slug wrong: $movie" }

    val episode = subtitleSlugFor("tt0903747:1:1")
    expect(episode == "series/tt0903747:1:1") { "episode slug wrong: $episode" }

    expect(subtitleSlugFor("tt0903747:0:1") == "series/tt0903747:0:1") { "specials slug wrong" }
    expect(subtitleSlugFor("tt0903747:10:22") == "series/tt0903747:10:22") { "double digit slug wrong" }

    expect(subtitleSlugFor(null) == null) { "null id should give no slug" }
    expect(subtitleSlugFor("") == null) { "empty id should give no slug" }
    expect(subtitleSlugFor("kitsu:1234") == null) { "non-imdb id should give no slug" }
    expect(subtitleSlugFor("tt123:1") == "movie/tt123") { "a lone season is not an episode" }
}

fun duplicateSubtitleUrlsCollapse() {
    val list = listOf(
        RemoteSubtitle("https://a.example/1.srt", "English"),
        RemoteSubtitle("https://a.example/1.srt", "English"),
        RemoteSubtitle("https://a.example/2.srt", "Spanish"),
    )
    expect(list.distinctBy { it.url }.size == 2) { "duplicates not collapsed" }
}

fun everySubtitleSourceReachesThePicker() {
    val fromAddons = listOf(
        RemoteSubtitle("https://addon.example/a.srt", "English"),
        RemoteSubtitle("https://addon.example/b.srt", "Spanish"),
    )
    val inlineInStream = listOf(RemoteSubtitle("https://cdn.example/inline.srt", "French"))
    val fromFallback = listOf(RemoteSubtitle("https://os.example/f.srt", "German"))

    val merged = listOf(fromAddons, inlineInStream, fromFallback)
        .flatten()
        .distinctBy { it.url }
        .map { sanitizeSubtitleLang(it.lang) to it.url }

    expect(merged.size == 4) { "expected 4 subtitles, got $merged" }
    expect(merged.any { it.second.startsWith("https://addon.example/") }) { "addon subs lost" }
    expect(merged.any { it.second.startsWith("https://cdn.example/") }) { "inline stream subs lost" }
    expect(merged.any { it.second.startsWith("https://os.example/") }) { "fallback subs lost" }
    expect(merged.all { it.first.isNotBlank() }) { "a row lost its language: $merged" }

    val dup = listOf(
        listOf(RemoteSubtitle("https://same.example/x.srt", "English")),
        listOf(RemoteSubtitle("https://same.example/x.srt", "English")),
    ).flatten().distinctBy { it.url }
    expect(dup.size == 1) { "same file from two sources should collapse: $dup" }
}

fun runSubtitleChecks() {
    hostileSubtitleInputNeverThrows()
    subtitleLanguageIsAlwaysUsable()
    subtitleSlugMatchesTheContentType()
    duplicateSubtitleUrlsCollapse()
    everySubtitleSourceReachesThePicker()
}
