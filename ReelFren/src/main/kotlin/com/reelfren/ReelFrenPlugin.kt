package com.reelfren

import android.content.Context
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ReelFrenPlugin : Plugin() {
    private val provider = ReelFrenProvider()

    override fun load(context: Context) {
        ReelFrenStore.init(context)
        registerMainAPI(provider)

        openSettings = { ctx ->
            ReelFrenSettingsDialog.show(ctx, ReelFrenStore.apiBase())
        }

        ReelFrenSettingsDialog.onChanged = {
            runCatching { MainActivity.reloadHomeEvent.invoke(true) }
        }
    }
}
