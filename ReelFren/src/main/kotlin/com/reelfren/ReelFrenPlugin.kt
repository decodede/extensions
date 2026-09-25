package com.reelfren

import android.content.Context
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

object ReelFrenScope {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun launch(block: suspend CoroutineScope.() -> Unit) {
        scope.launch(block = block)
    }

    fun shutdown() {
        scope.cancel()
    }
}

@CloudstreamPlugin
class ReelFrenPlugin : Plugin() {
    private val providers = LinkedHashMap<String, ReelFrenProvider>()

    override fun load(context: Context) {
        ReelFrenStore.init(context)
        ReelFrenCf.setContext(context)
        register(ReelFrenStore.knownSlugs().sorted())

        openSettings = { ctx -> ReelFrenSettingsDialog.show(ctx, ReelFrenStore.apiBase()) }

        ReelFrenSettingsDialog.onChanged = {
            ReelFrenScope.launch { refresh(true) }
        }

        ReelFrenScope.launch { refresh(true) }
    }

    override fun beforeUnload() {
        ReelFrenScope.shutdown()
        providers.clear()
    }

    private suspend fun refresh(reload: Boolean) {
        val slugs = withTimeoutOrNull(8_000L) { ReelFrenDiscovery.refreshProviders() }.orEmpty()
        val added = register(slugs)
        if (added && reload) runCatching { MainActivity.reloadHomeEvent.invoke(true) }
    }

    private fun register(slugs: List<String>): Boolean {
        var added = false
        for (slug in slugs) {
            if (slug.isEmpty() || slug in providers) continue
            val provider = ReelFrenProvider(slug)
            providers[slug] = provider
            registerMainAPI(provider)
            added = true
        }
        return added
    }
}
