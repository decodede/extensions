package com.rulz

import android.content.Context
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class RulzPlugin : Plugin() {
    private val provider = RulzProvider()

    override fun load(context: Context) {
        RulzStore.init(context)
        provider.mainUrl = RulzStore.base()
        registerMainAPI(provider)

        openSettings = { ctx ->
            RulzSettingsDialog.show(ctx, provider.mainUrl)
        }

        RulzSettingsDialog.onBaseChanged = {
            provider.mainUrl = RulzStore.base()
            runCatching { MainActivity.reloadHomeEvent.invoke(true) }
        }
    }

    override fun beforeUnload() {}
}
