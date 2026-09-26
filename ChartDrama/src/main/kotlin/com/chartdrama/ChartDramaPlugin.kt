package com.chartdrama

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ChartDramaPlugin : Plugin() {
    private val providers = LinkedHashMap<Int, ChartDramaProvider>()

    override fun load(context: Context) {
        ChartDramaStore.init(context)
        register(ChartDramaStore.sources().sorted())

        openSettings = { ctx -> ChartDramaSettingsDialog.show(ctx) }

        ChartDramaScope.launch {
            val sources = ChartDramaDiscovery.known()
            if (register(sources)) ChartDramaScope.refreshHome()
            ChartDramaScope.launch { loadTags() }
        }
    }

    override fun beforeUnload() {
        ChartDramaScope.shutdown()
        providers.clear()
    }

    private fun register(sources: Collection<Int>): Boolean {
        var added = false
        for (id in sources) {
            if (id <= 0 || id in providers) continue
            val provider = ChartDramaProvider(id)
            providers[id] = provider
            registerMainAPI(provider)
            added = true
        }
        return added
    }

    private suspend fun loadTags() {
        if (ChartDramaStore.tagsFresh() && ChartDramaStore.tags().isNotEmpty()) return
        val tags = ChartDramaClient.tags(60)
        if (tags.isNotEmpty()) ChartDramaStore.saveTags(tags.take(ChartDramaProvider.TAG_ROWS))
    }
}
