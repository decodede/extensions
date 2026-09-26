package com.rulz

import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RulzProviderTest {
    private val provider = RulzProvider().apply { mainUrl = DEFAULT_BASE }

    @Test
    fun `keeps both site players in order`() {
        val html = """
            <script>
                var locations = [
                    "https:\/\/streamvin.com\/video\/abc123",
                    "https:\/\/ww7.vcdnlare.com\/v\/def456?sid=9307&t=hls"
                ];
            </script>
        """.trimIndent()

        assertEquals(
            listOf(
                "https://streamvin.com/video/abc123",
                "https://ww7.vcdnlare.com/v/def456?sid=9307&t=hls"
            ),
            extractPlayerUrls(html, "https://www.5movierulz.services/movie.html")
        )
    }

    @Test
    fun `extracts every current media field including master txt`() {
        val json = """
            {
              "result": {
                "360p": {
                  "label": "360p",
                  "file": "https:\/\/cdn.example\/hls\/master.m3u8",
                  "type": "hls"
                },
                "auto": {
                  "file": "https:\/\/cdn.example\/hls\/master.txt",
                  "type": "hls"
                }
              }
            }
        """.trimIndent()

        val candidates = extractMediaCandidates(json, "https://provider.example/embed/abc")

        assertEquals(
            listOf(
                "https://cdn.example/hls/master.m3u8",
                "https://cdn.example/hls/master.txt"
            ),
            candidates.map { it.url }
        )
        assertEquals(ExtractorLinkType.M3U8, streamType(candidates.last().url))
    }

    @Test
    fun `does not mistake a Cloudflare beacon for a challenge page`() {
        val legitimatePage = """
            <html><body>
            <script src="/cdn-cgi/challenge-platform/scripts/jsd/main.js"></script>
            <source src="https://cdn.example/master.m3u8">
            </body></html>
        """.trimIndent()

        assertFalse(isChallengePage(legitimatePage))
        assertTrue(isChallengePage("<title>Just a moment...</title><div id='cf-chl-widget'></div>"))
    }

    @Test
    fun `keeps catalog cards even when a poster is missing`() {
        val html = """
            <div class="boxed film">
              <div class="cont_display">
                <a href="/first-movie.html" title="First Movie (2026) WEBRip">
                  <img src="/uploads/first.jpg" alt="First Movie">
                </a>
              </div>
            </div>
            <div class="boxed film">
              <div class="cont_display">
                <a href="/second-movie.html" title="Second Movie (2026) WEBRip"></a>
              </div>
              <p><b>Second Movie (2026) WEBRip</b></p>
            </div>
        """.trimIndent()

        val items = provider.gridItems(Jsoup.parse(html, DEFAULT_BASE))

        assertEquals(2, items.size)
        assertEquals(listOf("$DEFAULT_BASE/first-movie.html", "$DEFAULT_BASE/second-movie.html"), items.map { it.url })
    }

    @Test
    fun `extracts current provider token and file links`() {
        val landing = """
            <a class="btn" href="/gUqXrCYB/download?token=abc123">Download Now</a>
        """.trimIndent()
        val downloadPage = """
            <a class="btn" href="/dl?code=gUqXrCYB&token=def456">Start Download</a>
        """.trimIndent()

        val downloadLink = TokenDownloadResolver.findLink(landing, "https://www.uperbox.cx/gUqXrCYB") {
            it.contains("/download?") && it.contains("token=")
        }
        val fileLink = TokenDownloadResolver.findLink(downloadPage, "https://www.uperbox.cx/gUqXrCYB/download?token=abc123") {
            it.contains("/dl?") && it.contains("code=")
        }

        assertEquals("https://www.uperbox.cx/gUqXrCYB/download?token=abc123", downloadLink)
        assertEquals("https://www.uperbox.cx/dl?code=gUqXrCYB&token=def456", fileLink)
    }

    @Test
    fun `unpacks current FileLions player links`() {
        val packed = "eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(c,k[c]);return p}(" +
            "'var links={\"hls2\":\"https://cdn.example/hls2/master.m3u8\"};sources:[{file:hls2}];'," +
            "62,1,'hls2'.split('|')))"

        val unpacked = unpackPacker(packed)

        assertTrue(unpacked.contains("var links="))
        assertEquals(
            listOf("https://cdn.example/hls2/master.m3u8"),
            extractMediaCandidates(unpacked, "https://callistanise.com/v/abc").map { it.url }
        )
    }

    @Test
    fun `routes each advertised provider to its dedicated resolver`() {
        assertTrue(StreamLareResolver.matches("Player 2", "https://ww7.vcdnlare.com/v/abc"))
        assertTrue(UperBoxResolver.matches("Uperbox", "https://www.uperbox.cx/abc"))
        assertTrue(EasySyncResolver.matches("Easysyncr", "https://www.easysyncr.me/abc"))
        assertTrue(DownloadResolver.matches("Download", "https://www.easysyncr.me/abc"))
        assertTrue(StreamVinResolver.matches("Player 1", "https://streamvin.com/video/abc"))
        assertTrue(FileLionsResolver.matches("Filelions", "https://minochinos.com/f/abc"))
    }

    @Test
    fun `diagnostic URLs never expose query tokens`() {
        assertEquals(
            "https://www.easysyncr.me/file/abc",
            safeUrl("https://www.easysyncr.me/file/abc?token=secret&code=secret")
        )
    }

    @Test
    fun `types tokenised hls cdn playlists as m3u8`() {
        val streamLare = "https://hls2.vcdnx.com/hls/SFVhaURJUXd0OGloSFZybTI3WHJjdz09/xfgdYshjhYhj=!sdsHsyG"
        val pathStyle = "https://streamwish.to/hls/abcdef1234"

        assertTrue(isHlsUrl(streamLare))
        assertEquals(ExtractorLinkType.M3U8, streamType(streamLare))
        assertEquals(ExtractorLinkType.M3U8, streamType(pathStyle))
        assertEquals(ExtractorLinkType.VIDEO, streamType("https://cdn.example/dl/abc123.mp4"))
        assertFalse(isHlsUrl("https://www.uperbox.cx/dl?code=gUqXrCYB&token=def456"))
    }

    @Test
    fun `parses the current StreamVin json payload`() {
        val body = """
            {"hls":true,"videoSource":"https:\/\/streamvin.com\/cdn\/hls\/a139b1\/master.txt",
             "securedLink":"https:\/\/streamvin.com\/cdn\/hls\/a139b1\/master.m3u8?md5=abc&expires=1790362204",
             "downloadLinks":["https:\/\/streamvin.com\/dl\/a139b1\/720.mp4"]}
        """.trimIndent()

        val context = RulzResolverContext()
        val target = HostTarget("Player 1", "https://streamvin.com/video/abc123")
        val streams = LinkedHashMap<String, FoundStream>()
        listOf("securedLink", "videoSource").forEach { key ->
            context.putStream(
                streams,
                StreamVinResolver.jsonString(body, key),
                "Auto",
                target,
                context.playbackHeaders(target.url, target.url)
            )
        }

        assertEquals(
            listOf(
                "https://streamvin.com/cdn/hls/a139b1/master.m3u8?md5=abc&expires=1790362204",
                "https://streamvin.com/cdn/hls/a139b1/master.txt"
            ),
            streams.keys.toList()
        )
        assertTrue(streams.values.all { streamType(it.url) == ExtractorLinkType.M3U8 })
    }

    @Test
    fun `reads the current FileLions links object`() {
        val links = Regex("""var\s+links\s*=\s*(\{[^}]+\})""")
            .find("""var links={"hls2":"https://cdn.example/hls2/master.m3u8","hls3":"https://cdn.example/hls3/720.m3u8"}""")
            ?.groupValues?.get(1).orEmpty()

        val urls = listOf("hls2", "hls3").mapNotNull { key ->
            Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"").find(links)?.groupValues?.get(1)
        }

        assertEquals(
            listOf("https://cdn.example/hls2/master.m3u8", "https://cdn.example/hls3/720.m3u8"),
            urls
        )
    }

    @Test
    fun `uses the requested live domain by default`() {
        assertEquals("https://www.5movierulz.services", DEFAULT_BASE)
        assertEquals("https://www.5movierulz.services", normalizeBaseUrl("www.5movierulz.services"))
        assertEquals(null, normalizeBaseUrl("http://127.0.0.1"))
        assertEquals(null, normalizeBaseUrl("https://user:pass@example.com"))
    }
}
