package com.finddrama

import android.util.Log

object FindDramaRegistry {
    private const val TAG = "FindDrama"

    private val bundlePath = Regex("""/assets/[A-Za-z0-9_\-.]+\.js""")
    private val publicSet = Regex("""[A-Za-z_$][\w$]*=\[([0-9,\s]{4,})\]""")

    private val entries = listOf(
        Regex("""\{source:(\d+)\s*,\s*label:`([^`]+)`"""),
        Regex("""\{source:(\d+)\s*,\s*label:"([^"]+)""""),
        Regex("""\{source:(\d+)\s*,\s*label:'([^']+)'"""),
    )

    @Volatile
    private var cachedProviders: List<Provider> = emptyList()

    @Volatile
    private var cachedPublic: List<Int> = emptyList()

    fun cached(): List<Provider> = cachedProviders

    fun publicIds(): List<Int> = cachedPublic

    fun isKnown(sourceId: Int): Boolean = cachedProviders.any { it.id == sourceId }

    fun reset() {
        cachedProviders = emptyList()
        cachedPublic = emptyList()
    }

    suspend fun providers(): List<Provider> {
        cachedProviders.takeIf { it.isNotEmpty() }?.let { return it }
        return FindDramaApi.once("registry") { load() }.orEmpty()
    }

    private suspend fun load(): List<Provider> {
        FindDramaStore.loadProviders(Gl.PROVIDER_TTL_MINUTES)?.let { stored ->
            cachedProviders = stored.first
            cachedPublic = stored.second
            Log.i(TAG, "providers from disk: ${stored.first.size}")
            return stored.first
        }
        val discovered = runCatching { discover() }.getOrNull()
        if (discovered == null || discovered.providers.isEmpty()) {
            Log.w(TAG, "provider discovery failed, keeping ${cachedProviders.size} known providers")
            return cachedProviders
        }
        cachedProviders = discovered.providers
        cachedPublic = discovered.public
        FindDramaStore.saveProviders(discovered.providers, discovered.public)
        Log.i(
            TAG,
            "providers discovered: ${discovered.providers.size}, public rails: ${discovered.public.size}",
        )
        return discovered.providers
    }

    private class Discovery(
        val providers: List<Provider>,
        val public: List<Int>,
    )

    private suspend fun discover(): Discovery? {
        val html = FindDramaApi.fetchHtml("/") ?: return null
        val candidates = bundlePath.findAll(html).map { it.value }.distinct().toList()
        if (candidates.isEmpty()) return null
        var best: Discovery? = null
        for (path in candidates) {
            val bundle = FindDramaApi.fetchText(FindDramaApi.asset(path)) ?: continue
            val providers = parseProviders(bundle)
            if (providers.isEmpty()) continue
            if (providers.size > (best?.providers?.size ?: 0)) {
                best = Discovery(providers, parsePublic(bundle))
            }
            if (providers.size > 50) return best
        }
        return best
    }

    private fun parseProviders(bundle: String): List<Provider> {
        for (pattern in entries) {
            val found = pattern.findAll(bundle)
                .map { match ->
                    Provider(id = match.groupValues[1].toInt(), label = match.groupValues[2].trim())
                }
                .filter { it.id > 0 && it.label.isNotEmpty() }
                .distinctBy { it.id }
                .sortedBy { it.id }
                .toList()
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    private fun parsePublic(bundle: String): List<Int> = publicSet.findAll(bundle)
        .map { match ->
            match.groupValues[1]
                .split(",")
                .mapNotNull { it.trim().toIntOrNull() }
        }
        .maxByOrNull { it.size }
        .orEmpty()
}
