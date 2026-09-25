package com.stremiouniversal

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode

data class AddonConfig(
    val name: String,
    val manifestUrl: String,
    val enabled: Boolean = true
)

data class LinkRef(
    val base: String,
    val type: String,
    val id: String
)

data class MetaRef(
    val base: String,
    val type: String,
    val id: String,
    val name: String,
    val poster: String?
)

data class CatalogRow(
    val title: String,
    val items: List<MetaRef>
)

data class VideoRef(
    val id: String,
    val title: String,
    val season: Int,
    val episode: Int,
    val thumbnail: String?,
    val overview: String
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
    val videos: List<VideoRef>
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
    val fileIdx: Int? = null
)

data class StreamsResult(
    val links: List<StreamLink>,
    val inlineSubtitles: List<RemoteSubtitle>,
    val youtubeIds: List<String> = emptyList(),
    val externalUrls: List<String> = emptyList()
)

data class RemoteSubtitle(
    val url: String,
    val lang: String
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
    val hasSubtitles: Boolean
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioManifest(
    val name: String? = null,
    val types: List<String> = emptyList(),
    val idPrefixes: List<String> = emptyList(),
    val resources: List<JsonNode> = emptyList(),
    val catalogs: List<StremioCatalog> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioCatalog(
    val name: String? = null,
    val id: String = "",
    val type: String? = null,
    val types: MutableList<String> = mutableListOf(),
    val extra: List<StremioExtra>? = null,
    val extraSupported: List<String>? = null
) {
    init {
        if (type != null && type !in types) types.add(type)
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioExtra(
    val name: String? = null,
    val isRequired: Boolean? = false
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CatalogResponse(
    val metas: List<CatalogEntry>? = null,
    val meta: CatalogEntry? = null
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
    @JsonProperty("year") val year: JsonNode? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioLink(
    val category: String? = null,
    val id: String? = null,
    val url: String? = null
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
    val description: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioTrailer(
    val source: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TrailerStream(
    val ytId: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StreamsResponse(
    val streams: List<StremioStream> = emptyList()
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
    val subtitles: List<StremioSubtitle> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BehaviorHints(
    val proxyHeaders: ProxyHeaders? = null,
    val headers: Map<String, String>? = null,
    val filename: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ProxyHeaders(
    val request: Map<String, String>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioSubtitle(
    val url: String? = null,
    val lang: String? = null,
    @JsonProperty("lang_code") val langCode: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SubsResponse(
    val subtitles: List<StremioSubtitle> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbExternalIds(
    @JsonProperty("imdb_id") val imdb_id: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniZipResponse(
    val mappings: AniZipMappings? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniZipMappings(
    @JsonProperty("imdb_id") val imdb_id: String? = null
)
