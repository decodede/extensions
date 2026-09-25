package com.rulz

import android.util.Log
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI

internal const val RULZ_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

internal data class HostTarget(val label: String, val url: String)

internal data class FoundStream(
    val url: String,
    val label: String,
    val rank: Int,
    val referer: String,
    val headers: Map<String, String>
)

internal class RulzResolverContext {
    private val cookieJar = HashMap<String, Map<String, String>>()

    suspend fun text(target: HostTarget, url: String, referer: String, stage: String): String? {
        val host = hostOf(url)
        val cookies = synchronized(cookieJar) { cookieJar[host].orEmpty() }
        val response = fetchRulzTextResponse(url, referer, stage, cookies) ?: return null
        if (response.cookies.isNotEmpty()) {
            synchronized(cookieJar) {
                cookieJar[hostOf(response.url).ifEmpty { host }] =
                    (cookieJar[hostOf(response.url).ifEmpty { host }].orEmpty() + response.cookies)
            }
        }
        return response.text
    }

    fun headers(pageUrl: String, referer: String): Map<String, String> {
        val headers = playbackHeaders(pageUrl, referer).toMutableMap()
        val cookies = synchronized(cookieJar) { cookieJar[hostOf(pageUrl)].orEmpty() }
        if (cookies.isNotEmpty()) {
            headers["Cookie"] = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }
        return headers
    }

    fun playbackHeaders(pageUrl: String, referer: String): Map<String, String> = mapOf(
        "User-Agent" to RULZ_USER_AGENT,
        "Referer" to referer,
        "Origin" to originOf(pageUrl),
        "Connection" to "keep-alive"
    )

    fun direct(target: HostTarget, referer: String): FoundStream {
        val quality = if (isHlsUrl(target.url)) "Auto" else parseQuality(target.url).ifEmpty { "HD" }
        return FoundStream(
            target.url,
            target.label + sizeSuffix(parseSize(target.url)),
            qualityRank(quality),
            referer,
            playbackHeaders(target.url, referer)
        )
    }

    fun putStream(
        out: LinkedHashMap<String, FoundStream>,
        rawUrl: String,
        quality: String,
        target: HostTarget,
        headers: Map<String, String>,
        playbackReferer: String = target.url
    ) {
        val url = protocolFix(decodeEntities(rawUrl))
        if (!isPublicHttp(url) || isAssetUrl(url)) return
        out.putIfAbsent(url, FoundStream(url, target.label, qualityRank(quality), playbackReferer, headers))
    }
}

internal object RulzResolverLog {
    private const val TAG = "Rulz"

    fun start(provider: String, target: HostTarget) {
        val label = target.label.replace(Regex("[\\r\\n\\t]"), " ").take(40)
        Log.d(TAG, "[$provider] start target=${safeUrl(target.url)} label=$label")
    }

    fun stage(provider: String, target: HostTarget, stage: String) {
        Log.d(TAG, "[$provider] stage=$stage target=${safeUrl(target.url)}")
    }

    fun fallback(provider: String, target: HostTarget, reason: String) {
        Log.d(TAG, "[$provider] fallback reason=$reason target=${safeUrl(target.url)}")
    }

    fun empty(provider: String, target: HostTarget, reason: String) {
        Log.w(TAG, "[$provider] no media: $reason target=${safeUrl(target.url)}")
    }

    fun success(provider: String, target: HostTarget, count: Int) {
        Log.d(TAG, "[$provider] resolved count=$count target=${safeUrl(target.url)}")
    }

    fun failure(provider: String, target: HostTarget, stage: String, error: Throwable) {
        val detail = "${error.javaClass.simpleName}: ${sanitizeError(error.message)}"
        Log.w(TAG, "[$provider] failed stage=$stage target=${safeUrl(target.url)} error=$detail")
    }
}

internal data class RulzTextResponse(
    val text: String,
    val url: String,
    val cookies: Map<String, String>
)

internal suspend fun fetchRulzText(
    url: String,
    referer: String?,
    stage: String,
    userAgent: String = RULZ_USER_AGENT
): String? = fetchRulzTextResponse(url, referer, stage, emptyMap(), userAgent)?.text

internal suspend fun fetchRulzTextResponse(
    url: String,
    referer: String?,
    stage: String,
    cookies: Map<String, String> = emptyMap(),
    userAgent: String = RULZ_USER_AGENT
): RulzTextResponse? {
    if (!isPublicHttp(url)) {
        Log.w("Rulz", "[HTTP] rejected non-public target=${safeUrl(url)} stage=$stage")
        return null
    }
    return try {
        val response = withTimeoutOrNull(30_000L) {
            app.get(
                url,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Accept" to "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8"
                ),
                referer = referer,
                cookies = cookies
            )
        }
        if (response == null) {
            Log.w("Rulz", "[HTTP] timeout target=${safeUrl(url)} stage=$stage")
            return null
        }
        val body = response.text
        if (response.code !in 200..399) {
            Log.w("Rulz", "[HTTP] status=${response.code} target=${safeUrl(url)} stage=$stage")
            return RulzTextResponse("", response.url, response.cookies)
        }
        when {
            body.isBlank() -> Log.w("Rulz", "[HTTP] empty body target=${safeUrl(url)} stage=$stage")
            isChallengePage(body) -> Log.w("Rulz", "[HTTP] challenge page target=${safeUrl(url)} stage=$stage")
        }
        body.takeIf { it.isNotBlank() && !isChallengePage(it) }
            ?.let { RulzTextResponse(it, response.url, response.cookies) }
    } catch (error: Exception) {
        RulzResolverLog.failure("HTTP", HostTarget("request", url), stage, error)
        null
    }
}

