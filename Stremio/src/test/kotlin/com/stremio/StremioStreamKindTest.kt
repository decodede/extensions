package com.stremio

import com.lagradost.cloudstream3.utils.ExtractorLinkType

class StremioStreamKindTest {

    private fun check(expected: StreamKind, url: String?, hasInfoHash: Boolean = false) {
        val got = streamKindOf(url, hasInfoHash)
        if (got != expected) {
            throw AssertionError("streamKindOf($url, hasInfoHash=$hasInfoHash) = $got, expected $expected")
        }
    }

    fun magnetVariants() {
        check(StreamKind.MAGNET, "magnet:?xt=urn:btih:" + "a".repeat(40))
        check(StreamKind.MAGNET, "MAGNET:?xt=urn:btih:" + "a".repeat(40))
        check(StreamKind.MAGNET, "", hasInfoHash = true)
        check(StreamKind.MAGNET, null, hasInfoHash = true)
        check(StreamKind.MAGNET, "https://host/list.m3u", hasInfoHash = true)
    }

    fun torrentFiles() {
        check(StreamKind.TORRENT, "https://host/dl/abc.torrent")
        check(StreamKind.TORRENT, "https://host/dl/abc.TORRENT?token=1")
    }

    fun hlsDetection() {
        check(StreamKind.HLS, "https://host/master.m3u8")
        check(StreamKind.HLS, "https://host/master.m3u8?token=abc")
        check(StreamKind.HLS, "https://host/proxy/1234.m3u8/manifest")
        check(StreamKind.HLS, "https://host/v?ext=m3u8")
        check(StreamKind.HLS, "https://host/hls/stream/index.m3u8")
        check(StreamKind.HLS, "https://host/playlist/abc")
        check(StreamKind.HLS, "https://host/x?format=hls")
        check(StreamKind.HLS, "https://host/./m3u8")
    }

    fun dashDetection() {
        check(StreamKind.DASH, "https://host/manifest.mpd")
        check(StreamKind.DASH, "https://host/manifest.mpd?a=1")
        check(StreamKind.DASH, "https://host/dash/stream.mpd")
        check(StreamKind.DASH, "https://host/v?ext=mpd")
        check(StreamKind.DASH, "https://host/v?type=dash")
    }

    fun m3uIsNotAStream() {
        check(StreamKind.M3U, "https://host/playlist.m3u")
        check(StreamKind.M3U, "https://host/playlist.m3u?token=abc")
        check(StreamKind.M3U, "https://host/hls/stream/index.m3u")
    }

    fun nonHttpSchemes() {
        check(StreamKind.RTMP, "rtmp://live.example.net/app/stream")
        check(StreamKind.RTMP, "RTMPS://live.example.net/app")
        check(StreamKind.RTSP, "rtsp://cam.example.net:554/live")
        check(StreamKind.RTSP, "rtsps://cam.example.net/live")
        check(StreamKind.FTP, "ftp://files.example.net/movie.mp4")
        check(StreamKind.FTP, "ftps://files.example.net/movie.mp4")
    }

    fun progressiveContainers() {
        for (ext in listOf("mp4", "mkv", "avi", "webm", "mov", "flv", "ts", "m4v", "wmv")) {
            check(StreamKind.PROGRESSIVE, "https://host/file.$ext")
            check(StreamKind.PROGRESSIVE, "https://host/file.$ext?sig=xyz")
        }
    }

    fun unrecognisedIsNone() {
        check(StreamKind.NONE, "https://host/watch/abc123")
        check(StreamKind.NONE, "https://cdn.example.net/dl")
        check(StreamKind.NONE, "")
        check(StreamKind.NONE, null)
    }

    fun protocolTokenInHostnameIsNotHls() {
        check(StreamKind.NONE, "https://m3u8-proxy.example.net/watch/abc")
        check(StreamKind.PROGRESSIVE, "https://dash-cdn.example.net/movie.mp4")
    }

