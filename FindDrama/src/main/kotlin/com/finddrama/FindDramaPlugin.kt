package com.finddrama

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

private const val TAG = "FindDrama"

@CloudstreamPlugin
class FindDramaPlugin : Plugin() {

    private val provider = FindDramaProvider()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun load(context: Context) {
        FindDramaStore.init(context)
        provider.syncBase()
        registerMainAPI(provider)
        openSettings = FindDramaSettings::show
        FindDramaSettings.onChanged = {
            FindDramaStore.clearCache()
            provider.reload()
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
        }
    }

    override fun beforeUnload() {
        scope.cancel()
    }
}
