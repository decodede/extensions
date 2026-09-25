package com.screen

import android.content.Context
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ScreenPlugin : Plugin() {
    private val provider = ScreenProvider()

    override fun load(context: Context) {
        ScreenStore.init(context)
        provider.mainUrl = ScreenStore.base()
        registerMainAPI(provider)

        openSettings = { ctx ->
            ScreenSettingsDialog.show(ctx, provider.mainUrl)
        }

        ScreenSettingsDialog.onBaseChanged = {
            provider.mainUrl = ScreenStore.base()
            runCatching { MainActivity.reloadHomeEvent.invoke(true) }
        }
    }

    override fun beforeUnload() {}
}
