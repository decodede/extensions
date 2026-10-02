package com.kimoitv

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class kimoitvplugin : Plugin() {
    override fun load(context: Context) {
        cloudflare.load(context.applicationContext)
        registerMainAPI(kimoitv())
        openSettings = { ctx ->
            val activity = ctx as? AppCompatActivity
            if (activity != null) cloudflare.show(activity)
        }
    }
}