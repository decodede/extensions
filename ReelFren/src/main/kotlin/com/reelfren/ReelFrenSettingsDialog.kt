package com.reelfren

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
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
            setText("")
            isSingleLine = true
            setSelectAllOnFocus(true)
        }
        root.addView(input)
        val verify = Button(context).apply { text = "Verify access (solve Cloudflare)" }
        verify.setOnClickListener { showVerify(context) }
        root.addView(verify)
        root.addView(TextView(context).apply {
            text = "Providers (unticked are hidden from home and search)"
            textSize = 11f
            setTextColor(Color.GRAY)
            setPadding(0, pad / 2, 0, 0)
        })
        val toggles = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(toggles)
        val checks = ArrayList<CheckBox>()
        fun fill(slugs: List<String>) {
            toggles.removeAllViews()
            checks.clear()
            for (slug in slugs) {
                val box = CheckBox(context).apply {
                    text = ReelFrenCatalog.displayName(slug)
                    isChecked = ReelFrenStore.isEnabled(slug)
                    textSize = 14f
                }
                checks.add(box)
                toggles.addView(box)
            }
        }
        fill(allSlugs())
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, pad / 2, 0, 0)
        }
        val all = Button(context).apply { text = "All" }
        val none = Button(context).apply { text = "None" }
        all.setOnClickListener { checks.forEach { it.isChecked = true } }
        none.setOnClickListener { checks.forEach { it.isChecked = false } }
        row.addView(all)
        row.addView(none)
        root.addView(row)

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
                val slugs = allSlugs()
                val picked = slugs.filterIndexed { i, _ -> checks.getOrNull(i)?.isChecked == true }.toSet()
                if (picked.size == slugs.size) ReelFrenStore.clearEnabled()
                else ReelFrenStore.saveEnabled(picked)
                onChanged?.invoke()
                Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun allSlugs(): List<String> {
        return (ReelFrenCatalog.providers.map { it.slug } + ReelFrenStore.knownSlugs()).distinct().sorted()
    }

    @Suppress("SetJavaScriptEnabled", "SetTextI18n")
    fun showVerify(context: Context) {
        val density = context.resources.displayMetrics.density
        val web = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.mediaPlaybackRequiresUserGesture = true
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
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