    fun playabilityAndTypeAreDistinct() {
        val playable = setOf(
            StreamKind.MAGNET, StreamKind.TORRENT, StreamKind.DASH, StreamKind.HLS,
            StreamKind.PROGRESSIVE, StreamKind.RTMP, StreamKind.RTSP, StreamKind.FTP,
        )
        for (kind in StreamKind.entries) {
            if (kind.isPlayable != (kind in playable)) {
                throw AssertionError("StreamKind.$kind isPlayable=${kind.isPlayable}")
            }
            if (!kind.isPlayable && kind.rejectionReason().isBlank()) {
                throw AssertionError("StreamKind.$kind gives no reason to log")
            }
        }
        val typed = mapOf(
            StreamKind.MAGNET to ExtractorLinkType.MAGNET,
            StreamKind.TORRENT to ExtractorLinkType.TORRENT,
            StreamKind.DASH to ExtractorLinkType.DASH,
            StreamKind.HLS to ExtractorLinkType.M3U8,
        )
        for ((kind, expected) in typed) {
            if (kind.linkType() != expected) {
                throw AssertionError("StreamKind.$kind linkType=${kind.linkType()}, expected $expected")
            }
        }
        for (kind in listOf(StreamKind.PROGRESSIVE, StreamKind.RTMP, StreamKind.RTSP, StreamKind.FTP)) {
            if (kind.linkType() != null) {
                throw AssertionError("StreamKind.$kind should be left to sniffing, got ${kind.linkType()}")
            }
        }
    }

    fun magnetBuildsFromBothHashVersions() {
        val v1 = buildMagnet("a".repeat(40), "Name", emptyList())
        if (v1 == null || !v1.startsWith("magnet:?xt=urn:btih:")) {
            throw AssertionError("v1 magnet wrong: $v1")
        }
        val v2 = buildMagnet("b".repeat(64), "Name", emptyList())
        if (v2 == null || !v2.contains("urn:btmh:1220")) {
            throw AssertionError("v2 magnet should use urn:btmh:1220, got: $v2")
        }
        if (buildMagnet("nothex", null, emptyList()) != null) {
            throw AssertionError("non-hex infoHash must be rejected")
        }
        if (buildMagnet("a".repeat(39), null, emptyList()) != null) {
            throw AssertionError("39-char infoHash must be rejected")
        }
    }

    fun magnetCarriesAddonTrackersAndFileIndex() {
        val magnet = buildMagnet(
            "c".repeat(40),
            "Some Movie 1080p",
            listOf("tracker:udp://tracker.example:1337/announce", "dht:abcdef", "udp://bare.example/announce"),
            fileIdx = 3,
        ) ?: throw AssertionError("magnet should build")
        if (!magnet.contains("&so=3")) throw AssertionError("fileIdx must map to &so=: $magnet")
        if (!magnet.contains("&tr=udp%3A%2F%2Ftracker.example%3A1337%2Fannounce")) {
            throw AssertionError("tracker: prefix must be stripped and url-encoded: $magnet")
        }
        if (!magnet.contains("&tr=udp%3A%2F%2Fbare.example%2Fannounce")) {
            throw AssertionError("bare udp tracker must pass through: $magnet")
        }
        if (magnet.contains("dht%3A")) throw AssertionError("dht: is not a magnet tr param: $magnet")
        if (!magnet.contains("&dn=Some+Movie+1080p")) {
            throw AssertionError("dn must be form-encoded, not left raw: $magnet")
        }
    }

    fun magnetFallsBackToHardcodedTrackers() {
        val magnet = buildMagnet("d".repeat(40), null, emptyList())
            ?: throw AssertionError("magnet should build with no trackers")
        if (!magnet.contains("&tr=")) throw AssertionError("must fall back to a tracker list: $magnet")
    }

    fun kodiPipeHeadersAreParsed() {
        val (url, headers) = splitKodiHeaders("https://host/v.mp4|Referer=https://host/&User-Agent=UA1")
        if (url != "https://host/v.mp4") throw AssertionError("url split wrong: $url")
        if (headers["Referer"] != "https://host/") throw AssertionError("Referer wrong: $headers")
        if (headers["User-Agent"] != "UA1") throw AssertionError("UA wrong: $headers")

        val (plain, none) = splitKodiHeaders("https://host/v.mp4")
        if (plain != "https://host/v.mp4" || none.isNotEmpty()) {
            throw AssertionError("no pipe should yield no headers: $plain $none")
        }
        val (weird, empty) = splitKodiHeaders("https://host/v.mp4|notaheader")
        if (weird != "https://host/v.mp4|notaheader" || empty.isNotEmpty()) {
            throw AssertionError("malformed pipe must be preserved verbatim: $weird")
        }
    }

