package com.nunodrama

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

const val DEFAULT_BASE = "https://nunodrama.my.id"

const val PLATFORM_COOKIE = "nuno_platform"
const val LANG_COOKIE = "nuno_lang"

const val LANG_ID = "id"
const val LANG_EN = "en"

const val SEARCH_PAGE_SIZE = 60
const val SEARCH_PER_PROVIDER = 8
const val HTTP_PARALLELISM = 32

fun JsonElement?.intOrZero(): Int {
    val primitive = this as? JsonPrimitive ?: return 0
    if (primitive.isString) return primitive.content.trim().toIntOrNull() ?: 0
    return primitive.content.toDoubleOrNull()?.toInt() ?: 0
}

@Serializable
data class DramaDto(
    @SerialName("PlatformName") val platformName: String = "",
    @SerialName("BookID") val bookId: String = "",
    @SerialName("BookName") val bookName: String = "",
    @SerialName("Cover") val cover: String? = null,
    @SerialName("ChapterCount") val chapterCount: JsonElement? = null,
)

@Serializable
data class SectionDto(
    @SerialName("dramas") val dramas: List<DramaDto> = emptyList(),
    @SerialName("next") val next: String? = null,
)

@Serializable
data class TvSeriesLd(
    @SerialName("name") val name: String = "",
    @SerialName("description") val description: String = "",
    @SerialName("image") val image: String? = null,
    @SerialName("inLanguage") val language: String = "",
    @SerialName("numberOfEpisodes") val episodeCount: JsonElement? = null,
)

@Serializable
data class HlsVariant(
    val url: String,
    val resolution: String,
    val bandwidth: Int,
    val height: Int,
)
