package com.finddrama

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
object FlexibleLong : KSerializer<Long?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleLong", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): Long? {
        if (decoder is JsonDecoder) {
            return when (val element = decoder.decodeJsonElement()) {
                is JsonNull -> null
                is JsonPrimitive -> element.content.trim().toLongOrNull()
                else -> null
            }
        }
        return if (decoder.decodeNotNullMark()) decoder.decodeLong() else null
    }

    override fun serialize(encoder: Encoder, value: Long?) {
        if (encoder is JsonEncoder) {
            encoder.encodeJsonElement(if (value == null) JsonNull else JsonPrimitive(value))
            return
        }
        if (value != null) encoder.encodeLong(value)
    }
}

@Serializable
data class Provider(
    val id: Int,
    val label: String,
)

@Serializable
data class DramaDto(
    val slug: String? = null,
    @Serializable(FlexibleLong::class) val dramaId: Long? = null,
    val title: String? = null,
    val cover: String? = null,
    val latestEpisodeLabel: String? = null,
    val source: Int? = null,
    val synopsis: String? = null,
    val tags: List<String> = emptyList(),
)

@Serializable
data class SeriesPage(
    val items: List<DramaDto> = emptyList(),
    val page: Int = 0,
    val limit: Int = 0,
    val total: Int = 0,
)

@Serializable
data class EpisodeDto(
    val ep: Int = 0,
    val url: String = "",
)

@Serializable
data class EpisodePage(
    val items: List<EpisodeDto> = emptyList(),
)

@Serializable
data class CachedRail(
    val at: Long = 0L,
    val total: Int = 0,
    val items: List<DramaDto> = emptyList(),
)

@Serializable
data class CachedEpisodes(
    val at: Long = 0L,
    val items: List<EpisodeDto> = emptyList(),
)
