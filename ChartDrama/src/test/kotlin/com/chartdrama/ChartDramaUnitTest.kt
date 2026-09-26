package com.chartdrama

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartDramaUnitTest {
    private val seriesJson = """
        {"items":[
          {"slug":"4177/divorced-unmasked","dramaId":39795,"title":"Divorced-UNMASKED",
           "cover":"https://res.chartdrama.com/30/a.png","latestEpisodeLabel":"EP97 TV",
           "likeCount":0,"playCount":53086,"source":30,"updatedAt":"2026-06-11T11:43:10+00:00"},
          {"slug":"3980/taming","dramaId":39797,"title":"TAMING THE GODFATHER",
           "cover":"https://res.chartdrama.com/30/b.png","latestEpisodeLabel":"EP80 TV",
           "likeCount":0,"playCount":49316,"source":30,"updatedAt":"2026-06-11T11:43:35+00:00"},
          {"slug":"","dramaId":1,"title":"","cover":"","latestEpisodeLabel":"","source":30}
        ],"page":1,"limit":3,"total":1039}
    """.trimIndent()

    private val watchJson = """
        {"cover":"https://res.chartdrama.com/30/a.png","createdAt":"2026-06-11T11:43:10Z",
         "dramaId":"39795","embedUrl":"https://static.venixtv.com/videos/4835/1080/1/l86jP0lI3wksSQEa.m3u8",
         "likeCount":0,"slug":"4177/divorced-unmasked","source":30,"sourceBookId":"4177",
         "synopsis":"Ivy hid her identity.","title":"Divorced-UNMASKED",
         "updatedAt":"2026-06-11T11:43:10Z"}
    """.trimIndent()

    @Test
    fun parsesSeriesAndSkipsIncompleteItems() {
        val items = ChartDramaParse.series(seriesJson)
        assertEquals(2, items.size)
        assertEquals("4177/divorced-unmasked", items[0].slug)
        assertEquals("39795", items[0].dramaId)
        assertEquals(30, items[0].source)
        assertEquals(53086, items[0].playCount)
        assertEquals(97, items[0].episodeCount)
    }

    @Test
    fun parsesTotalAndTags() {
        assertEquals(1039, ChartDramaParse.total(seriesJson))
        val tags = ChartDramaParse.tags("""{"items":["Action","Romance","Action"]}""")
        assertEquals(listOf("Action", "Romance"), tags)
        assertTrue(ChartDramaParse.tags("""{"items":[]}""").isEmpty())
    }

    @Test
    fun parsesWatchWithEmbedUrl() {
        val w = ChartDramaParse.watch(watchJson)!!
        assertEquals("Divorced-UNMASKED", w.title)
        assertEquals(30, w.source)
        assertTrue(w.embedUrl.endsWith(".m3u8"))
        assertTrue(w.synopsis.isNotEmpty())
        assertEquals(1080, ChartDramaParse.qualityOf(w.embedUrl))
        assertEquals(0, ChartDramaParse.qualityOf("https://x/video.mp4"))
    }

    @Test
    fun rejectsIncompleteWatch() {
        assertEquals(null, ChartDramaParse.watch("""{"title":"x"}"""))
        assertEquals(null, ChartDramaParse.watch("not json"))
    }

    @Test
    fun buildsApiUrls() {
        assertEquals(
            "https://chartdrama.com/api/series?limit=30&page=2&sources=30",
            ChartDramaApi.seriesUrl(30, 2, 30)
        )
        assertTrue(
            ChartDramaApi.seriesUrl(30, 1, 30, query = "CEO ex").contains("q=CEO+ex")
        )
        assertTrue(
            ChartDramaApi.seriesUrl(30, 1, 30, tag = "Action Romance").contains("tag=Action+Romance")
        )
        assertEquals(
            "https://chartdrama.com/api/random?limit=30&offset=0&sources=47",
            ChartDramaApi.randomUrl(47, 30)
        )
        assertEquals(
            "https://chartdrama.com/api/watch/4177/divorced-unmasked",
            ChartDramaApi.watchUrl("4177/divorced-unmasked")
        )
        assertEquals(
            "https://chartdrama.com/d/4177/divorced-unmasked",
            ChartDramaApi.pageUrl("4177/divorced-unmasked")
        )
    }

    @Test
    fun pagingSlicesAndAdvertisesMore() {
        val items = (1..95).map { "i$it" }
        val (first, more) = ChartDramaPaging.slice(items, 1)
        assertEquals(30, first.size)
        assertTrue(more)
        val (last, moreLast) = ChartDramaPaging.slice(items, 4)
        assertEquals(5, last.size)
        assertTrue(!moreLast)
        val (past, pastMore) = ChartDramaPaging.slice(items, 9)
        assertTrue(past.isEmpty())
        assertTrue(!pastMore)
    }

    @Test
    fun sourceLabelsAreStable() {
        assertEquals("ChartDrama #30", ChartDramaNames.label(30))
        assertEquals("ChartDrama #120", ChartDramaNames.label(120))
    }
}
