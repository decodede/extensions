package com.kimoitv

import com.lagradost.api.Log
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.ConcurrentHashMap


inline fun <T> guard(fallback: T, block: () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    fallback
}

object Net {

    private val session = ConcurrentHashMap<String, String>()

    private val browserHeaders = mapOf(
        K.HEADER_USER_AGENT to K.USER_AGENT,
        K.HEADER_ACCEPT to K.ACCEPT_MEDIA,
        K.HEADER_ACCEPT_LANGUAGE to K.ACCEPT_LANGUAGE,
        K.HEADER_UPGRADE to K.VALUE_UPGRADE,
        K.HEADER_SEC_FETCH to K.VALUE_SAME_ORIGIN,
        K.HEADER_SEC_FETCH_MODE to K.VALUE_NAVIGATE,
        K.HEADER_SEC_FETCH_DEST to K.VALUE_DOCUMENT,
    )

    private val playbackHeaders = mapOf(
        K.HEADER_USER_AGENT to K.USER_AGENT,
        K.HEADER_ACCEPT to K.ACCEPT_VIDEO,
        K.HEADER_ACCEPT_LANGUAGE to K.ACCEPT_LANGUAGE,
        K.HEADER_REFERER to K.REFERER_ROOT,
    )

    suspend fun cookies(): Map<String, String> {
        if (session.isEmpty()) {
            guard(emptyMap()) {
                val found = app.get(K.BASE_URL, headers = browserHeaders, cacheTime = K.CACHE_SESSION)
                    .cookies
                    .filterValues { it.isNotBlank() }
                found.forEach { (name, value) -> session[name] = value }
                found
            }
        }
        return session.toMap()
    }

    suspend fun get(url: String, cacheTime: Int, referer: String? = null): Document =
        guard(Document(K.BASE_URL)) {
            fetch(
                url = url,
                referer = referer,
                headers = browserHeaders,
                cacheTime = cacheTime,
            ).document
        }

    suspend fun post(url: String, data: Map<String, String>, referer: String): Document =
        guard(Document(K.BASE_URL)) {
            var response = send(url, data, referer)
            if (blocked(response) && !cloudflare.hasClearance()) {
                Log.w(K.TAG, "post blocked $url code=${response.code} solving")
                cloudflare.solve()
                response = send(url, data, referer)
            }
            response.document
        }

    private suspend fun fetch(
        url: String,
        referer: String?,
        headers: Map<String, String>,
        cacheTime: Int,
    ): NiceResponse {
        var response = send(url, referer = referer, headers = headers, cacheTime = cacheTime)
        if (blocked(response) && !cloudflare.hasClearance()) {
            Log.w(K.TAG, "blocked $url code=${response.code} len=${response.text.length} solving")
            cloudflare.solve(url)
            response = send(url, referer = referer, headers = headers, cacheTime = K.CACHE_NONE)
            Log.w(K.TAG, "retry $url code=${response.code} len=${response.text.length}")
        }
        return response
    }

    private suspend fun send(
        url: String,
        referer: String? = null,
        headers: Map<String, String>,
        cacheTime: Int,
    ): NiceResponse = app.get(
        url = url,
        referer = referer,
        headers = headers,
        cookies = cookies(),
        cacheTime = cacheTime,
        interceptor = bypass,
    )

    private suspend fun send(
        url: String,
        data: Map<String, String>,
        referer: String,
    ): NiceResponse = app.post(
        url = url,
        data = data,
        referer = referer,
        headers = browserHeaders + mapOf(
            K.HEADER_ACCEPT to K.ACCEPT_ANY,
            K.HEADER_CONTENT_TYPE to K.CONTENT_FORM,
            K.HEADER_ORIGIN to K.BASE_URL,
            K.HEADER_REFERER to referer,
            K.HEADER_REQUESTED_WITH to K.VALUE_XHR,
        ),
        cookies = cookies(),
        cacheTime = K.CACHE_NONE,
        interceptor = bypass,
    )

    private fun blocked(response: NiceResponse): Boolean =
        guard(false) { cloudflare.isBlocked(response.code, response.text) }

    fun media(): Map<String, String> = playbackHeaders

    suspend fun <T, R> parallel(items: List<T>, limit: Int, block: suspend (T) -> R?): List<R> =
        guard(emptyList()) {
            coroutineScope {
                val semaphore = Semaphore(limit)
                items
                    .map { item -> async { semaphore.withPermit { guard(null) { block(item) } } } }
                    .awaitAll()
                    .filterNotNull()
            }
        }
}

object Dom {

    fun text(element: Element?, vararg selectors: String): String? = element?.let { node ->
        selectors.asSequence()
            .mapNotNull { node.select(it).firstOrNull() }
            .map { it.ownText().ifBlank { it.text() } }
            .firstOrNull { it.isNotBlank() }
            ?.let { K.RE_WHITESPACE.replace(it, K.SEPARATOR_SPACE).trim() }
    }

    fun attr(element: Element?, vararg names: String): String? = element?.let { node ->
        names.asSequence()
            .mapNotNull { node.attr(it).ifBlank { null } }
            .firstOrNull()
    }

    fun absolute(href: String): String = when {
        href.startsWith("http", true) -> href
        href.startsWith("//") -> "https:$href"
        else -> K.BASE_URL + (if (href.startsWith(K.SLASH)) K.EMPTY else K.SLASH) + href
    }

    fun type(tokens: List<String>, fallback: TvType): TvType {
        val haystack = tokens.joinToString(K.SEPARATOR_SPACE) { it.lowercase() }
        return K.TYPE_TOKENS.firstOrNull { entry -> entry.first.any { haystack.contains(it) } }
            ?.second
            ?: fallback
    }

    fun year(vararg sources: String?): Int? = sources.asSequence()
        .filterNotNull()
        .mapNotNull { K.RE_YEAR.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        .firstOrNull()

    fun season(label: String, href: String): Int =
        group(K.RE_SEASON, label, href) ?: K.SEASON_FALLBACK

    fun episode(label: String, href: String): Int {
        K.RE_EPISODE_STD.find(href)?.let { return it.groupValues[2].toIntOrNull() ?: K.EPISODE_FALLBACK }
        K.RE_EPISODE_STD.find(label)?.let { return it.groupValues[2].toIntOrNull() ?: K.EPISODE_FALLBACK }
        K.RE_EPISODE_WORD.find(label)?.let { return it.groupValues[1].toIntOrNull() ?: K.EPISODE_FALLBACK }
        return K.RE_EPISODE_INDEX.find(label)?.groupValues?.get(1)?.toIntOrNull() ?: K.EPISODE_FALLBACK
    }

    fun quality(label: String, url: String): String? {
        val haystack = "$url $label"
        K.RE_RESOLUTION.find(haystack)?.let { match ->
            match.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.let { return "$it${K.SUFFIX_P}" }
        }
        return K.RE_RESOLUTION_TOKEN.find(haystack)?.groupValues?.get(1)?.plus(K.SUFFIX_P)
    }

    fun host(url: String): String = guard(K.NAME) {
        java.net.URI(url).host?.removePrefix(K.SUB_WWW) ?: K.NAME
    }

    private fun group(regex: Regex, vararg sources: String): Int? = sources.asSequence()
        .mapNotNull { regex.find(it) }
        .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
        .firstOrNull()
}