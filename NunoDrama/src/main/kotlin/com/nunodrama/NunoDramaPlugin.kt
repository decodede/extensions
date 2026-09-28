package com.nunodrama

import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "NunoDrama"

@CloudstreamPlugin
class NunoDramaPlugin : Plugin() {

    private val provider = NunoDramaProvider()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun load(context: Context) {
        NunoDramaStore.init(context)
        applyPreferences()
        registerMainAPI(provider)
        openSettings = { ctx -> NunoDramaSettingsDialog.show(ctx) }
        NunoDramaSettingsDialog.onChanged = {
            applyPreferences()
            provider.refresh()
            scope.launch { provider.warmUp() }
            runCatching { MainActivity.reloadHomeEvent.invoke(true) }
        }
        scope.launch {
            val before = provider.mainPage.size
            provider.warmUp()
            val after = provider.mainPage.size
            Log.i(TAG, "rails: $before cached -> $after after warm up")
            if (after > 0 && after != before) {
                runCatching { MainActivity.reloadHomeEvent.invoke(true) }
            }
            NunoDramaRegistry.discoverCategoriesInBackground()
        }
    }

    private fun applyPreferences() {
        provider.mainUrl = NunoDramaStore.base()
        provider.lang = NunoDramaStore.language()
        Log.i(TAG, "NunoDrama ready base=${provider.mainUrl} lang=${provider.lang}")
    }

    override fun beforeUnload() {
        scope.cancel()
    }
}
