package com.stremio

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode

object StremioConstants {
    const val TAG = "Stremio"

    const val PREFS_NAME = "Stremio"

    const val KEY_ADDONS = "stremio_addons"

    const val KEY_DEFAULT_ADDONS = "stremio_default_addons_enabled"

    const val KEY_PROFILES = "stremio_profiles"
    const val KEY_DEFAULT_PROFILE_NAME = "stremio_default_profile_name"
    const val PROVIDER_NAME = "Stremio"
    const val KEY_SCHEMA_V = "stremio_schema_v"
    const val SCHEMA_V = 3

    const val KEY_UA_PRESET = "stremio_ua_preset"

    const val KEY_APPEND_TRACKERS = "stremio_append_trackers"

    const val KEY_OPENSUBS = "stremio_opensubtitles_fallback"


    const val UA_DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Safari/537.36"

    const val UA_MOBILE =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Mobile Safari/537.36"

    const val UA_DEFAULT = ""

    val UA_PRESETS = listOf(
        "Desktop Chrome" to UA_DESKTOP,
        "Mobile Chrome" to UA_MOBILE,
        "Host default" to UA_DEFAULT,
    )

    const val BROWSE_ADDONS_URL = "https://stremio-addons.net"
    const val CINEMETA_BASE = "https://v3-cinemeta.strem.io"
    const val ELFHOSTED_BASE =
        "https://aiometadata.elfhosted.com/stremio/b7cb164b-074b-41d5-b458-b3a834e197bb"
    const val TRACKER_LIST_URL =
        "https://raw.githubusercontent.com/ngosang/trackerslist/master/trackers_best.txt"
    const val OPENSUBS_API = "https://opensubtitles-v3.strem.io"

    const val TMDB_DEMO_KEY = "98ae14df2b8d8f8f8136499daf79f0e0"

    const val LEGACY_ADDON_PREFIX = "stremio_addon"
    const val LEGACY_LINKS_X = "stremio_saved_links"
    const val LEGACY_LINKS_STREAMPLAY = "streamplay_stremio_addon_saved_links"
}

data class AddonPreview(
    val name: String,
    val catalogCount: Int
)

data class AddonConfig(
    val name: String,
    val manifestUrl: String,
    val enabled: Boolean = true,
)

data class LinkRef(
    val base: String,
    val type: String,
    val id: String,
)

data class MetaRef(
    val base: String,
    val type: String,
    val id: String,
    val name: String,
    val poster: String?,
)

data class CatalogRow(
    val title: String,
    val items: List<MetaRef>,
)

data class VideoRef(
    val id: String,
    val title: String,
    val season: Int,
    val episode: Int,
    val thumbnail: String?,
    val overview: String,
    val released: Long? = null,
)

data class MetaDetails(
    val id: String,
    val type: String,
    val name: String,
    val poster: String?,
    val background: String?,
    val description: String,
    val year: Int?,
    val rating: Double?,
    val genres: List<String>,
    val cast: List<String>,
    val trailerYoutubeIds: List<String>,
    val videos: List<VideoRef>,
    val tmdbId: String? = null,
    val kitsuId: String? = null,
)

data class StreamLink(
    val url: String,
    val source: String,
    val title: String,
    val qualityTag: String?,
    val headers: Map<String, String>,
    val resolutionRank: Int,
    val seeders: Int,
    val addonOrder: Int,
    val fileIdx: Int? = null,
    val kind: StreamKind = StreamKind.PROGRESSIVE,
    val referer: String? = null,
    val bingeGroup: String? = null,
    val videoSize: Long? = null,
)

data class RemoteSubtitle(
    val url: String,
    val lang: String,
)

