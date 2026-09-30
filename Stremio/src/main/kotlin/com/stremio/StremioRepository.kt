package com.stremio

import android.content.SharedPreferences
import android.util.Log
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.imdbUrlToIdNullable
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = StremioConstants.TAG

class StremioRepository(
    prefs: SharedPreferences?,
    val profileId: String = DEFAULT_PROFILE_ID,
) {
    private val prefs: SharedPreferences? = prefs
    private val addonsKey = profileAddonsKey(profileId)
    private val defaultsKey = profileDefaultsKey(profileId)

    fun forProfile(id: String): StremioRepository =
        if (id == profileId) this else StremioRepository(prefs, id)

    companion object {
        private const val MANIFEST_TTL_MS = 24L * 60 * 60 * 1000
        private const val TRACKER_TTL_MS = 6L * 60 * 60 * 1000
        private const val CATALOG_PAGE_SIZE = 100
        private const val MAX_META_BYTES = 16 * 1024 * 1024

        private val EPISODE_TYPES = setOf("series", "anime", "hentai", "sport")

        private const val STREAM_FANOUT_TIMEOUT_MS = 55_000L
        private const val CATALOG_FANOUT_TIMEOUT_MS = 45_000L
        private const val ADDON_TIMEOUT_MS = 30_000L
        private const val SUBTITLE_FETCH_LIMIT = 300

        private data class TimedManifest(val at: Long, val manifest: StremioManifest)
        private val manifests = ConcurrentHashMap<String, TimedManifest>()

        @Volatile private var cachedTrackers: List<String>? = null
        @Volatile private var cachedTrackersAt: Long = 0

        private val catalogSeen = ConcurrentHashMap<String, MutableSet<String>>()

        private const val SEEN_CAP = 4000
    }

    fun userAgentValue(): String = userAgent()

    private fun userAgent(): String =
        prefs?.getString(StremioConstants.KEY_UA_PRESET, StremioConstants.UA_DESKTOP)
            ?: StremioConstants.UA_DESKTOP

    private fun appendTrackers(): Boolean =
        prefs?.getBoolean(StremioConstants.KEY_APPEND_TRACKERS, true) ?: true

    private fun openSubsFallback(): Boolean =
        prefs?.getBoolean(StremioConstants.KEY_OPENSUBS, true) ?: true

    private fun debug(): Boolean = prefs?.getBoolean(StremioConstants.KEY_DEBUG, false) ?: false

    fun enabledDefaults(): Set<String> {
        val raw = prefs?.getString(defaultsKey, null).orEmpty()
        if (raw.isBlank()) return emptySet()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optString(it).trim().takeIf(String::isNotEmpty) }
                .toSet()
        }.getOrElse {
            Log.w(TAG, "enabledDefaults: corrupt JSON, treating as none", it)
            emptySet()
        }
    }

    fun isDefaultEnabled(manifestUrl: String): Boolean = manifestUrl in enabledDefaults()

    fun setDefaultEnabled(manifestUrl: String, enabled: Boolean) {
        if (manifestUrl !in StremioDefaultAddons.all) {
            Log.w(TAG, "setDefaultEnabled: not a shipped default")
            return
        }
        val next = if (enabled) enabledDefaults() + manifestUrl else enabledDefaults() - manifestUrl
        val arr = JSONArray()
        StremioDefaultAddons.all.filter { it in next }.forEach { arr.put(it) }
        prefs?.edit()
            ?.putString(defaultsKey, arr.toString())
            ?.putInt(StremioConstants.KEY_SCHEMA_V, StremioConstants.SCHEMA_V)
            ?.apply()
        manifests.clear()
    }

    fun setBehavior(key: String, value: String) {
        prefs?.edit()?.putString(key, value)?.apply()
    }

    fun setBehavior(key: String, value: Boolean) {
        prefs?.edit()?.putBoolean(key, value)?.apply()
    }

    fun behaviorString(key: String): String? = prefs?.getString(key, null)

    fun behaviorBoolean(key: String, fallback: Boolean): Boolean =
        prefs?.getBoolean(key, fallback) ?: fallback

    fun loadConfiguredAddons(): List<AddonConfig> {
        val stored = storedAddons()
        val defaults = StremioDefaultAddons.all.filter { it in enabledDefaults() }
        if (defaults.isEmpty()) return stored
        return stored + defaults.map { AddonConfig(name = "", manifestUrl = it, enabled = true) }
    }

    private fun storedAddons(): List<AddonConfig> {
        val raw = prefs?.getString(addonsKey, null)
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
                    enabled = obj.optBoolean("enabled", true),
                )
            }
        }.getOrElse {
            Log.e(TAG, "storedAddons: corrupt JSON, resetting that profile", it)
            writeAddons(emptyList())
            emptyList()
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
            listOf(StremioConstants.LEGACY_LINKS_X, StremioConstants.LEGACY_LINKS_STREAMPLAY)
                .forEach { blobKey ->
                    val blob = p.getString(blobKey, null) ?: return@forEach
                    runCatching {
                        val arr = JSONArray(blob)
                        (0 until arr.length()).mapNotNull { i ->
                            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                            val rawUrl = obj.optString("link")
                                .ifEmpty { obj.optString("url") }
                                .ifEmpty { obj.optString("manifestUrl") }
                            normalizeAddonUrl(rawUrl)
                                ?.let { AddonConfig(obj.optString("name").trim(), it) }
                        }
                    }.getOrDefault(emptyList()).forEach { found.add(it) }
                }
        } catch (e: Exception) {
            Log.e(TAG, "migrateLegacyAddons failed", e)
            return emptyList()
        }
        val deduped = found.distinctBy { addonBaseKey(it.manifestUrl) }
        if (deduped.isEmpty()) return emptyList()
        writeAddons(deduped)
        try {
            val legacyAddonKey = Regex("^" + Regex.escape(StremioConstants.LEGACY_ADDON_PREFIX) + "\\d*$")
            p.edit().apply {
                p.all.keys
                    .filter { legacyAddonKey.matches(it) }
                    .forEach { remove(it) }
                remove(StremioConstants.LEGACY_LINKS_X)
                remove(StremioConstants.LEGACY_LINKS_STREAMPLAY)
            }.apply()
        } catch (e: Exception) {
            Log.e(TAG, "migrateLegacyAddons: clear legacy keys failed", e)
        }
        return deduped
    }

    fun userAddons(): List<AddonConfig> = storedAddons()

    fun loadEnabledAddons(): List<AddonConfig> = loadConfiguredAddons().filter { it.enabled }

    private fun writeAddons(configs: List<AddonConfig>) {
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
            ?.putString(addonsKey, arr.toString())
            ?.putInt(StremioConstants.KEY_SCHEMA_V, StremioConstants.SCHEMA_V)
            ?.apply()
    }

    fun profiles(): List<StremioProfile> {
        val stored = readProfiles()
        return listOf(StremioProfile(DEFAULT_PROFILE_ID, defaultProfileName())) + stored
    }

    fun createProfile(requestedName: String): StremioProfile? {
        val name = uniqueProfileName(requestedName, profiles().map { it.name })
        if (name.isEmpty()) return null
        val existing = readProfiles()
        val profile = StremioProfile(freshId(existing), name)
        prefs?.edit()?.putString(StremioConstants.KEY_PROFILES, encodeProfiles(existing + profile))?.apply()
        return profile
    }

    fun renameProfile(id: String, requestedName: String): Boolean {
        if (id == DEFAULT_PROFILE_ID) {
            val others = profiles().filterNot { it.id == id }.map { it.name }
            val name = uniqueProfileName(requestedName, others)
            if (name.isEmpty()) return false
            prefs?.edit()?.putString(StremioConstants.KEY_DEFAULT_PROFILE_NAME, name)?.apply()
            return true
        }
        val existing = readProfiles()
        val index = existing.indexOfFirst { it.id == id }
        if (index < 0) return false
        val others = profiles().filter { it.id != id }.map { it.name }
        val name = uniqueProfileName(requestedName, others)
        if (name.isEmpty()) return false
        val updated = existing.toMutableList().apply { this[index] = existing[index].copy(name = name) }
        prefs?.edit()?.putString(StremioConstants.KEY_PROFILES, encodeProfiles(updated))?.apply()
        return true
    }

    fun deleteProfile(id: String): Boolean {
        if (id == DEFAULT_PROFILE_ID) return false
        val existing = readProfiles()
        if (existing.none { it.id == id }) return false
        prefs?.edit()
            ?.putString(StremioConstants.KEY_PROFILES, encodeProfiles(existing.filterNot { it.id == id }))
            ?.remove(profileAddonsKey(id))
            ?.remove(profileDefaultsKey(id))
            ?.apply()
        catalogSeen.keys.removeIf { it.startsWith(profileCatalogKey(id) + "|") }
        return true
    }

    private fun defaultProfileName(): String =
        prefs?.getString(StremioConstants.KEY_DEFAULT_PROFILE_NAME, null) ?: StremioConstants.PROVIDER_NAME

    private fun freshId(existing: List<StremioProfile>): String {
        val taken = existing.mapTo(HashSet()) { it.id }
        while (true) {
            val candidate = newProfileId()
            if (taken.add(candidate)) return candidate
        }
    }

    private fun readProfiles(): List<StremioProfile> {
        val raw = prefs?.getString(StremioConstants.KEY_PROFILES, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optString("id").trim()
                val name = sanitizeProfileName(obj.optString("name"))
                if (id.isEmpty() || name.isEmpty() || id == DEFAULT_PROFILE_ID) null
                else StremioProfile(id, name)
            }.distinctBy { it.id }
        }.getOrElse {
            Log.w(TAG, "readProfiles: corrupt JSON, treating as none", it)
            emptyList()
        }
    }

    private fun encodeProfiles(list: List<StremioProfile>): String {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name)) }
        return arr.toString()
    }

    fun addAddon(manifestUrl: String): Boolean {
        val normalized = normalizeAddonUrl(manifestUrl) ?: return false
        val current = storedAddons()
        if (current.any { addonBaseKey(it.manifestUrl) == addonBaseKey(normalized) }) return false
        if (enabledDefaults().any { addonBaseKey(it) == addonBaseKey(normalized) }) return false
        writeAddons(current + AddonConfig("", normalized))
        return true
    }

    fun removeAddonAt(index: Int) {
        val current = userAddons().toMutableList()
        if (index !in current.indices) return
        current.removeAt(index)
        writeAddons(current)
    }

    fun setAddonEnabled(index: Int, enabled: Boolean) {
        val current = userAddons().toMutableList()
        if (index !in current.indices) return
        current[index] = current[index].copy(enabled = enabled)
        writeAddons(current)
    }

    fun moveAddon(from: Int, to: Int) {
        val current = userAddons().toMutableList()
        if (from !in current.indices || to !in current.indices || from == to) return
        val item = current.removeAt(from)
        current.add(to, item)
        writeAddons(current)
    }

    suspend fun previewAddon(manifestUrl: String): AddonPreview? {
        val normalized = normalizeAddonUrl(manifestUrl) ?: return null
        val manifest = manifestOf(normalized) ?: return null
        return AddonPreview(
            name = manifest.name?.trim()?.takeIf { it.isNotEmpty() } ?: addonDisplayHost(normalized),
            catalogCount = manifest.catalogs.size,
        )
    }

    fun addonCount(): Int = loadEnabledAddons().size

    suspend fun configuredAddons(): List<ConfiguredAddon> = supervisorScope {
        val enabled = loadEnabledAddons()
        if (enabled.isEmpty()) return@supervisorScope emptyList()
        enabled.mapIndexed { order, config ->
            async {
                val manifest = manifestOf(config.manifestUrl)
                if (manifest == null) {
                    Log.e(TAG, "configuredAddons: manifest null ${addonDisplayHost(config.manifestUrl)}")
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
        return fetchJson<StremioManifest>(addonApiUrl(url), 20)
            ?.also { manifests[url] = TimedManifest(System.currentTimeMillis(), it) }
            ?: manifests[url]?.manifest
    }

    private fun describe(
        config: AddonConfig,
        order: Int,
        manifest: StremioManifest,
    ): ConfiguredAddon? {
        val base = manifestBase(config.manifestUrl)
        if (base.isEmpty()) return null
        val catalogs = manifest.catalogs.flatMap { catalog ->
            (listOfNotNull(catalog.type) + catalog.types).distinct()
                .map { type -> catalog.copy(type = type) }
        }.filter { it.id.isNotEmpty() }
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
            hasSubtitles = hasResource(manifest, "subtitles"),
            version = manifest.version,
            needsConfiguration = manifest.behaviorHints?.configurationRequired == true ||
                manifest.config?.any { it.required == true } == true,
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
            if (node.isObject && resourceMatches(node, "stream")) {
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
        if (page <= 1) catalogSeen.keys.removeIf { it.startsWith(profileCatalogKey(profileId) + "|") }
        val skip = (page - 1).coerceAtLeast(0).toLong().times(CATALOG_PAGE_SIZE).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val rows = withTimeoutOrNull(CATALOG_FANOUT_TIMEOUT_MS) {
            addons.map { addon ->
                async {
                    addon.catalogs
                        .filter { catalog -> !isSearchCatalog(catalog) }
                        .map { catalog -> async { catalogRow(addon, catalog, skip) } }
                        .mapNotNull { resultOr(null) { it.await() } }
                }
            }.flatMap { resultOr(emptyList()) { it.await() } }
        }.orEmpty()
        if (debug()) Log.i(TAG, "catalogRows page=$page rows=${rows.size} items=${rows.sumOf { it.items.size }}")
        rows
    }

    private suspend fun catalogMetas(
        addon: ConfiguredAddon,
        catalog: StremioCatalog,
        skip: Int,
    ): List<CatalogEntry> {
        val type = catalog.type ?: return emptyList()
        val paging = if (skip > 0) "/skip=$skip" else ""
        val url = addonUrl(addon, "/catalog/$type/${catalog.id}$paging.json") ?: return emptyList()
        return (fetchJson<CatalogResponse>(url, 20)?.metas.orEmpty())
            .filter { it.id.isNotEmpty() && it.name.isNotEmpty() }
    }

    private suspend fun catalogRow(addon: ConfiguredAddon, catalog: StremioCatalog, skip: Int): CatalogRow? {
        val type = catalog.type ?: return null
        val metas = catalogMetas(addon, catalog, skip)
        if (metas.isEmpty()) return null
        val seen = catalogSeen.computeIfAbsent(
            catalogSeenKey(profileId, addon.base, catalog.id)
        ) { ConcurrentHashMap.newKeySet() }
        if (seen.size > SEEN_CAP) seen.clear()
        val items = metas
            .mapNotNull { it.toRef(addon, type) }
            .filter { seen.add("${it.type}:${it.id}") }
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

    private suspend fun searchCatalog(
        addon: ConfiguredAddon,
        catalog: StremioCatalog,
        query: String,
    ): List<MetaRef> {
        val type = catalog.type ?: return emptyList()
        val encoded = encodePathSegment(query) ?: return emptyList()
        val url = addonUrl(addon, "/catalog/$type/${catalog.id}/search=$encoded.json") ?: return emptyList()
        return resultOr(emptyList()) {
            app.get(url, timeout = 25).parsedSafe<CatalogResponse>()?.metas.orEmpty()
        }.mapNotNull { it.toRef(addon, type) }
    }

    private fun isSearchCatalog(catalog: StremioCatalog): Boolean =
        catalog.extra?.any { it.name == "search" && it.isRequired == true } == true

    private fun supportsSearch(catalog: StremioCatalog): Boolean =
        catalog.extra?.any { it.name == "search" } == true ||
            catalog.extraSupported?.contains("search") == true

    suspend fun metaDetails(ref: LinkRef): MetaDetails? {
        val addons = configuredAddons()
        if (addons.isEmpty()) return null
        val wantsEpisodes = ref.type.lowercase(Locale.ROOT) in EPISODE_TYPES
        val candidates = mutableListOf<MetaDetails>()
        val origin = addons.firstOrNull { it.base == ref.base }
        if (origin != null) {
            val entry = fetchMeta(origin, ref.type, ref.id)
            if (entry != null && (entry.id == ref.id || entry.id.isEmpty())) {
                imdbIdFromLinks(entry)?.let { imdbId ->
                    fetchMetaByImdb(addons, ref.type, imdbId)?.let { candidates.add(it) }
                        ?: entry.toDetails()?.let { candidates.add(it) }
                } ?: entry.toDetails()?.let { candidates.add(it) }
            } else if (entry != null) {
                Log.w(TAG, "metaDetails: id mismatch requested=${ref.id} got=${entry.id}")
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
        entry.links.firstOrNull { it.category.equals("imdb", ignoreCase = true) }?.let { link ->
            val fromUrl = (link.url?.substringAfterLast("/") ?: link.id ?: "")
                .trim().substringBefore("?").substringBefore("#")
            if (fromUrl.matches(Regex("^tt\\d+$"))) return fromUrl
            link.id?.trim()?.takeIf { it.matches(Regex("^tt\\d+$")) }?.let { return it }
        }
        return null
    }

    private suspend fun fetchMetaByImdb(
        addons: List<ConfiguredAddon>,
        type: String,
        imdbId: String,
    ): MetaDetails? {
        addons.firstOrNull()?.let { fetchMeta(it, type, imdbId)?.toDetails()?.let { return it } }
        elfhostedMeta(type, imdbId)?.toDetails()?.let { return it }
        return cinemetaMeta(type, imdbId)?.toDetails()
    }

    private suspend fun elfhostedMeta(type: String, id: String): CatalogEntry? {
        val kind = if (type.equals("movie", ignoreCase = true)) "movie" else "series"
        val encoded = encodePathSegment(id) ?: return null
        return resultOr(null) {
            app.get("${StremioConstants.ELFHOSTED_BASE}/meta/$kind/$encoded.json", timeout = 25)
                .parsedSafe<CatalogResponse>()?.meta
        }?.takeIf { it.id.isEmpty() || it.id == id }
    }

    suspend fun fetchMeta(addon: ConfiguredAddon, type: String, id: String): CatalogEntry? {
        val encoded = encodePathSegment(id) ?: return null
        val url = addonUrl(addon, "/meta/$type/$encoded.json") ?: return null
        repeat(2) { attempt ->
            val text = resultOr(null) {
                app.get(url, timeout = 25, headers = apiHeaders()).text.takeIf { it.length <= MAX_META_BYTES }
            }
            if (text != null) {
                val entry = extractMetaEntry(text, id) ?: return null
                if (entry.id.isNotEmpty() && entry.id != id) return null
                return entry
            }
            if (attempt < 1) delay(500L)
        }
        return null
    }

    private suspend fun cinemetaMeta(type: String, id: String): CatalogEntry? {
        if (!id.matches(Regex("^tt\\d+$"))) return null
        val kinds = when (type.lowercase(Locale.ROOT)) {
            "movie" -> listOf("movie")
            "series", "anime", "hentai" -> listOf("series")
            else -> listOf("movie", "series")
        }
        for (kind in kinds) {
            resultOr(null) {
                app.get("${StremioConstants.CINEMETA_BASE}/meta/$kind/$id.json", timeout = 25, headers = apiHeaders())
                    .parsedSafe<CatalogResponse>()?.meta
            }?.let { return it }
        }
        return null
    }

    suspend fun resolveStreamId(type: String, id: String): String {
        val clean = id.trim()
        if (clean.matches(Regex("^tt\\d+(:\\d+)*$"))) return clean
        if (clean.matches(Regex("^tt\\d+$"))) return clean
        if (clean.startsWith("tmdb:")) {
            tmdbToImdb(clean.removePrefix("tmdb:"))?.let { return it }
            return clean
        }
        if (clean.startsWith("kitsu:")) {
            kitsuToImdb(clean.removePrefix("kitsu:"))?.let { return it }
            return clean
        }
        if (!clean.contains('/')) return clean
        imdbUrlToIdNullable(clean)?.let { return it }
        return clean
    }

    private suspend fun tmdbToImdb(tmdbId: String): String? {
        val clean = tmdbId.trim().removePrefix("tmdb:").substringBefore("?").substringBefore("/")
        if (clean.isEmpty() || clean.any { !it.isDigit() }) return null
        return resultOr(null) {
            app.get(
                "https://api.themoviedb.org/3/find/$clean",
                params = mapOf("api_key" to StremioConstants.TMDB_DEMO_KEY, "external_source" to "imdb_id"),
                timeout = 25,
                headers = apiHeaders(),
            ).parsedSafe<TmdbFindResponse>()
        }?.imdbResults?.firstOrNull { it.imdbId?.startsWith("tt") == true }?.imdbId
            ?.takeIf { it.matches(Regex("^tt\\d+$")) }
    }

    private suspend fun kitsuToImdb(kitsuId: String): String? {
        val clean = kitsuId.trim().removePrefix("kitsu:").substringBefore("?").substringBefore("/")
        if (clean.isEmpty() || clean.length > 16 || clean.any { !it.isDigit() }) return null
        return resultOr(null) {
            app.get(
                "https://api.ani.zip/mappings",
                params = mapOf("kitsu_id" to clean),
                timeout = 25,
                headers = apiHeaders(),
            ).parsedSafe<AniZipResponse>()
        }?.mappings?.imdbId?.takeIf { it.matches(Regex("^tt\\d+$")) }
    }

    suspend fun forEachStreamBatch(
        ref: LinkRef,
        onStreams: suspend (ConfiguredAddon, List<StremioStream>) -> Unit,
    ): Int = supervisorScope {
        val streamId = resolveStreamId(ref.type, ref.id)
        val streamRef = if (streamId == ref.id) ref else ref.copy(id = streamId)
        val normalized = normalizeContentId(streamRef.id)
        val targets = configuredAddons().filter { addon ->
            addon.hasStream && (addon.idPrefixes.isEmpty() || addon.idPrefixes.any { prefix ->
                prefix.isNotEmpty() && (streamRef.id.startsWith(prefix) || normalized.startsWith(prefix))
            })
        }
        if (targets.isEmpty()) return@supervisorScope 0
        val trackers = if (appendTrackers()) fetchRemoteTrackers() else emptyList()
        val count = java.util.concurrent.atomic.AtomicInteger()
        withTimeoutOrNull(STREAM_FANOUT_TIMEOUT_MS) {
            targets.map { addon ->
                launch {
                    val streams = withTimeoutOrNull(ADDON_TIMEOUT_MS) {
                        addonStreams(addon, streamRef, trackers)
                    } ?: return@launch
                    if (streams.isEmpty()) return@launch
                    count.addAndGet(streams.size)
                    runCatching { onStreams(addon, streams) }
                }
            }.joinAll()
        }
        count.get()
    }

    fun undeliverableIn(streams: List<StremioStream>): List<String> =
        streams.mapNotNull { stream ->
            val kind = classifyUndeliverable(stream)
            if (kind == null) null
            else "${stream.name ?: stream.description ?: "(unnamed)"} — ${kind.rejectionReason()}"
        }

    fun inlineSubtitlesIn(streams: List<StremioStream>): List<RemoteSubtitle> =
        streams.asSequence().flatMap { it.subtitles.asSequence() }
            .mapNotNull { toRemoteSubtitle(it) }
            .distinctBy { it.url }
            .take(SUBTITLE_FETCH_LIMIT)
            .toList()

    fun youtubeIdsIn(streams: List<StremioStream>): List<String> =
        streams.mapNotNull { it.ytId?.let(::youtubeIdOf) }.distinct().take(100)

    fun externalUrlsIn(streams: List<StremioStream>): List<String> =
        streams
            .filter { !isPlaceholderStream(it.name, it.description ?: it.title, it.externalUrl) }
            .mapNotNull { it.externalUrl?.trim()?.takeIf { u -> u.startsWith("http") } }
            .distinct().take(10)

    private fun classifyUndeliverable(stream: StremioStream): StreamKind? = when {
        stream.anyArchiveUrl() != null -> StreamKind.ARCHIVE
        stream.url.isNullOrBlank() && stream.infoHash.isNullOrBlank() &&
            stream.ytId.isNullOrBlank() && stream.externalUrl.isNullOrBlank() -> StreamKind.NONE
        else -> streamKindOf(stream.url, stream.infoHash?.isNotBlank() == true)
            .takeIf { !it.isPlayable }
    }

    private suspend fun addonStreams(
        addon: ConfiguredAddon,
        ref: LinkRef,
        remoteTrackers: List<String>,
    ): List<StremioStream> =
        streamTypesFor(ref.type).amap { kind ->
            val encoded = encodePathSegment(ref.id) ?: return@amap emptyList<StremioStream>()
            val url = addonUrl(addon, "/stream/$kind/$encoded.json") ?: return@amap emptyList<StremioStream>()
            fetchJson<StreamsResponse>(url, 30, apiHeaders())?.streams.orEmpty()
        }.flatten().map { stream ->
            if (remoteTrackers.isEmpty() || stream.infoHash.isNullOrBlank()) {
                stream
            } else {
                stream.copy(
                    sources = (stream.sources + remoteTrackers.map { "tracker:$it" }).distinct()
                )
            }
        }

    suspend fun fetchRemoteTrackers(): List<String> {
        val now = System.currentTimeMillis()
        cachedTrackers?.let { if (now - cachedTrackersAt < TRACKER_TTL_MS) return it }
        val remote = resultOr(emptyList()) {
            app.get(StremioConstants.TRACKER_LIST_URL, timeout = 30, headers = apiHeaders())
                .text.take(64 * 1024)
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

    suspend fun globalSubtitles(streamId: String?): List<RemoteSubtitle> {
        if (!openSubsFallback()) return emptyList()
        val slug = subtitleSlugFor(streamId) ?: return emptyList()
        return resultOr(emptyList()) {
            app.get("${StremioConstants.OPENSUBS_API}/subtitles/$slug.json", timeout = 30, headers = apiHeaders())
                .parsedSafe<SubsResponse>()?.subtitles.orEmpty()
        }.mapNotNull { toRemoteSubtitle(it) }.distinctBy { it.url }
    }

    suspend fun subtitlesFor(ref: LinkRef): List<RemoteSubtitle> = supervisorScope {
        val subId = resolveStreamId(ref.type, ref.id)
        configuredAddons().filter { it.hasSubtitles }.map { addon ->
            async {
                val encoded = encodePathSegment(subId) ?: return@async emptyList<RemoteSubtitle>()
                val url = addonUrl(addon, "/subtitles/${ref.type}/$encoded.json")
                    ?: return@async emptyList<RemoteSubtitle>()
                resultOr(emptyList()) {
                    app.get(url, timeout = 30, headers = apiHeaders()).parsedSafe<SubsResponse>()?.subtitles.orEmpty()
                }.mapNotNull { toRemoteSubtitle(it) }
            }
        }.flatMap { resultOr(emptyList()) { it.await() } }
            .distinctBy { it.url }
            .take(SUBTITLE_FETCH_LIMIT)
    }

    private fun toRemoteSubtitle(sub: StremioSubtitle): RemoteSubtitle? {
        val url = sub.url?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return null
        return RemoteSubtitle(url, sanitizeSubtitleLang(subtitleLangOf(sub)))
    }

    private fun apiHeaders(): Map<String, String> {
        val ua = userAgent()
        return buildMap {
            if (ua.isNotEmpty()) put("User-Agent", ua)
            put("Accept", "application/json, text/plain, */*")
        }
    }

    private suspend inline fun <reified T : Any> fetchJson(
        url: String,
        timeout: Long,
        headers: Map<String, String> = emptyMap(),
    ): T? {
        repeat(2) { attempt ->
            resultOr(null) { app.get(url, timeout = timeout, headers = headers).parsedSafe<T>() }
                ?.let { return it }
            if (attempt < 1) delay(500L)
        }
        Log.w(TAG, "fetch failed host=" + url.substringAfter("://").substringBefore("/"))
        return null
    }

    private fun encodePathSegment(value: String): String? = runCatching {
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("%3A", ":")
    }.getOrNull()

    private fun addonUrl(addon: ConfiguredAddon, path: String): String? {
        val full = "${addon.base}$path${addon.querySuffix}"
        if (full.contains(Regex("\\s"))) return null
        if (!addon.querySuffix.isValidQuerySuffix()) return null
        return full
    }

    private fun addonApiUrl(manifestUrl: String): String = manifestUrl.trim()

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
                overview = stripHtml(video.overview ?: video.description).take(800),
                released = parseReleaseDate(video.released ?: video.firstAired)
                    ?: dateFromText(video.overview ?: video.description),
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
            videos = videos,
            tmdbId = id.substringAfter("tmdb:", "").takeIf { id.startsWith("tmdb:") && it.isNotEmpty() },
            kitsuId = id.substringAfter("kitsu:", "").takeIf { id.startsWith("kitsu:") && it.isNotEmpty() },
        )
    }

}