internal fun unpackPacker(html: String): String {
    val source = html.take(500_000)
    val packed = Regex("""eval\(function\(p,a,c,k,e,d?\)[\s\S]*?\}\('([\s\S]*?)',(\d+),(\d+),'([\s\S]*?)'\.split\('\|'\)""")
    val unpacked = StringBuilder()
    var rounds = 0
    for (match in packed.findAll(source)) {
        if (rounds++ >= 10) break
        val radix = match.groupValues[2].toIntOrNull() ?: continue
        val count = match.groupValues[3].toIntOrNull() ?: continue
        if (radix !in 2..62 || count !in 0..2_000) continue
        val replacements = match.groupValues[4].split('|')
        var code = match.groupValues[1]
        for (index in count - 1 downTo 0) {
            val replacement = replacements.getOrNull(index).orEmpty()
            if (replacement.isNotEmpty()) {
                code = Regex("\\b" + baseN(index, radix) + "\\b").replace(code, replacement)
            }
        }
        unpacked.append(code).append('\n')
    }
    return unpacked.toString()
}

internal fun pageTitle(html: String): String =
    Regex("""<title>([^<]+)</title>""", RegexOption.IGNORE_CASE)
        .find(html)?.groupValues?.get(1)?.replace(Regex("\\s+"), " ")?.trim().orEmpty()

internal fun parseQuality(value: String): String {
    val text = value.lowercase()
    return when {
        text.contains("2160") || text.contains("4k") || text.contains("uhd") -> "2160p"
        text.contains("1080") -> "1080p"
        text.contains("720") -> "720p"
        text.contains("480") -> "480p"
        else -> ""
    }
}

internal fun parseSize(value: String): String =
    Regex("""(\d+(?:\.\d+)?)\s*(mb|gb)""", RegexOption.IGNORE_CASE)
        .find(value)?.let { it.groupValues[1] + it.groupValues[2].uppercase() }.orEmpty()

internal fun sizeSuffix(size: String): String = if (size.isEmpty()) "" else " [$size]"

internal fun qualityRank(quality: String): Int = when (quality.lowercase()) {
    "2160p" -> 0
    "1080p" -> 1
    "720p" -> 2
    "480p" -> 3
    "hd" -> 4
    else -> 5
}

internal fun absAgainst(href: String?, base: String): String {
    val clean = href?.replace(Regex("[\\r\\n\\t ]"), "")?.trim().orEmpty()
    if (clean.isEmpty()) return ""
    if (clean.startsWith("http://", true) || clean.startsWith("https://", true)) return clean
    return runCatching { URI(base).resolve(clean).toString() }.getOrDefault("")
}

internal fun absoluteSiteUrl(href: String, baseUrl: String): String {
    val clean = href.replace(Regex("[\\r\\n\\t ]"), "").trim().replace("&amp;", "&")
    return when {
        clean.isEmpty() -> ""
        clean.startsWith("http://", true) || clean.startsWith("https://", true) -> clean
        clean.startsWith("//") -> "https:$clean"
        clean.startsWith("/") -> baseUrl.trimEnd('/') + clean
        else -> baseUrl.trimEnd('/') + "/" + clean
    }
}

internal fun originOf(url: String): String = runCatching {
    val uri = URI(url)
    uri.scheme + "://" + uri.host
}.getOrDefault("")

internal fun hostOf(url: String): String = runCatching { URI(url).host.lowercase() }.getOrDefault("")

internal fun protocolFix(url: String): String {
    val clean = url.trim()
    return if (clean.startsWith("//")) "https:$clean" else clean
}

internal fun decodeEntities(value: String): String = value
    .replace("\\/", "/")
    .replace("\\u0026", "&", ignoreCase = true)
    .replace("&amp;", "&")
    .trim()

internal fun safeUrl(url: String): String = runCatching {
    val uri = URI(url)
    buildString {
        if (!uri.scheme.isNullOrEmpty()) append(uri.scheme).append("://")
        if (!uri.host.isNullOrEmpty()) append(uri.host)
        append(uri.path.orEmpty().replace(Regex("[\\r\\n\\t]"), ""))
    }
}.getOrDefault("<invalid-url>")

private fun sanitizeError(message: String?): String = message
    .orEmpty()
    .replace(Regex("""https?://[^\s]+""", RegexOption.IGNORE_CASE)) { safeUrl(it.value) }
    .take(300)

private fun isAssetUrl(url: String): Boolean =
    Regex("""\.(?:png|jpe?g|gif|webp|svg|css|js|vtt|srt)(?:$|[?#])""", RegexOption.IGNORE_CASE)
        .containsMatchIn(url)

private fun baseN(number: Int, base: Int): String {
    if (number == 0) return "0"
    var value = number
    var result = ""
    val alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    while (value > 0 && result.length < 12) {
        result = alphabet[value % base] + result
        value /= base
    }
    return result
}
