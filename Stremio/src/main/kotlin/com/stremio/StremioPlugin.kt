package com.stremio

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.SubtitleHelper
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.stremio.ui.StremioSettingsFragment
import java.lang.ref.WeakReference
import java.util.Locale

@CloudstreamPlugin
class StremioPlugin : Plugin() {

    override fun load(context: Context) {
        StremioProviderRegistry.bindPlugin(this)
        val ctx = context.applicationContext ?: context
        (ctx as? Application)?.let { ResumedActivityTracker.install(it) }
        StremioProviderRegistry.attach(
            StremioRepository(ctx.getSharedPreferences(StremioConstants.PREFS_NAME, Context.MODE_PRIVATE))
        )
        openSettings = {
            val activity = ResumedActivityTracker.current()?.takeUnless {
                it.isFinishing || it.isDestroyed || it.supportFragmentManager.isStateSaved
            }
            if (activity == null) {
                Toast.makeText(context, "Open Stremio settings from the main screen", Toast.LENGTH_LONG).show()
            } else {
                StremioSettingsFragment().show(activity.supportFragmentManager, TAG_SETTINGS)
            }
        }
    }

    private companion object {
        const val TAG_SETTINGS = "StremioSettings"
    }
}

private const val SUBTITLE_LIMIT = 300

