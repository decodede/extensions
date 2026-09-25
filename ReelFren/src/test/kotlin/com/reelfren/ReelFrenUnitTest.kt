package com.reelfren

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReelFrenUnitTest {
    @Test
    fun parsesPublicSiteUrlFromDeviceLog() {
        val parsed = ReelFrenUrl.parse("https://reelfren.com/movieboxshorts|5493769893436705664")
        assertEquals("movieboxshorts", parsed.first)
        assertEquals("5493769893436705664", parsed.second)
        assertEquals(1, parsed.third)
    }

    @Test
    fun parsesSiteUrlWithEpisode() {
        val parsed = ReelFrenUrl.parse("https://reelfren.com/melolo|7679002393698110469|4")
        assertEquals("melolo", parsed.first)
        assertEquals("7679002393698110469", parsed.second)
        assertEquals(4, parsed.third)
    }

    @Test
    fun parsesBareAndInternalForms() {
        assertEquals("wetv", ReelFrenUrl.parse("wetv|12345").first)
        assertEquals("wetv", ReelFrenUrl.parse("https://reelfren.com/wetv|12345?lang=en").first)
        assertTrue(ReelFrenUrl.parse("https://reelfren.com/").first.isEmpty())
        assertTrue(ReelFrenUrl.parse(null).first.isEmpty())
        assertTrue(ReelFrenUrl.parse("https://reelfren.com/melolo").first.isEmpty())
    }

    @Test
    fun buildsSiteUrls() {
        assertEquals("https://reelfren.com/anamana|abc", ReelFrenUrl.page("anamana", "abc"))
        assertEquals("https://reelfren.com/anamana|abc|3", ReelFrenUrl.episode("anamana", "abc", 3))
    }

    @Test
    fun parsesMainPageData() {
        assertEquals("wetv" to "popular", ReelFrenUrl.parseMain("wetv|popular"))
        assertEquals("wetv" to "", ReelFrenUrl.parseMain("wetv|"))
        assertEquals("" to "", ReelFrenUrl.parseMain("https://reelfren.com/wetv|abc"))
    }

    @Test
    fun probeKeepsOnlyDistinctCategories() {
        val base = setOf("1", "2", "3")
        val found = mapOf(
            "popular" to listOf("9", "8"),
            "trending" to listOf("1", "2", "3"),
            "hot" to listOf("7"),
            "empty" to listOf()
        )
        val selected = ReelFrenProbe.select(base, found, cap = 10).map { it.key }
        assertEquals(listOf("popular", "hot"), selected)
    }

    @Test
    fun probeCapsRowsAndKeepsPriorityOrder() {
        val base = setOf("1")
        val found = ReelFrenProbe.candidates.associate { it.key to listOf("x" + it.key) }
        val selected = ReelFrenProbe.select(base, found, cap = 4).map { it.key }
        assertEquals(ReelFrenProbe.candidates.take(4).map { it.key }, selected)
    }

    @Test
    fun probeReturnsNothingWhenProviderIgnoresEveryKey() {
        val base = setOf("1", "2")
        val found = ReelFrenProbe.candidates.associate { it.key to listOf("1", "2") }
        assertTrue(ReelFrenProbe.select(base, found).isEmpty())
    }

    @Test
    fun candidateSeedMatchesVerifiedKeys() {
        val expected = listOf(
            "popular", "trending", "hot", "new", "top-rated", "top-searched",
            "rising-fast", "ranked", "monthly-trending", "anime", "drama",
            "original", "recommended", "top", "shorts", "latest", "all", "home"
        )
        assertEquals(expected, ReelFrenProbe.candidates.map { it.key })
    }

    @Test
    fun categoryLabelsAreHumanReadable() {
        assertEquals("Home", ReelFrenNames.prettify(""))
        assertEquals("Top Searched", ReelFrenNames.prettify("top-searched"))
        assertEquals("Monthly Trending", ReelFrenProbe.label("monthly-trending"))
        assertEquals("Melolo", ReelFrenNames.display("melolo"))
        assertEquals("RapidTV 2", ReelFrenNames.display("rapidtv2"))
        assertEquals("Brand New", ReelFrenNames.display("brand-new"))
    }

    @Test
    fun qualityMapping() {
        assertEquals(1080, ReelFrenQuality.of("1080p"))
        assertEquals(720, ReelFrenQuality.of("720p H.265"))
        assertEquals(2160, ReelFrenQuality.of("4K"))
        assertEquals(400, ReelFrenQuality.of("Auto"))
    }

    @Test
    fun normalizesApiBase() {
        assertEquals("https://api.reelfren.com", normalizeBase("api.reelfren.com/"))
        assertEquals("http://local:8080", normalizeBase("http://local:8080"))
        assertEquals(null, normalizeBase("  "))
        assertEquals(null, normalizeBase(null))
    }

    @Test
    fun parsesPlaybackQualitiesAndSubtitles() {
        val body = """
            {"title":"T","episodeNumber":2,"totalEpisodes":9,"locked":false,"sourceServer":"1",
             "qualityList":[{"label":"720p","url":"/a.m3u8","format":"hls"},
                            {"label":"1080p","url":"https://cdn/b.mp4","format":"video"}],
             "subtitles":[{"label":"English","srclang":"en","url":"/s.vtt"}]}
        """.trimIndent()
        val play = ReelFrenParse.playback(body)!!
        assertEquals(2, play.qualities.size)
        assertEquals(1, play.subtitles.size)
        assertEquals("en", play.subtitles.first().lang)
        assertEquals("1", play.server)
        assertTrue(!play.locked)
    }

    @Test
    fun skipsLockedAndEmptyPlayback() {
        assertEquals(null, ReelFrenParse.playback("""{"locked":true,"qualityList":[]}"""))
        assertEquals(null, ReelFrenParse.playback("""{"qualityList":[]}"""))
    }
}
