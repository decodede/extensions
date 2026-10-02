package com.kimoitv

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class kimoitvplugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(kimoitv())
    }
}
