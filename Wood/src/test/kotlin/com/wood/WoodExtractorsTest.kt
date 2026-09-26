package com.wood

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WoodExtractorsTest {
    @Test
    fun `playback headers look like a browser media request`() {
        assertTrue(MEDIA_HEADERS["User-Agent"].orEmpty().startsWith("Mozilla/5.0"))
        assertTrue(MEDIA_HEADERS["Accept"].orEmpty().contains("*/*"))
        assertTrue(MEDIA_HEADERS["Connection"].orEmpty().equals("keep-alive", true))
        assertTrue(MEDIA_HEADERS["Sec-Fetch-Dest"].orEmpty().equals("video", true))
    }

    @Test
    fun `only a real success status retires a source`() {
        assertTrue(isPlayableStatus(200))
        assertTrue(isPlayableStatus(206))
        // Cloudflare's "Website Access Blocked" is what the player used to
        // surface as ERROR_CODE_IO_BAD_HTTP_STATUS (2004).
        assertFalse(isPlayableStatus(403))
        assertFalse(isPlayableStatus(404))
        assertFalse(isPlayableStatus(500))
        assertFalse(isPlayableStatus(302))
    }

    @Test
    fun `direct media hosts are recognised regardless of case`() {
        assertTrue(DIRECT_MEDIA_PATTERN.containsMatchIn("https://cdn.example/Movie_1080p.MKV"))
        assertTrue(DIRECT_MEDIA_PATTERN.containsMatchIn("https://cdn.example/master.m3u8?token=x"))
        assertFalse(DIRECT_MEDIA_PATTERN.containsMatchIn("https://movieswood.cloud/rating.php?f=10ja4bb"))
    }
}
