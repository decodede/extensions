package com.wood
import android.content.Context
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
@CloudstreamPlugin
class WoodPlugin : Plugin() {
    private val provider = WoodProvider()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun load(context: Context) {
        registerMainAPI(provider)
        scope.launch {
            val before = provider.mainPage.size
            provider.warmUp()
            val after = provider.mainPage.size
            if (after > 0 && after != before) {
                runCatching { MainActivity.reloadHomeEvent.invoke(true) }
            }
        }
    }
    override fun beforeUnload() {
        scope.cancel()
    }
}