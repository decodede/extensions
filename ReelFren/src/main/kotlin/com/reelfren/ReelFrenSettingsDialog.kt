package com.reelfren

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

object ReelFrenSettingsDialog {
    var onChanged: (() -> Unit)? = null

    @Suppress("SetTextI18n")
    fun show(context: Context, currentApi: String) {
        val density = context.resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        root.addView(TextView(context).apply {
            text = "API link"
            textSize = 11f
            setTextColor(Color.GRAY)
        })
        val input = EditText(context).apply {
            hint = currentApi
            isSingleLine = true
            setSelectAllOnFocus(true)
        }
        root.addView(input)
        root.addView(hint(context, "Each site on ReelFren is a separate provider. Hide or show " +
            "them from the provider list in CloudStream settings."))
        val verify = Button(context).apply { text = "Verify access (solve Cloudflare)" }
        verify.setOnClickListener { showVerify(context) }
        root.addView(verify)
        val rescan = Button(context).apply { text = "Rescan providers and categories" }
        rescan.setOnClickListener {
            ReelFrenStore.clearCategories()
            ReelFrenStore.clearKnown()
            onChanged?.invoke()
            Toast.makeText(context, "Rescanning", Toast.LENGTH_SHORT).show()
        }
        root.addView(rescan)

        val scroll = ScrollView(context).apply { addView(root) }
        AlertDialog.Builder(context)
            .setTitle("ReelFren Settings")
            .setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                val raw = input.text?.toString()?.trim().orEmpty()
                if (raw.isNotEmpty() && !ReelFrenStore.saveApiBase(raw)) {
                    Toast.makeText(context, "Invalid URL", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                onChanged?.invoke()
                Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @Suppress("SetTextI18n")
    private fun hint(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 11f
        setTextColor(Color.GRAY)
        setPadding(0, pad2(context) / 2, 0, 0)
    }

    private fun pad2(context: Context): Int = (16 * context.resources.displayMetrics.density).toInt()

    @Suppress("SetJavaScriptEnabled", "SetTextI18n")
    fun showVerify(context: Context) {
        val density = context.resources.displayMetrics.density
        val web = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.mediaPlaybackRequiresUserGesture = true
            settings.userAgentString = ReelFrenClient.UA
            webViewClient = WebViewClient()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (460 * density).toInt()
            )
        }
        CookieManager.getInstance().setAcceptCookie(true)
        web.loadUrl(REEL_DEFAULT_WEB + "/?lang=en")
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(web)
            addView(TextView(context).apply {
                text = "Wait until the site loads, then press Done."
                textSize = 12f
                setPadding((20 * density).toInt(), (10 * density).toInt(), (20 * density).toInt(), 0)
            })
        }
        AlertDialog.Builder(context)
            .setTitle("Cloudflare check")
            .setView(box)
            .setPositiveButton("Done", null)
            .setOnDismissListener { runCatching { web.stopLoading(); web.destroy() } }
            .show()
    }
}
