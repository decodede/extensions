package com.kisskh

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class KissKhProviderPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(KissKhProvider())
    }
}