    fun headerMergePrefersProxyHeadersAndBlocksFramingHeaders() {
        val hints = BehaviorHints(
            proxyHeaders = ProxyHeaders(request = mapOf("User-Agent" to "fromProxy", "Referer" to "https://ref/")),
            headers = mapOf("User-Agent" to "fromHints", "X-Custom" to "keep"),
        )
        val stream = StremioStream(headers = mapOf("User-Agent" to "fromStream", "X-Top" to "keep"))
        val merged = mergeStreamHeaders(stream, hints, "https://cdn.example.net/v.mp4")

        if (merged["User-Agent"] != "fromProxy") throw AssertionError("proxyHeaders must win: $merged")
        if (merged["X-Custom"] != "keep") throw AssertionError("behaviorHints.headers must survive: $merged")
        if (merged["X-Top"] != "keep") throw AssertionError("stream.headers must survive: $merged")

        val dirty = sanitizeForwardedHeaders(
            mapOf(
                "Host" to "evil.example",
                "Content-Length" to "0",
                "X-CRLF" to "a\r\nX-Injected: 1",
                "Sec-Fetch-Mode" to "cors",
                "Referer" to "https://ok.example/",
            )
        )
        if (dirty.keys.any { it.equals("Host", true) || it.equals("Content-Length", true) }) {
            throw AssertionError("framing headers must be blocked: $dirty")
        }
        if (dirty.keys.any { it.startsWith("sec-", true) }) throw AssertionError("sec-* blocked: $dirty")
        if (dirty.values.any { it.contains('\n') || it.contains('\r') }) {
            throw AssertionError("CRLF injection must be blocked: $dirty")
        }
        if (dirty["Referer"] != "https://ok.example/") {
            throw AssertionError("ordinary headers must pass: $dirty")
        }
    }

    fun browserUserAgentIsAlwaysSupplied() {
        val merged = mergeStreamHeaders(StremioStream(), null, "https://host/v.mp4", StremioConstants.UA_DESKTOP)
        if (merged["User-Agent"] != StremioConstants.UA_DESKTOP) {
            throw AssertionError("expected the default browser UA, got: $merged")
        }
    }

    fun selfRefererIsSynthesisedForGatedHosts() {
        val headers = mergeStreamHeaders(
            StremioStream(),
            BehaviorHints(proxyHeaders = ProxyHeaders(request = mapOf("Referer" to "https://pixel.hubcloud.cx/"))),
            "https://pixel.hubcloud.cx/movie.mkv",
            StremioConstants.UA_DESKTOP,
        )
        if (headers["Referer"] != "https://pixel.hubcloud.cx/") {
            throw AssertionError("addon Referer must be kept: $headers")
        }
        if (headers["Origin"] != "https://pixel.hubcloud.cx") {
            throw AssertionError("Origin must be derived from Referer: $headers")
        }

        val guessed = mergeStreamHeaders(StremioStream(), null, "https://gated.example.net/v.mkv", StremioConstants.UA_DESKTOP)
        if (guessed["Referer"] != "https://gated.example.net/") {
            throw AssertionError("expected a synthesised self-referer: $guessed")
        }

        val safe = mergeStreamHeaders(StremioStream(), null, "https://hubcloud.cx.evil.net/v.mkv", StremioConstants.UA_DESKTOP)
        if (safe["Referer"] != "https://hubcloud.cx.evil.net/") {
            throw AssertionError("referer must match the media host exactly: $safe")
        }
    }

    fun zeroVideoSizeIsTreatedAsAbsent() {
        if (videoSizeOrNull(0L) != null) throw AssertionError("0 videoSize must read as absent")
        if (videoSizeOrNull(null) != null) throw AssertionError("null videoSize must read as absent")
        if (videoSizeOrNull(-1L) != null) throw AssertionError("negative videoSize must read as absent")
        if (videoSizeOrNull(1024L) != 1024L) throw AssertionError("positive videoSize must survive")
    }

