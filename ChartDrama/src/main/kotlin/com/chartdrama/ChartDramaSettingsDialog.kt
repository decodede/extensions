package com.chartdrama

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

object ChartDramaSettingsDialog {
    var onRescan: (() -> Unit)? = null

    @SuppressLint("SetTextI18n")
    fun show(context: Context) {
        val density = context.resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        root.addView(label(context, "Each ChartDrama source is a separate provider.", Color.GRAY))
        root.addView(
            label(
                context,
                "Sources found: " + ChartDramaStore.sources().size +
                    ". Rows are built from the site's own tags.",
                Color.GRAY
            )
        )
        val rescan = Button(context).apply { text = "Rescan sources and tags" }
        rescan.setOnClickListener {
            ChartDramaStore.clear()
            onRescan?.invoke()
            Toast.makeText(context, "Rescanning", Toast.LENGTH_SHORT).show()
        }
        root.addView(rescan)
        root.addView(label(context, "API: " + CHARTDRAMA_API, Color.GRAY))

        val scroll = ScrollView(context).apply { addView(root) }
        AlertDialog.Builder(context)
            .setTitle("ChartDrama Settings")
            .setView(scroll)
            .setPositiveButton("Close", null)
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
