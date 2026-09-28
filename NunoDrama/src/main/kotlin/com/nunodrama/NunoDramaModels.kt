package com.nunodrama

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

const val DEFAULT_BASE = "https://nunodrama.my.id"

const val PLATFORM_COOKIE = "nuno_platform"
const val LANG_COOKIE = "nuno_lang"

const val LANG_ID = "id"
const val LANG_EN = "en"

const val CATALOGUE_PAGE_SIZE = 30
const val SEARCH_PAGE_SIZE = 60
const val SEARCH_PER_PROVIDER = 8
const val HTTP_PARALLELISM = 3

object LenientIntSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LenientInt", PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)

    override fun deserialize(decoder: Decoder): Int {
        val decoder = decoder as? JsonDecoder ?: return decoder.decodeInt()
        val element = decoder.decodeJsonElement() as? JsonPrimitive ?: return 0
        if (element.isString) return element.content.trim().toIntOrNull() ?: 0
        return element.content.toDoubleOrNull()?.toInt() ?: 0
    }
}

@Serializable
data class DramaDto(
    @SerialName("PlatformName") val platformName: String = "",
    @SerialName("BookID") val bookId: String = "",
    @SerialName("BookName") val bookName: String = "",
    @SerialName("Cover") val cover: String? = null,
    @SerialName("ChapterCount") @Serializable(with = LenientIntSerializer::class) val chapterCount: Int = 0,
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
    @SerialName("numberOfEpisodes") @Serializable(with = LenientIntSerializer::class) val episodeCount: Int = 0,
)

@Serializable
data class HlsVariant(
    val url: String,
    val resolution: String,
    val bandwidth: Int,
    val height: Int,
)
