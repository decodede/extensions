package com.reelfren

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReelFrenUnitTest {

    @Test
    fun `catalog holds every site provider exactly once`() {
        val slugs = ReelFrenCatalog.providers.map { it.slug }
        assertEquals(37, slugs.size)
        assertEquals(37, slugs.toSet().size)
        for (slug in listOf("anamana", "kalostv", "rapidtv2", "flextv2", "movieboxshorts", "iqiyi")) {
            assertTrue(slugs.contains(slug))
        }
    }

    @Test
    fun `display names fall back for unknown slugs`() {
        assertEquals("KalosTV", ReelFrenCatalog.displayName("kalostv"))
        assertEquals("Sereal+", ReelFrenCatalog.displayName("sereal"))
        assertEquals("New Provider", ReelFrenCatalog.displayName("new-provider"))
    }

    @Test
    fun `category catalogs preserve site labels`() {
        assertEquals(
            listOf("Home", "New", "Rankings", "Fantasy", "Romance", "Revenge"),
            ReelFrenCatalog.categories("anamana").map { it.label }
        )
        assertEquals(
            listOf("All", "Popular", "New", "Anime", "Monthly Trending", "Top Searched", "Rising Fast"),
            ReelFrenCatalog.categories("kalostv").map { it.label }
        )
        assertEquals("All", ReelFrenCatalog.categories("new-provider")[0].label)
    }

    @Test
    fun `main data round trips`() {
        val encoded = ReelFrenCodec.encodeMainData("kalostv", "popular")
        assertEquals(Pair("kalostv", "popular"), ReelFrenCodec.decodeMainData(encoded))
        assertEquals(Pair("wetv", ""), ReelFrenCodec.decodeMainData("wetv|"))
    }

    @Test
    fun `load data keeps ids containing separators`() {
        val id = "7295930638539411024_five-friends-2-mount-klawih-CLmEFjgrXG8"
        val (slug, back) = ReelFrenCodec.decodeLoadData(ReelFrenCodec.encodeLoadData("filmbox", id))
        assertEquals("filmbox", slug)
        assertEquals(id, back)
    }

    @Test
    fun `episode data round trips`() {
        val (slug, id, ep) = ReelFrenCodec.decodeEpisodeData(
            ReelFrenCodec.encodeEpisodeData("dramawave", "UDWwNbrol6", 12)
        )
        assertEquals("dramawave", slug)
        assertEquals("UDWwNbrol6", id)
        assertEquals(12, ep)
    }

    @Test
    fun `feed cache codec round trips`() {
        val feeds = mapOf("kalostv" to listOf("", "popular"), "wetv" to listOf(""))
        assertEquals(feeds, ReelFrenCodec.decodeFeeds(ReelFrenCodec.encodeFeeds(feeds)))
        assertTrue(ReelFrenCodec.decodeFeeds("").isEmpty())
    }

    @Test
    fun `parsers retain every playback quality`() {
        val body = """
            {"title":"Episode 1","episodeNumber":1,"totalEpisodes":2,"locked":false,
            "videoUrl":"/api/proxy/video","sourceServer":"2",
            "qualityList":[{"label":"720p","url":"/a.m3u8","format":"hls"},
            {"label":"1080p","url":"/b.mp4","format":"mp4"}],
            "subtitles":[{"label":"English","srclang":"en","url":"/subtitle.en.vtt"}]}
        """.trimIndent()
        val playback = ReelFrenParse.playback(body)
        assertEquals(3, playback!!.qualities.size)
        assertEquals("2", playback.server)
        assertEquals("/api/proxy/video", playback.qualities[0].url)
        assertEquals("mp4", playback.qualities[2].format)
        assertEquals("en", playback.subtitles.single().lang)
        val detail = ReelFrenParse.detail(
            "{\"id\":\"1\",\"provider\":\"x\",\"title\":\"T\",\"episodes\":2,\"videos\":[{\"episode\":2},{\"episode\":1}]}"
        )
        assertEquals(listOf(1, 2), detail!!.videos.map { it.episode })
    }

    @Test
    fun `quality mapping covers site labels`() {
        assertEquals(1080, ReelFrenQuality.of("1080p"))
        assertEquals(720, ReelFrenQuality.of("720p"))
        assertEquals(480, ReelFrenQuality.of("540p"))
        assertEquals(480, ReelFrenQuality.of("480p"))
        assertEquals(360, ReelFrenQuality.of("360p"))
        assertEquals(2160, ReelFrenQuality.of("4K"))
        assertEquals(400, ReelFrenQuality.of("Auto"))
        assertEquals(400, ReelFrenQuality.of("Japanese"))
        assertEquals(400, ReelFrenQuality.of("Auto HLS (500)"))
        assertEquals(400, ReelFrenQuality.of(""))
    }
}
