package com.wood

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WoodExtractorsTest {
    @Test
    fun `playback headers match what a phone browser sends for media`() {
        assertTrue(MEDIA_HEADERS["User-Agent"].orEmpty().contains("Android"))
        assertTrue(MEDIA_HEADERS["User-Agent"].orEmpty().contains("Mobile Safari"))
        assertTrue(MEDIA_HEADERS["Accept"].orEmpty().contains("*/*"))
        assertFalse(MEDIA_HEADERS.keys.any { it.startsWith("Sec-Fetch", true) })
        assertFalse(MEDIA_HEADERS.containsKey("Referer"))
        assertFalse(MEDIA_HEADERS.containsKey("Connection"))
    }

    @Test
    fun `webview clearance cookies reach the player`() {
        assertEquals(MEDIA_HEADERS, withCookie(MEDIA_HEADERS, null))
        assertEquals(MEDIA_HEADERS, withCookie(MEDIA_HEADERS, "  "))
        val merged = withCookie(MEDIA_HEADERS, "cf_clearance=abc; __cf_bm=def")
        assertEquals("cf_clearance=abc; __cf_bm=def", merged["Cookie"])
        assertEquals(MEDIA_HEADERS["User-Agent"], merged["User-Agent"])
    }

    @Test
    fun `only a real success status retires a source`() {
        assertTrue(isPlayableStatus(200))
        assertTrue(isPlayableStatus(206))
        assertFalse(isPlayableStatus(403))
        assertFalse(isPlayableStatus(404))
        assertFalse(isPlayableStatus(500))
        assertFalse(isPlayableStatus(302))
    }

    @Test
    fun `a refused host stays out until its cooldown expires`() {
        DeadHosts.forget("cooldown.example")

        assertFalse(DeadHosts.isDead("cooldown.example", now = 0))
        DeadHosts.mark("cooldown.example", now = 0)
        assertTrue(DeadHosts.isDead("cooldown.example", now = 1_000))
        assertFalse(DeadHosts.isDead("cooldown.example", now = DeadHosts.TTL_MS + 1))
    }

    @Test
    fun `host extraction ignores junk`() {
        assertEquals("cdn.example", hostOf("https://cdn.example/Movie.mkv?token=x"))
        assertEquals("", hostOf("not a url"))
        assertFalse(DeadHosts.isDead(""))
    }

    @Test
    fun `direct media hosts are recognised regardless of case`() {
        assertTrue(DIRECT_MEDIA_PATTERN.containsMatchIn("https://cdn.example/Movie_1080p.MKV"))
        assertTrue(DIRECT_MEDIA_PATTERN.containsMatchIn("https://cdn.example/master.m3u8?token=x"))
        assertFalse(DIRECT_MEDIA_PATTERN.containsMatchIn("https://movieswood.cloud/rating.php?f=10ja4bb"))
    }

}
