package com.stremiouniversal.ui

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.stremiouniversal.StremioConstants

object AddonBrowserDialog {

    private const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.82 Mobile Safari/537.36"

    fun show(ctx: Context) {
        val density = ctx.resources.displayMetrics.density
        val web = WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = MOBILE_UA
            settings.loadsImagesAutomatically = true
            settings.mediaPlaybackRequiresUserGesture = true
            webViewClient = WebViewClient()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (480 * density).toInt()
            )
        }
        CookieManager.getInstance().setAcceptCookie(true)
        web.loadUrl(StremioConstants.BROWSE_ADDONS_URL)
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            addView(web)
            addView(TextView(ctx).apply {
                text = "Copy an addon manifest link, then paste it under Add new."
                textSize = 12f
                setPadding((20 * density).toInt(), (10 * density).toInt(), (20 * density).toInt(), (10 * density).toInt())
            })
        }
        AlertDialog.Builder(ctx)
            .setTitle("Browse Addons")
            .setView(container)
            .setNegativeButton("Close", null)
            .setOnDismissListener {
                runCatching {
                    web.stopLoading()
                    web.destroy()
                }
            }
            .show()
    }
}
