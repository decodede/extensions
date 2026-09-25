package com.stremiouniversal

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.coroutines.CancellationException

private val codec: ObjectMapper by lazy {
    ObjectMapper().registerKotlinModule()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
}

fun LinkRef.toJsonString(): String = codec.writeValueAsString(this)

fun parseLinkRef(json: String): LinkRef? = try {
    codec.readValue(json, LinkRef::class.java)
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

fun stringList(node: JsonNode?): List<String> {
    if (node == null || node.isNull) return emptyList()
    if (node.isArray) return node.mapNotNull { it.asText(null)?.trim()?.takeIf { it.isNotEmpty() } }
    if (node.isValueNode) {
        val text = node.asText().trim()
        if (text.isEmpty()) return emptyList()
        return text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }
    return emptyList()
}

fun extractMetaEntry(body: String, id: String): CatalogEntry? {
    val wrapped = try {
        codec.readValue(body, CatalogResponse::class.java)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
    wrapped?.let { it.meta ?: it.metas?.firstOrNull { meta -> meta.id == id } ?: it.metas?.singleOrNull() }
        ?.takeIf { it.name.isNotEmpty() }?.let { return it }
    return try {
        codec.readValue(body, CatalogEntry::class.java)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }?.takeIf { it.id.isNotEmpty() && it.name.isNotEmpty() }
}
