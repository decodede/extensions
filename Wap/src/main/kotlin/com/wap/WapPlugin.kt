package com.wap

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class WapPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(WapProvider())
    }
}