data class ConfiguredAddon(
    val order: Int,
    val displayName: String,
    val base: String,
    val querySuffix: String,
    val idPrefixes: List<String>,
    val catalogs: List<StremioCatalog>,
    val hasCatalog: Boolean,
    val hasStream: Boolean,
    val hasMeta: Boolean,
    val hasSubtitles: Boolean,
    val version: String? = null,
    val needsConfiguration: Boolean = false,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioManifest(
    val id: String? = null,
    val version: String? = null,
    val name: String? = null,
    val types: List<String> = emptyList(),
    val idPrefixes: List<String> = emptyList(),
    val resources: List<JsonNode> = emptyList(),
    val catalogs: List<StremioCatalog> = emptyList(),
    val behaviorHints: ManifestBehaviorHints? = null,
    val config: List<StremioConfigField>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ManifestBehaviorHints(
    val configurable: Boolean? = null,
    val configurationRequired: Boolean? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioConfigField(
    val key: String? = null,
    val type: String? = null,
    val required: Boolean? = null,
    val title: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioCatalog(
    val name: String? = null,
    val id: String = "",
    val type: String? = null,
    val types: List<String> = emptyList(),
    val extra: List<StremioExtra>? = null,
    val extraSupported: List<String>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioExtra(
    val name: String? = null,
    val isRequired: Boolean? = false,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CatalogResponse(
    val metas: List<CatalogEntry>? = null,
    val meta: CatalogEntry? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CatalogEntry(
    val name: String = "",
    val id: String = "",
    val poster: String? = null,
    val background: String? = null,
    val description: String? = null,
    val imdbRating: JsonNode? = null,
    val type: String? = null,
    val videos: List<StremioVideo>? = null,
    val genre: JsonNode? = null,
    val genres: JsonNode? = null,
    val cast: JsonNode? = null,
    @JsonProperty("links") val links: List<StremioLink> = emptyList(),
    @JsonProperty("trailers") val trailers: List<StremioTrailer> = emptyList(),
    @JsonProperty("trailerStreams") val trailerStreams: List<TrailerStream> = emptyList(),
    @JsonProperty("year") val year: JsonNode? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioLink(
    val category: String? = null,
    val id: String? = null,
    val url: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioVideo(
    val id: String? = null,
    val title: String? = null,
    val name: String? = null,
    val season: Int? = null,
    val number: Int? = null,
    val episode: Int? = null,
    val thumbnail: String? = null,
    val overview: String? = null,
    val description: String? = null,
    @JsonProperty("released") val released: String? = null,
    @JsonProperty("first_aired") val firstAired: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioTrailer(
    val source: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TrailerStream(
    val ytId: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StreamsResponse(
    val streams: List<StremioStream> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioStream(
    val name: String? = null,
    val title: String? = null,
    val url: String? = null,
    val description: String? = null,
    val ytId: String? = null,
    val externalUrl: String? = null,
    val behaviorHints: BehaviorHints? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val headers: Map<String, String>? = null,
    val sources: List<String> = emptyList(),
    val subtitles: List<StremioSubtitle> = emptyList(),
    val nzbUrl: String? = null,
    val servers: List<String> = emptyList(),
    val rarUrls: List<ArchiveSource> = emptyList(),
    val zipUrls: List<ArchiveSource> = emptyList(),
    @JsonProperty("7zipUrls") val sevenZipUrls: List<ArchiveSource> = emptyList(),
    val tarUrls: List<ArchiveSource> = emptyList(),
    val tgzUrls: List<ArchiveSource> = emptyList(),
) {
    fun anyArchiveUrl(): String? =
        nzbUrl?.takeIf { it.isNotBlank() }
            ?: (rarUrls + zipUrls + sevenZipUrls + tarUrls + tgzUrls).firstOrNull()?.url
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class ArchiveSource(
    val url: String? = null,
    val bytes: Long? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BehaviorHints(
    val proxyHeaders: ProxyHeaders? = null,
    val headers: Map<String, String>? = null,
    val filename: String? = null,
    val bingeGroup: String? = null,
    val notWebReady: Boolean? = null,
    val videoSize: Long? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ProxyHeaders(
    val request: Map<String, String>? = null,
    val response: Map<String, String>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioSubtitle(
    val url: String? = null,
    val lang: String? = null,
    @JsonProperty("lang_code") val langCode: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SubsResponse(
    val subtitles: List<StremioSubtitle> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbExternalIds(
    @JsonProperty("imdb_id") val imdbId: String? = null,
    @JsonProperty("id") val id: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbFindResponse(
    @JsonProperty("imdb_results") val imdbResults: List<TmdbFindResult> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbFindResult(
    @JsonProperty("imdb_id") val imdbId: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniZipResponse(
    val mappings: AniZipMappings? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniZipMappings(
    @JsonProperty("imdb_id") val imdbId: String? = null,
)
