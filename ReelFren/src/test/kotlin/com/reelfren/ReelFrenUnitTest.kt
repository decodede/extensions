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
    fun probeKeepsCategoriesThatMerelyOverlapTheDefaultFeed() {
        val base = (1..20).map { "i$it" }.toSet()
        val overlapping = (2..21).map { "i$it" }
        assertTrue(ReelFrenProbe.isDistinct(overlapping, base))
    }

    @Test
    fun probeDropsExactDuplicatesAndEmptyFeeds() {
        val base = listOf("1", "2", "3").toSet()
        assertTrue(!ReelFrenProbe.isDistinct(listOf("3", "2", "1"), base))
        assertTrue(!ReelFrenProbe.isDistinct(emptyList(), base))
        assertTrue(ReelFrenProbe.isDistinct(listOf("1", "2", "9"), base))
    }

    @Test
    fun probeSelectUsesVocabularyAndCap() {
        val samples = mapOf(
            "popular" to listOf("1"),
            "discover" to listOf("2"),
            "not-in-vocabulary" to listOf("3")
        )
        assertEquals(listOf("popular", "discover"), ReelFrenProbe.select(samples).map { it.key })
        assertEquals(1, ReelFrenProbe.select(samples, cap = 1).size)
    }

    @Test
    fun vocabularyContainsSiteObservedKeys() {
        val keys = ReelFrenProbe.candidates.map { it.key }.toSet()
        for (key in listOf("discover", "heartbeat", "theater", "trending", "now", "more", "picks")) {
            assertTrue("missing $key", keys.contains(key))
        }
        assertTrue(!keys.contains("all"))
        assertTrue(!keys.contains("home"))
    }

    @Test
    fun cloudflareChallengeDetection() {
        assertTrue(ReelFrenCf.isChallenge("<title>Just a moment...</title>"))
        assertTrue(ReelFrenCf.isChallenge("<div>Checking your browser before accessing"))
        assertTrue(ReelFrenCf.isChallenge("cf_chl_opt"))
        assertTrue(!ReelFrenCf.isChallenge("""{"data":[]}"""))
    }

    @Test
    fun cloudflareHostExtraction() {
        assertEquals("api.reelfren.com", ReelFrenCf.host("https://api.reelfren.com/api/home?x=1"))
        assertEquals("reelfren.com", ReelFrenCf.host("https://reelfren.com/melolo|1"))
        assertEquals("", ReelFrenCf.host("not a url"))
    }

    @Test
    fun probeKeepsOnlyDistinctCategories() {
        val base = listOf("1", "2", "3").toSet()
        val found = mapOf(
            "popular" to listOf("9", "8"),
            "trending" to listOf("1", "2", "3"),
            "hot" to listOf("7"),
            "empty" to listOf()
        )
        val kept = found.filter { ReelFrenProbe.isDistinct(it.value, base) }
        assertEquals(listOf("popular", "hot"), kept.keys.toList())
    }

    @Test
    fun candidateSeedMatchesVerifiedKeys() {
        val keys = ReelFrenProbe.candidates.map { it.key }
        assertEquals("duplicate keys in vocabulary", keys.size, keys.toSet().size)
        assertTrue("vocabulary too small: ${keys.size}", keys.size >= 100)
        assertEquals(listOf("popular", "trending", "discover"), keys.take(3))
        assertTrue(keys.containsAll(listOf("hot", "new", "ranked", "top-rated", "anime", "drama")))
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
