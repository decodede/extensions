package com.reelfren

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

object ReelFrenSettingsDialog {
    var onRescan: (() -> Unit)? = null

    @SuppressLint("SetTextI18n")
    fun show(context: Context, currentApi: String) {
        val density = context.resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        root.addView(label(context, "API link", Color.GRAY))
        val input = EditText(context).apply {
            hint = currentApi
            isSingleLine = true
            setSelectAllOnFocus(true)
        }
        root.addView(input)
        root.addView(
            label(
                context,
                "Each ReelFren site is its own provider. Hide or show them from the " +
                    "provider list in CloudStream settings.",
                Color.GRAY
            )
        )
        root.addView(
            label(
                context,
                "Cloudflare: " + if (ReelFrenStore.hasCookie()) {
                    "verified, saved on this device"
                } else {
                    "not verified yet. It solves itself when a site blocks a request."
                },
                Color.GRAY
            )
        )

        val verify = Button(context).apply { text = "Verify Cloudflare now" }
        verify.setOnClickListener {
            Toast.makeText(context, "Checking access…", Toast.LENGTH_SHORT).show()
            ReelFrenScope.launch {
                val items = ReelFrenClient.home("dramanova", "")
                val message = when {
                    items.isNotEmpty() -> "Access OK (${items.size} items)"
                    ReelFrenStore.hasCookie() -> "Still blocked. Tap below to solve manually."
                    else -> "Still blocked. Solve it manually."
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
        root.addView(verify)

        val manual = Button(context).apply { text = "Solve Cloudflare manually" }
        manual.setOnClickListener {
            Toast.makeText(context, "Solving…", Toast.LENGTH_SHORT).show()
            ReelFrenScope.launch {
                val ok = ReelFrenCf.solve(REEL_DEFAULT_WEB + "/?lang=en")
                Toast.makeText(
                    context,
                    if (ok) "Cloudflare verified" else "Could not verify",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        root.addView(manual)

        val clear = Button(context).apply { text = "Clear cache, cookies and rescan" }
        clear.setOnClickListener {
            ReelFrenStore.clearCookies()
            ReelFrenStore.clearCategories()
            onRescan?.invoke()
            Toast.makeText(context, "Cleared", Toast.LENGTH_SHORT).show()
        }
        root.addView(clear)

        val report = ReelFrenStore.diagnosticLines()
        if (report.isNotEmpty()) {
            root.addView(label(context, "Category probe results", Color.GRAY))
            root.addView(
                TextView(context).apply {
                    text = report.joinToString("\n")
                    textSize = 10f
                    setTextColor(Color.DKGRAY)
                    setPadding(0, (4 * density).toInt(), 0, 0)
                }
            )
        }

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
                onRescan?.invoke()
                Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @SuppressLint("SetTextI18n")
    private fun label(context: Context, text: String, color: Int): TextView = TextView(context).apply {
        this.text = text
        textSize = 11f
        setTextColor(color)
        setPadding(0, (8 * context.resources.displayMetrics.density).toInt(), 0, 0)
    }
}
