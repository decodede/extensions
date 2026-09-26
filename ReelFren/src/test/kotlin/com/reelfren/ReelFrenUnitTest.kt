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
    fun parsesProviderTabsFromRealExplorePage() {
        val html = """
            <div class="feed-tabs category-tabs">
            <a class="feed-tab active" href="/explore?provider=dramanova&amp;category=all&amp;lang=en">All</a>
            <a class="feed-tab " href="/explore?provider=dramanova&amp;category=discover&amp;lang=en">Discover</a>
            <a class="feed-tab " href="/explore?provider=dramanova&amp;category=dramanova_new&amp;lang=en">Heartbeat Theater</a>
            <a class="feed-tab " href="/explore?provider=dramanova&amp;category=dramanova_hot&amp;lang=en">Trending Now</a>
            <a class="feed-tab " href="/explore?provider=dramanova&amp;category=dramanova_more&amp;lang=en">More Picks</a>
            </div>
        """.trimIndent()
        val tabs = ReelFrenTabs.parse(html, "dramanova")
        assertEquals(
            listOf("discover", "dramanova_new", "dramanova_hot", "dramanova_more"),
            tabs.map { it.key }
        )
        assertEquals("Heartbeat Theater", tabs[1].label)
        assertEquals("Trending Now", tabs[2].label)
    }

    @Test
    fun parsesEncodedTabKeys() {
        val html = """
            <a href="/explore?provider=vibeshort&amp;category=tab%3A326&amp;lang=en">For You</a>
            <a href="/explore?provider=netshort&amp;category=kind%3Dtab%26tabId%3D123&amp;lang=en">Asian</a>
        """.trimIndent()
        val tabs = ReelFrenTabs.parse(html, "vibeshort")
        assertEquals("tab:326", tabs.single().key)
        val netshort = ReelFrenTabs.parse(html, "netshort")
        assertEquals("kind=tab&tabId=123", netshort.single().key)
    }

    @Test
    fun ignoresTabsFromOtherProviders() {
        val html = """
            <a href="/explore?provider=wetv&amp;category=10071&amp;lang=en">Anime</a>
            <a href="/explore?provider=dramanova&amp;category=discover&amp;lang=en">Discover</a>
        """.trimIndent()
        val tabs = ReelFrenTabs.parse(html, "dramanova")
        assertEquals(listOf("discover"), tabs.map { it.key })
    }

    @Test
    fun unescapesHtmlEntitiesInLabels() {
        val html = """<a href="/explore?provider=candyjar&amp;category=mafia&amp;lang=en">Mafia, Gangs, &amp; Trouble</a>"""
        assertEquals("Mafia, Gangs, & Trouble", ReelFrenTabs.parse(html, "candyjar").single().label)
    }

    @Test
    fun exploreUrlTargetsTheSite() {
        assertEquals(
            "https://www.reelfren.com/explore?provider=dramanova&lang=en",
            ReelFrenTabs.exploreUrl("dramanova")
        )
    }

    @Test
    fun categoryLabelsAreHumanReadable() {
        assertEquals("Melolo", ReelFrenNames.display("melolo"))
        assertEquals("RapidTV 2", ReelFrenNames.display("rapidtv2"))
        assertEquals("Brand New", ReelFrenNames.display("brand-new"))
    }

    @Test
    fun firstPageReturnsTheWholeCatalog() {
        for (size in listOf(20, 95, 326, 499)) {
            val items = (1..size).map { "item$it" }
            val (window, hasMore) = ReelFrenPaging.first(items)
            assertEquals("catalog of $size must be fully visible", size, window.size)
            assertEquals("item1", window.first())
            assertTrue("a $size item catalog fits one page", !hasMore)
        }
    }

    @Test
    fun firstPageSignalsOverflowOnlyForHugeFeeds() {
        val items = (1..1200).map { "item$it" }
        val (window, hasMore) = ReelFrenPaging.first(items)
        assertEquals(ReelFrenPaging.PAGE_SIZE, window.size)
        assertTrue("1200 items must advertise more", hasMore)
        val (second, more2) = ReelFrenPaging.slice(items, 2)
        assertEquals("item501", second.first())
        assertTrue(more2)
        assertTrue("pages must not overlap", (window.toSet() intersect second.toSet()).isEmpty())
    }

    @Test
    fun pagedMergeDeduplicatesAndReportsNewItems() {
        val collected = LinkedHashMap<String, String>()
        assertEquals(3, ReelFrenPaging.mergeInto(collected, listOf("a", "b", "c")) { it })
        assertEquals(0, ReelFrenPaging.mergeInto(collected, listOf("a", "b", "c")) { it })
        assertEquals(2, ReelFrenPaging.mergeInto(collected, listOf("c", "d", "e")) { it })
        assertEquals(listOf("a", "b", "c", "d", "e"), collected.values.toList())
        assertEquals(0, ReelFrenPaging.mergeInto(collected, emptyList<String>()) { it })
        assertEquals(0, ReelFrenPaging.mergeInto(collected, listOf("", "")) { it })
    }

    @Test
    fun pagedMergePreservesFirstSeenOrder() {
        val collected = LinkedHashMap<String, String>()
        ReelFrenPaging.mergeInto(collected, listOf("z", "y", "x")) { it }
        ReelFrenPaging.mergeInto(collected, listOf("y", "w")) { it }
        assertEquals(listOf("z", "y", "x", "w"), collected.values.toList())
    }

    @Test
    fun paginationHandlesEdgesAndEmptyFeeds() {
        val (past, pastMore) = ReelFrenPaging.slice(listOf("a"), 2)
        assertTrue(past.isEmpty())
        assertTrue(!pastMore)
        val (invalid, invalidMore) = ReelFrenPaging.slice(listOf("a"), 0)
        assertTrue(invalid.isEmpty())
        assertTrue(!invalidMore)
        val (empty, emptyMore) = ReelFrenPaging.slice(emptyList<String>(), 1)
        assertTrue(empty.isEmpty())
        assertTrue(!emptyMore)
    }

    @Test
    fun mainPageDataRoundTripsEveryTabKey() {
        val html = """<a href="/explore?provider=dramanova&amp;category=dramanova_hot&amp;lang=en">Trending Now</a>"""
        for (tab in ReelFrenTabs.parse(html, "dramanova")) {
            val decoded = ReelFrenUrl.parseMain("dramanova|" + tab.key)
            assertEquals("dramanova", decoded.first)
            assertEquals(tab.key, decoded.second)
        }
        val encoded = ReelFrenTabs.parse(
            """<a href="/explore?provider=vibeshort&amp;category=tab%3A326&amp;lang=en">For You</a>""",
            "vibeshort"
        )
        assertEquals("tab:326", ReelFrenUrl.parseMain("vibeshort|" + encoded.single().key).second)
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
