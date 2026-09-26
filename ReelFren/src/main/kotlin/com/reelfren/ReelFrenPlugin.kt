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
    @Volatile private var lastRefresh = 0L

    fun launch(block: suspend CoroutineScope.() -> Unit) {
        scope.launch(block = block)
    }

    fun refreshHome() {
        val now = System.currentTimeMillis()
        if (now - lastRefresh < REFRESH_COOLDOWN_MS) return
        lastRefresh = now
        runCatching { MainActivity.reloadHomeEvent.invoke(true) }
    }

    fun shutdown() {
        scope.cancel()
    }

    private const val REFRESH_COOLDOWN_MS = 20_000L
}

@CloudstreamPlugin
class ReelFrenPlugin : Plugin() {
    private val providers = LinkedHashMap<String, ReelFrenProvider>()

    override fun load(context: Context) {
        ReelFrenStore.init(context)
        ReelFrenCf.setContext(context)
        register(ReelFrenStore.knownSlugs().sorted().ifEmpty { ReelFrenNames.seedSlugs })

        openSettings = { ctx -> ReelFrenSettingsDialog.show(ctx, ReelFrenStore.apiBase()) }

        ReelFrenSettingsDialog.onRescan = {
            ReelFrenScope.launch { refresh(true) }
        }
    }

    override fun beforeUnload() {
        ReelFrenScope.shutdown()
        providers.clear()
    }

    private suspend fun refresh(reload: Boolean) {
        val slugs = withTimeoutOrNull(15_000L) { ReelFrenDiscovery.refreshProviders() }.orEmpty()
        val added = register(slugs)
        if (added && reload) ReelFrenScope.refreshHome()
    }

    private fun register(slugs: List<String>): Boolean {
        var added = false
        for (slug in slugs) {
            if (slug.isEmpty() || slug in providers) continue
            val provider = ReelFrenProvider(slug)
            providers[slug] = provider
            runCatching { registerMainAPI(provider) }.onSuccess { added = true }
        }
        return added
    }
}