    fun defaultAddonsAreWellFormed() {
        if (StremioDefaultAddons.all.isEmpty()) throw AssertionError("no default addons shipped")
        val bad = StremioDefaultAddons.all.filter { !it.startsWith("https://") }
        if (bad.isNotEmpty()) throw AssertionError("non-https defaults: $bad")
        if (StremioDefaultAddons.all.any { it.contains(Regex("\\s")) }) {
            throw AssertionError("a default URL contains whitespace")
        }
        val dupes = StremioDefaultAddons.all.groupBy { it }.filterValues { it.size > 1 }.keys
        if (dupes.isNotEmpty()) throw AssertionError("duplicate default: $dupes")
    }

    fun kodiPipeSuffixIsStrippedFromThePlayableUrl() {
        val link = toStreamLink(
            StremioStream(
                name = "1080p",
                url = "https://host/v.mp4|Referer=https%3A%2F%2Fhost%2F&User-Agent=UA1",
            ),
            addonName = "Test",
            addonOrder = 0,
        ) ?: throw AssertionError("link should build")

        if (link.url != "https://host/v.mp4") {
            throw AssertionError("pipe suffix must be stripped from the URL, got: ${link.url}")
        }
        if (link.url.contains('|')) throw AssertionError("playable URL still carries a pipe")
        if (link.kind != StreamKind.PROGRESSIVE) {
            throw AssertionError("kind should come from the clean URL, got ${link.kind}")
        }
        if (link.headers["User-Agent"] != "UA1") {
            throw AssertionError("headers from the pipe must survive: ${link.headers}")
        }
    }

    fun onlySafeHeaderNamesAndValuesSurvive() {
        val cleaned = sanitizeForwardedHeaders(
            mapOf(
                "X-Euro" to "\u20ac",
                "X-Latin1" to "caf\u00e9",
                "X-Good" to "plain-ascii",
                "X Bad Name" to "value",
                "X-Crlf" to "a\r\nb",
            )
        )
        if (cleaned.keys.any { it == "X-Euro" }) {
            throw AssertionError("value outside Latin-1 must be dropped, OkHttp would throw: $cleaned")
        }
        if (cleaned["X-Latin1"] != "caf\u00e9") throw AssertionError("Latin-1 is legal, must survive: $cleaned")
        if (cleaned.keys.any { it.contains(' ') }) throw AssertionError("invalid header name kept: $cleaned")
        if (cleaned.keys.any { it == "X-Crlf" }) throw AssertionError("CRLF value kept: $cleaned")
        if (cleaned["X-Good"] != "plain-ascii") throw AssertionError("safe header dropped: $cleaned")
    }

    fun headerOverridesAreCaseInsensitive() {
        val merged = sanitizeForwardedHeaders(
            linkedMapOf("User-Agent" to "first", "user-agent" to "second")
        )
        if (merged.size != 1) throw AssertionError("same header stored twice: $merged")
        if (merged.values.first() != "second") throw AssertionError("later value must win: $merged")
    }

    fun runAll() {
        magnetVariants()
        torrentFiles()
        hlsDetection()
        dashDetection()
        m3uIsNotAStream()
        nonHttpSchemes()
        progressiveContainers()
        unrecognisedIsNone()
        protocolTokenInHostnameIsNotHls()
        playabilityAndTypeAreDistinct()
        magnetBuildsFromBothHashVersions()
        magnetCarriesAddonTrackersAndFileIndex()
        magnetFallsBackToHardcodedTrackers()
        kodiPipeHeadersAreParsed()
        onlySafeHeaderNamesAndValuesSurvive()
        headerOverridesAreCaseInsensitive()
        kodiPipeSuffixIsStrippedFromThePlayableUrl()
        headerMergePrefersProxyHeadersAndBlocksFramingHeaders()
        browserUserAgentIsAlwaysSupplied()
        selfRefererIsSynthesisedForGatedHosts()
        zeroVideoSizeIsTreatedAsAbsent()
        defaultAddonsAreWellFormed()
    }
}