class StremioProvider(
    private val repository: StremioRepository,
    name: String,
) : MainAPI() {
    override var mainUrl = profileMainUrl(repository.profileId)
    override var name = name
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Others)

    override var sequentialMainPage = false

    override val getMainPageTimeoutMs = 110_000L

    override val mainPage = mainPageOf(name to mainUrl)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (repository.addonCount() == 0) return newHomePageResponse(emptyList(), hasNext = false)
        val rows = resultOr(emptyList()) { repository.catalogRows(page) }
        val lists = rows.map { row ->
            HomePageList(row.title, row.items.mapNotNull { it.toSearchResponse() })
        }.filter { it.list.isNotEmpty() }
        return newHomePageResponse(lists, hasNext = lists.isNotEmpty())
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = resultOr(null) { search(query) }

    override suspend fun search(query: String): List<SearchResponse> {
        if (repository.addonCount() == 0) return emptyList()
        return resultOr(emptyList()) { repository.searchAll(query) }.mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val ref = parseLinkRef(url) ?: return null
        val details = resultOr(null) { repository.metaDetails(ref) } ?: return null
        val safeType = canonicalType(details.type.ifEmpty { ref.type }, details.videos.isNotEmpty())
        val payload = LinkRef(ref.base, safeType, details.id).toJsonString()
        val screenType = contentTypeOf(safeType)

        if (details.videos.isEmpty()) {
            return newMovieLoadResponse(details.name, payload, screenType, payload) {
                applyDetails(details)
            }
        }

        val episodes = details.videos.map { video ->
            newEpisode(LinkRef(ref.base, safeType, video.id).toJsonString()) {
                name = video.title
                season = video.season
                episode = video.episode
                posterUrl = video.thumbnail ?: details.poster
                description = video.overview
                video.released?.let { date = it }
            }
        }
        return newTvSeriesLoadResponse(details.name, payload, screenType, episodes) {
            applyDetails(details)
        }
    }

    private suspend fun LoadResponse.applyDetails(details: MetaDetails) {
        posterUrl = details.poster
        backgroundPosterUrl = details.background
        plot = details.description
        year = details.year
        tags = details.genres.takeIf { it.isNotEmpty() }
        score = Score.from10(details.rating?.toString())
        addActors(details.cast)
        details.id.takeIf { it.matches(Regex("^tt\\d+$")) }?.let { addImdbId(it) }
        details.tmdbId?.takeIf { it.isNotEmpty() }?.let { addTMDbId(it) }
        details.kitsuId?.takeIf { it.isNotEmpty() }?.let { addKitsuId(it) }
        details.trailerYoutubeIds.firstOrNull()?.let { addTrailer("https://www.youtube.com/watch?v=$it") }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val ref = parseLinkRef(data) ?: return false
        val ua = repository.userAgentValue()
        val emitted = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val inlineSubs = java.util.concurrent.CopyOnWriteArrayList<RemoteSubtitle>()
        val youtubeIds = java.util.Collections.synchronizedList(mutableListOf<String>())
        val externalUrls = java.util.Collections.synchronizedList(mutableListOf<String>())
        val skipped = java.util.Collections.synchronizedList(mutableListOf<String>())
        var shown = 0

        val total = resultOr(0) {
            repository.forEachStreamBatch(ref) { addon, streams ->
                val links = sortAndDedupe(
                    streams.mapNotNull { toStreamLink(it, addon.displayName, addon.order, ua) }
                )
                for (link in links) {
                    if (!link.kind.isPlayable) continue
                    if (!emitted.add(link.url)) continue
                    shown++
                    callback(
                        newExtractorLink(link.source, link.title, link.url, link.kind.linkType()) {
                            quality = qualityValue(link.qualityTag)
                            headers = link.headers
                            link.referer?.let { referer = it }
                        }
                    )
                }
                skipped.addAll(repository.undeliverableIn(streams))
                inlineSubs.addAll(repository.inlineSubtitlesIn(streams))
                youtubeIds.addAll(repository.youtubeIdsIn(streams))
                externalUrls.addAll(repository.externalUrlsIn(streams))
            }
        }

        youtubeIds.forEach { ytId ->
            resultOr(Unit) { loadExtractor("https://www.youtube.com/watch?v=$ytId", subtitleCallback, callback) }
        }
        externalUrls.forEach { ext ->
            resultOr(Unit) { loadExtractor(ext, subtitleCallback, callback) }
        }

        val remote = resultOr(emptyList()) { repository.subtitlesFor(ref) }
        val streamId = resultOr(null) { repository.resolveStreamId(ref.type, ref.id) }
        val global = if (streamId != null && remote.isEmpty() && inlineSubs.isEmpty()) {
            resultOr(emptyList()) { repository.globalSubtitles(streamId) }
        } else {
            emptyList()
        }
        listOf(remote, inlineSubs, global)
            .flatten()
            .distinctBy { it.url }
            .take(SUBTITLE_LIMIT)
            .forEach { sub ->
                resultOr(Unit) {
                    val name = sanitizeSubtitleLang(sub.lang)
                    subtitleCallback(newSubtitleFile(name, sub.url))
                }
            }

        if (skipped.isNotEmpty()) {
            Log.w(
                StremioConstants.TAG,
                "${skipped.size} stream(s) not playable here: ${skipped.distinct().take(8).joinToString("; ")}",
            )
        }
        Log.i(
            StremioConstants.TAG,
            "streams id=${ref.id} addonsReturned=$total shown=$shown youtube=${youtubeIds.size} external=${externalUrls.size}",
        )
        return shown > 0 || youtubeIds.isNotEmpty() || externalUrls.isNotEmpty()
    }

    private fun MetaRef.toSearchResponse(): SearchResponse? {
        if (id.isEmpty() || name.isEmpty()) return null
        return newMovieSearchResponse(name, LinkRef(base, type, id).toJsonString(), contentTypeOf(type)) {
            posterUrl = poster
        }
    }

    private fun canonicalType(type: String, hasEpisodes: Boolean): String =
        when (type.lowercase(Locale.ROOT)) {
            "movie", "series", "anime", "hentai", "tv", "channel", "live", "livestream",
            "iptv", "sport", "short",
            -> type.lowercase(Locale.ROOT)
            else -> if (hasEpisodes) "series" else "movie"
        }

    private fun contentTypeOf(type: String): TvType = when (type.lowercase(Locale.ROOT)) {
        "movie", "short" -> TvType.Movie
        "series", "anime", "hentai" -> TvType.TvSeries
        "tv", "channel", "live", "livestream", "iptv", "sport" -> TvType.Others
        else -> TvType.Movie
    }
}

object ResumedActivityTracker : Application.ActivityLifecycleCallbacks {
    private val alive = mutableListOf<WeakReference<FragmentActivity>>()
    private var resumed: WeakReference<FragmentActivity>? = null

    fun current(): FragmentActivity? {
        synchronized(alive) {
            val it = alive.iterator()
            while (it.hasNext()) {
                val activity = it.next().get()
                if (activity == null || activity.isFinishing || activity.isDestroyed) it.remove()
            }
        }
        resumed?.get()?.let { return it }
        return alive.lastOrNull()?.get()
    }

    fun install(app: Application) {
        app.unregisterActivityLifecycleCallbacks(this)
        app.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) {
        (activity as? FragmentActivity)?.let {
            synchronized(alive) { alive.add(WeakReference(it)) }
        }
    }

    override fun onActivityResumed(activity: Activity) {
        (activity as? FragmentActivity)?.let { resumed = WeakReference(it) }
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed?.get() === activity) resumed = null
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (resumed?.get() === activity) resumed = null
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
}
