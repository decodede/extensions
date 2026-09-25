package com.stremiouniversal
import android.content.SharedPreferences
import android.util.Log
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import org.json.JSONArray
import org.json.JSONObject
class StremioRepository(prefs: SharedPreferences?) {
    private val prefs: SharedPreferences? = prefs
    companion object {
        private const val MANIFEST_TTL_MS = 24L * 60 * 60 * 1000
        private const val TRACKER_TTL_MS = 6L * 60 * 60 * 1000
        private data class TimedManifest(val at: Long, val manifest: StremioManifest)
        private val manifests = java.util.concurrent.ConcurrentHashMap<String, TimedManifest>()
        @Volatile private var cachedTrackers: List<String>? = null
        @Volatile private var cachedTrackersAt: Long = 0
    }
    private fun safeEncode(value: String): String? = runCatching {
        java.net.URLEncoder.encode(value, "UTF-8")
    }.getOrNull()
    private fun safeGetUrl(url: String, suffix: String): String? {
        val full = withQuery(url, suffix)
        if (full.length > 4096 || full.contains(Regex("\\s"))) return null
        if (!full.startsWith("http://") && !full.startsWith("https://")) return null
        if (!suffix.isValidQuerySuffix()) return null
        return full
    }
    private suspend inline fun <reified T : Any> fetchJson(url: String, timeout: Long): T? {
        repeat(3) { attempt ->
            resultOr(null) { app.get(url, timeout = timeout).parsedSafe<T>() }?.let { return it }
            if (attempt < 2) kotlinx.coroutines.delay(500L * (attempt + 1))
        }
        Log.w("StremioCS", "fetch failed host=" + url.substringAfter("://").substringBefore("/"))
        return null
    }
    fun loadConfiguredAddons(): List<AddonConfig> {
        val raw = prefs?.getString(StremioConstants.KEY_ADDONS, null)
        if (raw.isNullOrBlank()) return migrateLegacyAddons()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val rawUrl = obj.optString("url").ifEmpty { obj.optString("manifestUrl") }
                val normalized = normalizeAddonUrl(rawUrl) ?: return@mapNotNull null
                AddonConfig(
                    name = obj.optString("name").trim(),
                    manifestUrl = normalized,
                    enabled = obj.optBoolean("enabled", true)
                )
            }
        }.getOrElse {
            Log.e("StremioCS", "loadConfiguredAddons: corrupt JSON, trying legacy keys", it)
            migrateLegacyAddons()
        }
    }

    private fun migrateLegacyAddons(): List<AddonConfig> {
        val p = prefs ?: return emptyList()
        val found = mutableListOf<AddonConfig>()
        try {
            var index = 0
            while (true) {
                val key = if (index == 0) StremioConstants.LEGACY_ADDON_PREFIX
                else StremioConstants.LEGACY_ADDON_PREFIX + (index + 1)
                if (!p.contains(key)) break
                val value = p.getString(key, "").orEmpty().trim()
                if (value.isNotEmpty()) {

                    normalizeAddonUrl(value)?.let { found.add(AddonConfig("", it)) }
                        ?: normalizeAddonUrl(value.fixSourceUrl())?.let { found.add(AddonConfig("", it)) }
                }
                index++
                if (index > 500) break
            }
            listOf(StremioConstants.LEGACY_LINKS_X, StremioConstants.LEGACY_LINKS_STREAMPLAY).forEach { blobKey ->
                val blob = p.getString(blobKey, null) ?: return@forEach
                runCatching {
                    val arr = JSONArray(blob)
                    (0 until arr.length()).mapNotNull { i ->
                        val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                        val rawUrl = obj.optString("link")
                            .ifEmpty { obj.optString("url") }
                            .ifEmpty { obj.optString("manifestUrl") }
                        val name = obj.optString("name").trim()
                        normalizeAddonUrl(rawUrl)?.let { AddonConfig(name, it) }
                    }
                }.getOrDefault(emptyList()).forEach { found.add(it) }
            }
        } catch (e: Exception) {
            Log.e("StremioCS", "migrateLegacyAddons failed", e)
            return emptyList()
        }
        val deduped = found.distinctBy { addonBaseKey(it.manifestUrl) }
        if (deduped.isEmpty()) return emptyList()
        saveAddons(deduped)
        try {
            p.edit().apply {
                val keys = p.all.keys.filter {
                    it.startsWith(StremioConstants.LEGACY_ADDON_PREFIX) ||
                        it == StremioConstants.LEGACY_LINKS_X ||
                        it == StremioConstants.LEGACY_LINKS_STREAMPLAY
                }
                keys.forEach { remove(it) }
            }.apply()
        } catch (e: Exception) {
            Log.e("StremioCS", "migrateLegacyAddons: clear legacy keys failed", e)
        }
        return deduped
    }
    fun loadEnabledAddons(): List<AddonConfig> =
        loadConfiguredAddons().filter { it.enabled }
    fun saveAddons(configs: List<AddonConfig>) {
        val arr = JSONArray()
        val now = System.currentTimeMillis()
        configs.forEach {
            arr.put(
                JSONObject()
                    .put("name", it.name)
                    .put("url", it.manifestUrl)
                    .put("enabled", it.enabled)
                    .put("addedAt", now)
            )
        }
        prefs?.edit()
            ?.putString(StremioConstants.KEY_ADDONS, arr.toString())
            ?.putInt(StremioConstants.KEY_SCHEMA_V, StremioConstants.SCHEMA_V)
            ?.apply()
        manifests.keys.retainAll(configs.map { it.manifestUrl }.toSet())
    }
    fun addAddon(manifestUrl: String): Boolean {
        val normalized = normalizeAddonUrl(manifestUrl) ?: return false
        val current = loadConfiguredAddons()
        if (current.any { addonBaseKey(it.manifestUrl) == addonBaseKey(normalized) }) return false
        saveAddons(current + AddonConfig("", normalized))
        return true
    }
    fun removeAddonAt(index: Int) {
        val current = loadConfiguredAddons().toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            saveAddons(current)
        }
    }
    fun setAddonEnabled(index: Int, enabled: Boolean) {
        val current = loadConfiguredAddons().toMutableList()
        if (index in current.indices) {
            current[index] = current[index].copy(enabled = enabled)
            saveAddons(current)
        }
    }
    fun moveAddon(from: Int, to: Int) {
        val current = loadConfiguredAddons().toMutableList()
        if (from !in current.indices || to !in current.indices || from == to) return
        val item = current.removeAt(from)
        current.add(to, item)
        saveAddons(current)
    }
    suspend fun previewAddon(manifestUrl: String): AddonPreview? {
        val normalized = normalizeAddonUrl(manifestUrl) ?: return null
        val manifest = manifestOf(normalized) ?: return null
        return AddonPreview(
            name = manifest.name?.trim()?.takeIf { it.isNotEmpty() } ?: addonDisplayHost(normalized),
            catalogCount = manifest.catalogs.size
        )
    }
    fun addonCount(): Int = loadConfiguredAddons().size
    suspend fun configuredAddons(): List<ConfiguredAddon> = supervisorScope {
        val enabled = loadEnabledAddons()
        if (enabled.isEmpty()) return@supervisorScope emptyList()
        enabled.mapIndexed { order, config ->
            async {
                val manifest = manifestOf(config.manifestUrl)
                if (manifest == null) {

                    Log.e("StremioCS", "configuredAddons: manifest null ${addonDisplayHost(config.manifestUrl)}")
                    return@async null
                }
                describe(config, order, manifest)
            }
        }.mapNotNull { resultOr(null) { it.await() } }
    }
    private suspend fun manifestOf(url: String): StremioManifest? {
        manifests[url]?.let { (at, manifest) ->
            if (System.currentTimeMillis() - at < MANIFEST_TTL_MS) return manifest
        }
        return fetchJson<StremioManifest>(url, 20)
            ?.also { manifests[url] = TimedManifest(System.currentTimeMillis(), it) }
            ?: manifests[url]?.manifest
    }
    private fun describe(config: AddonConfig, order: Int, manifest: StremioManifest): ConfiguredAddon? {
        val base = manifestBase(config.manifestUrl)
        if (base.isEmpty()) return null
        val catalogs = manifest.catalogs.flatMap { catalog ->
            if (catalog.type != null) listOf(catalog)
            else catalog.types.map { type -> catalog.copy(type = type, types = mutableListOf(type)) }
        }.filter { it.id.isNotEmpty() && it.type != null }
        return ConfiguredAddon(
            order = order,
            displayName = manifest.name?.trim()?.takeIf { it.isNotEmpty() }
                ?: config.name.ifEmpty { "Addon ${order + 1}" },
            base = base,
            querySuffix = manifestQuery(config.manifestUrl),
            idPrefixes = streamPrefixesOf(manifest),
            catalogs = catalogs,
            hasCatalog = hasResource(manifest, "catalog") || catalogs.isNotEmpty(),
            hasStream = hasResource(manifest, "stream"),
            hasMeta = hasResource(manifest, "meta"),
            hasSubtitles = hasResource(manifest, "subtitles")
        )
    }
    private fun hasResource(manifest: StremioManifest, name: String): Boolean =
        manifest.resources.any { node -> resourceMatches(node, name) }
    private fun resourceMatches(node: JsonNode, name: String): Boolean {
        if (node.isTextual) return node.asText() == name
        if (!node.isObject) return false
        val declared = node.path("name").asText("")
        val legacyId = node.path("id").asText("")
        if (declared == name || legacyId == name) return true
        return name == "subtitles" && (declared == "subtitle" || declared == "subs")
    }
    private fun streamPrefixesOf(manifest: StremioManifest): List<String> {
        manifest.resources.forEach { node ->
            if (node.isObject && node.path("name").asText("") == "stream") {
                val declared = node.path("idPrefixes")
                if (declared.isArray && declared.size() > 0) {
                    return declared.mapNotNull { it.asText(null) }
                }
            }
        }
        return manifest.idPrefixes
    }
    suspend fun catalogRows(page: Int): List<CatalogRow> = supervisorScope {
        val addons = configuredAddons().filter { it.hasCatalog }
        if (addons.isEmpty()) return@supervisorScope emptyList()
        val skip = (page - 1).coerceAtLeast(0) * 100
        addons.map { addon ->
            async {
                addon.catalogs
                    .filter { catalog -> !isSearchCatalog(catalog) }
                    .map { catalog -> async { catalogRow(addon, catalog, skip) } }
                    .mapNotNull { resultOr(null) { it.await() } }
            }
        }.flatMap { resultOr(emptyList()) { it.await() } }
    }
    private suspend fun catalogMetas(addon: ConfiguredAddon, catalog: StremioCatalog, skip: Int): List<CatalogEntry> {
        val type = catalog.type ?: return emptyList()
        val paging = if (skip > 0) "/skip=$skip" else ""
        val encType = safeEncode(type) ?: return emptyList()
        val encId = safeEncode(catalog.id) ?: return emptyList()
        val url = safeGetUrl("${addon.base}/catalog/$encType/${encId}$paging.json", addon.querySuffix) ?: return emptyList()
        return (fetchJson<CatalogResponse>(url, 20)?.metas.orEmpty())
            .filter { it.id.isNotEmpty() && it.name.isNotEmpty() }
    }
    private suspend fun catalogRow(addon: ConfiguredAddon, catalog: StremioCatalog, skip: Int): CatalogRow? {
        val type = catalog.type ?: return null
        val metas = catalogMetas(addon, catalog, skip)
        if (metas.isEmpty()) return null
        val items = metas.mapNotNull { it.toRef(addon, type) }
        if (items.isEmpty()) return null
        return CatalogRow(catalog.name?.takeIf { it.isNotBlank() } ?: catalog.id, items)
    }
    suspend fun searchAll(query: String): List<MetaRef> = supervisorScope {
        val q = query.trim()
        if (q.isEmpty()) return@supervisorScope emptyList()
        val addons = configuredAddons().filter { it.hasCatalog }
        if (addons.isEmpty()) return@supervisorScope emptyList()
        val native = addons.map { addon ->
            async {
                addon.catalogs.filter(::supportsSearch).map { catalog ->
                    async { searchCatalog(addon, catalog, q) }
                }.flatMap { resultOr(emptyList()) { it.await() } }
            }
        }.flatMap { resultOr(emptyList()) { it.await() } }
        val filtered = addons.map { addon ->
            async {
                addon.catalogs
                    .filter { !supportsSearch(it) && !isSearchCatalog(it) }
                    .map { catalog ->
                        async {
                            val type = catalog.type ?: return@async emptyList<MetaRef>()
                            catalogMetas(addon, catalog, 0)
                                .filter { matchesQuery(it, q) }
                                .mapNotNull { it.toRef(addon, type) }
                        }
                    }.flatMap { resultOr(emptyList()) { it.await() } }
            }
        }.flatMap { resultOr(emptyList()) { it.await() } }
        (native + filtered).distinctBy { "${it.type}:${it.id}" }
    }
    private suspend fun searchCatalog(addon: ConfiguredAddon, catalog: StremioCatalog, query: String): List<MetaRef> {
        val type = catalog.type ?: return emptyList()
        val encoded = safeEncode(query) ?: return emptyList()
        val encType = safeEncode(type) ?: return emptyList()
        val encId = safeEncode(catalog.id) ?: return emptyList()
        val url = safeGetUrl("${addon.base}/catalog/$encType/${encId}/search=$encoded.json", addon.querySuffix) ?: return emptyList()
        return resultOr(emptyList()) {
            app.get(url, timeout = 20).parsedSafe<CatalogResponse>()?.metas.orEmpty()
        }.mapNotNull { it.toRef(addon, type) }
    }
    suspend fun metaDetails(ref: LinkRef): MetaDetails? {
        val addons = configuredAddons()
        if (addons.isEmpty()) return null
        val wantsEpisodes = ref.type.lowercase() in setOf("series", "anime", "hentai", "sport")
        val candidates = mutableListOf<MetaDetails>()
        val origin = addons.firstOrNull { it.base == ref.base }
        if (origin != null) {
            val entry = fetchMeta(origin, ref.type, ref.id)
            if (entry != null && (entry.id == ref.id || entry.id.isEmpty())) {
                imdbIdFromLinks(entry)?.let {
                    fetchMetaByImdb(addons, ref.type, it)?.let { candidates.add(it) } ?: entry.toDetails()?.let { candidates.add(it) }
                } ?: entry.toDetails()?.let { candidates.add(it) }
            } else if (entry != null) {
                Log.w("StremioCS", "metaDetails: id mismatch requested=${ref.id} got=${entry.id}")
            }
        }
        elfhostedMeta(ref.type, ref.id)?.toDetails()?.let { candidates.add(it) }
        if (ref.id.matches(Regex("^tt\\d+$"))) {
            cinemetaMeta(ref.type, ref.id)?.toDetails()?.let { candidates.add(it) }
        }
        supervisorScope {
            addons.filter { it.hasMeta && it.base != ref.base }.map { addon ->
                async { fetchMeta(addon, ref.type, ref.id)?.toDetails() }
            }.mapNotNull { resultOr(null) { it.await() } }.forEach { candidates.add(it) }
        }
        if (candidates.isEmpty()) return null
        if (wantsEpisodes) candidates.firstOrNull { it.videos.isNotEmpty() }?.let { return it }
        return candidates.firstOrNull()
    }
    private fun imdbIdFromLinks(entry: CatalogEntry): String? {

        entry.links.firstOrNull { it.category == "imdb" }?.let { link ->
            val candidate = (link.url?.substringAfterLast("/") ?: link.id ?: "")
                .trim().substringBefore("?").substringBefore("#")
            if (candidate.matches(Regex("^tt\\d+$"))) return candidate
            val idCandidate = link.id?.trim().orEmpty()
            if (idCandidate.matches(Regex("^tt\\d+$"))) return idCandidate
        }
        return null
    }
    private suspend fun fetchMetaByImdb(addons: List<ConfiguredAddon>, type: String, imdbId: String): MetaDetails? {
        val origin = addons.firstOrNull() ?: return null
        fetchMeta(origin, type, imdbId)?.toDetails()?.let { return it }
        elfhostedMeta(type, imdbId)?.toDetails()?.let { return it }
        return cinemetaMeta(type, imdbId)?.toDetails()
    }
    private suspend fun elfhostedMeta(type: String, id: String): CatalogEntry? {
        val kind = if (type == "movie") "movie" else "series"
        val encoded = safeEncode(id)?.replace("%3A", ":") ?: return null
        return resultOr(null) {
            app.get("${StremioConstants.ELFHOSTED_BASE}/meta/$kind/$encoded.json", timeout = 20)
                .parsedSafe<CatalogResponse>()?.meta
        }?.takeIf { it.id.isEmpty() || it.id == id }
    }
    suspend fun fetchMeta(addon: ConfiguredAddon, type: String, id: String): CatalogEntry? {
        val encoded = safeEncode(id)?.replace("%3A", ":") ?: return null
        val url = safeGetUrl("${addon.base}/meta/$type/$encoded.json", addon.querySuffix) ?: return null
        repeat(3) { attempt ->
            val text = resultOr(null) {
                app.get(url, timeout = 20).text.takeIf { it.length <= 2 * 1024 * 1024 }
            }
            if (text != null) {
                val entry = extractMetaEntry(text, id) ?: return null
                if (entry.id.isNotEmpty() && entry.id != id) return null
                return entry
            }
            if (attempt < 2) kotlinx.coroutines.delay(500L * (attempt + 1))
        }
        return null
    }
    private suspend fun cinemetaMeta(type: String, id: String): CatalogEntry? {
        if (!id.matches(Regex("^tt\\d+$"))) return null
        val kinds = when (type.lowercase()) {
            "movie" -> listOf("movie")
            "series", "anime", "hentai" -> listOf("series")
            else -> listOf("movie", "series")
        }
        for (kind in kinds) {
            resultOr(null) {
                app.get("${StremioConstants.CINEMETA_BASE}/meta/$kind/$id.json", timeout = 20)
                    .parsedSafe<CatalogResponse>()?.meta
            }?.let { return it }
        }
        return null
    }

    suspend fun resolveStreamId(type: String, id: String): String {
        val clean = id.trim()
        if (clean.matches(Regex("^tt\\d+$"))) return clean
        com.lagradost.cloudstream3.imdbUrlToIdNullable(clean)?.let { return it }
        if (clean.startsWith("tmdb:")) {
            tmdbToImdb(clean.removePrefix("tmdb:"), type)?.let { return it }
            return clean
        }
        if (clean.startsWith("kitsu:")) {
            kitsuToImdb(clean.removePrefix("kitsu:"))?.let { return it }
            return clean
        }
        return clean
    }
    private suspend fun tmdbToImdb(tmdbId: String, type: String?): String? {

        val mediaType = if (type == "series") "tv" else "movie"
        val clean = tmdbId.trim().removePrefix("tmdb:").substringBefore("?").substringBefore("/")
        if (clean.isEmpty() || clean.any { !it.isDigit() }) return null
        return resultOr(null) {
            app.get(
                "https://api.themoviedb.org/3/$mediaType/$clean/external_ids",
                params = mapOf("api_key" to StremioConstants.TMDB_DEMO_KEY)
            ).parsedSafe<TmdbExternalIds>()?.imdb_id?.takeIf { it.matches(Regex("^tt\\d+$")) }
        }
    }
    private suspend fun kitsuToImdb(kitsuId: String): String? {

        val clean = kitsuId.trim().removePrefix("kitsu:").substringBefore("?").substringBefore("/")
        if (clean.isEmpty() || clean.length > 16 || clean.any { !it.isDigit() }) return null
        return resultOr(null) {
            app.get(
                "https://api.ani.zip/mappings",
                params = mapOf("kitsu_id" to clean)
            ).parsedSafe<AniZipResponse>()?.mappings?.imdb_id?.takeIf { it.matches(Regex("^tt\\d+$")) }
        }
    }
    suspend fun streamsFor(ref: LinkRef): StreamsResult = supervisorScope {

        val streamId = resolveStreamId(ref.type, ref.id)
        val streamRef = if (streamId == ref.id) ref else ref.copy(id = streamId)
        val normalized = normalizeContentId(streamRef.id)
        val targets = configuredAddons().filter { addon ->
            addon.hasStream && (addon.idPrefixes.isEmpty() || addon.idPrefixes.any { prefix ->
                prefix.isNotEmpty() && (streamRef.id.startsWith(prefix) || normalized.startsWith(prefix))
            })
        }
        if (targets.isEmpty()) return@supervisorScope StreamsResult(emptyList(), emptyList(), emptyList())

        val gate = kotlinx.coroutines.sync.Semaphore(10)
        val remoteTrackers = fetchRemoteTrackers()
        val perAddon = targets.map { addon ->
            async {
                gate.acquire()
                try {
                    addon to addonStreams(addon, streamRef, remoteTrackers)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.e("StremioCS", "streamsFor: ${addonDisplayHost(addon.base)}", e)
                    null
                } finally {
                    gate.release()
                }
            }
        }.mapNotNull { resultOr(null) { it.await() } }
        val links = sortAndDedupe(
            perAddon.flatMap { (addon, streams) ->
                streams.mapNotNull { toStreamLink(it, addon.displayName, addon.order) }
            }
        )
        val raw = perAddon.flatMap { (_, streams) -> streams }
        val result = StreamsResult(
            links = links,
            inlineSubtitles = raw.flatMap { it.subtitles }
                .mapNotNull { toRemoteSubtitle(it) }
                .distinctBy { it.url },
            youtubeIds = raw.mapNotNull { it.ytId?.let(::youtubeIdOf) }.distinct().take(100),
            externalUrls = raw.filter { !isPlaceholderStream(it.name, it.description ?: it.title, it.externalUrl) }.mapNotNull { it.externalUrl?.trim()?.takeIf { u -> u.startsWith("http://") || u.startsWith("https://") } }.distinct().take(10)
        )
        Log.i("StremioCS", "streams id=" + streamRef.id + " targets=" + targets.size + " links=" + result.links.size + " yt=" + result.youtubeIds.size + " ext=" + result.externalUrls.size)
        return@supervisorScope result
    }
    private suspend fun addonStreams(addon: ConfiguredAddon, ref: LinkRef, remoteTrackers: List<String>): List<StremioStream> {
        return streamTypesFor(ref.type).amap { kind ->
            val encoded = safeEncode(ref.id)?.replace("%3A", ":") ?: return@amap emptyList<StremioStream>()
            val url = safeGetUrl("${addon.base}/stream/$kind/$encoded.json", addon.querySuffix) ?: return@amap emptyList<StremioStream>()
            fetchJson<StreamsResponse>(url, 30)?.streams.orEmpty()
        }.flatten().map { stream ->
            if (remoteTrackers.isEmpty() || stream.infoHash.isNullOrBlank()) stream
            else stream.copy(
                sources = (stream.sources + remoteTrackers.map { "tracker:$it" }).distinct()
            )
        }
    }

    suspend fun fetchRemoteTrackers(): List<String> {
        val now = System.currentTimeMillis()
        cachedTrackers?.let { if (now - cachedTrackersAt < TRACKER_TTL_MS) return it }
        val remote = resultOr(emptyList()) {
            app.get(StremioConstants.TRACKER_LIST_URL, timeout = 15).text.take(64 * 1024)
                .lineSequence().map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .filter { it.matches(Regex("^(udp|http|https|ws)://[^\\s]+$")) }
                .take(20).toList()
        }
        if (remote.isNotEmpty()) {
            cachedTrackers = remote
            cachedTrackersAt = now
            return remote
        }
        return cachedTrackers.orEmpty()
    }

    suspend fun globalSubtitles(imdbId: String?, season: Int?, episode: Int?): List<RemoteSubtitle> {
        if (imdbId.isNullOrBlank()) return emptyList()
        if (!imdbId.matches(Regex("^tt\\d+$"))) return emptyList()
        val slug = if (season == null || episode == null) "movie/$imdbId" else "series/$imdbId:$season:$episode"
        return resultOr(emptyList()) {
            app.get("${StremioConstants.OPENSUBS_API}/subtitles/$slug.json", timeout = 30)
                .parsedSafe<SubsResponse>()?.subtitles.orEmpty()
        }.mapNotNull(::toRemoteSubtitle).distinctBy { it.url }
    }
    suspend fun subtitlesFor(ref: LinkRef): List<RemoteSubtitle> = supervisorScope {
        val subId = resolveStreamId(ref.type, ref.id)
        configuredAddons().filter { it.hasSubtitles }.map { addon ->
            async {
                val encoded = safeEncode(subId)?.replace("%3A", ":") ?: return@async emptyList<RemoteSubtitle>()
                val url = safeGetUrl("${addon.base}/subtitles/${ref.type}/$encoded.json", addon.querySuffix) ?: return@async emptyList<RemoteSubtitle>()
                resultOr(emptyList()) {
                    app.get(url, timeout = 30).parsedSafe<SubsResponse>()?.subtitles.orEmpty()
                }.mapNotNull(::toRemoteSubtitle)
            }
        }.flatMap { resultOr(emptyList()) { it.await() } }
            .distinctBy { it.url }
    }
    private fun toRemoteSubtitle(sub: StremioSubtitle): RemoteSubtitle? {
        val url = sub.url?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return null

        return RemoteSubtitle(url, subtitleLangOf(sub) ?: "en")
    }
    private fun CatalogEntry.toRef(addon: ConfiguredAddon, fallbackType: String): MetaRef? {
        if (id.isEmpty() || name.isEmpty()) return null
        return MetaRef(addon.base, type ?: fallbackType, id, name, fixPosterUrl(poster))
    }
    private fun CatalogEntry.toDetails(): MetaDetails? {
        if (name.isEmpty() || id.isEmpty()) return null
        val videos = videos.orEmpty().mapNotNull { video ->
            val vid = video.id ?: return@mapNotNull null
            VideoRef(
                id = vid,
                title = video.name ?: video.title ?: "Episode",
                season = video.season ?: 1,
                episode = video.episode ?: video.number ?: 1,
                thumbnail = fixPosterUrl(video.thumbnail),
                overview = stripHtml(video.overview ?: video.description).take(800)
            )
        }
        return MetaDetails(
            id = id,
            type = type.orEmpty(),
            name = name,
            poster = fixPosterUrl(poster),
            background = fixPosterUrl(background),
            description = stripHtml(description).take(1000),
            year = yearOf(year),
            rating = imdbRating?.asText()?.toDoubleOrNull(),
            genres = (stringList(genres) + stringList(genre)).distinct().takeIf { it.isNotEmpty() }.orEmpty(),
            cast = stringList(cast),
            trailerYoutubeIds = trailers.mapNotNull { it.source?.let(::youtubeIdOf) } +
                trailerStreams.mapNotNull { it.ytId?.let(::youtubeIdOf) },
            videos = videos
        )
    }
    private fun isSearchCatalog(catalog: StremioCatalog): Boolean =
        catalog.extra?.any { it.name == "search" && it.isRequired == true } == true
    private fun supportsSearch(catalog: StremioCatalog): Boolean =
        catalog.extra?.any { it.name == "search" } == true ||
            catalog.extraSupported?.contains("search") == true
}
