package com.stremiouniversal
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.stremiouniversal.ui.AddonSettingsFragment
@CloudstreamPlugin
class StremioPlugin : Plugin() {
    override fun load(context: Context) {
        val prefs = context.getSharedPreferences(StremioConstants.PREFS_NAME, Context.MODE_PRIVATE)
        registerMainAPI(StremioProvider(StremioRepository(prefs)))
        openSettings = {
            val activity = activityOf(context)?.takeUnless { it.isFinishing || it.isDestroyed }
            if (activity != null) {
                AddonSettingsFragment().show(activity.supportFragmentManager, "StremioAddons")
            } else {
                Toast.makeText(context, "Open settings from an activity to edit Stremio addons", Toast.LENGTH_LONG).show()
            }
        }
    }
    private fun activityOf(c: Context): AppCompatActivity? {
        var x: Context? = c
        while (x != null) {
            if (x is AppCompatActivity) return x
            x = (x as? ContextWrapper)?.baseContext
        }
        return null
    }
}
