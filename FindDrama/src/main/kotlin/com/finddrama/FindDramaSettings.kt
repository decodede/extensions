package com.finddrama

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

object FindDramaSettings {
    var onChanged: (() -> Unit)? = null

    fun show(context: Context) {
        val pad = (16 * context.resources.displayMetrics.density).toInt()

        val baseInput = EditText(context).apply {
            hint = "Site address"
            setText(Gl.site())
            isSingleLine = true
        }

        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(label(context, "Site address (leave blank to keep the current one)"))
            addView(baseInput)
            addView(label(context, "Current"))
            addView(TextView(context).apply {
                text = Gl.site()
                textSize = 13f
                setTextColor(Color.parseColor("#1A73E8"))
                paint.isUnderlineText = true
                setOnClickListener { open(context, Gl.site()) }
            })
            addView(label(context, "Status"))
            addView(TextView(context).apply {
                text = status()
                textSize = 13f
            })
        }

        val root = ScrollView(context).apply {
            addView(inner)
        }

        AlertDialog.Builder(context)
            .setTitle("${Gl.NAME} Settings")
            .setView(root)
            .setPositiveButton("Save") { _, _ ->
                val saved = FindDramaStore.saveBase(baseInput.text?.toString())
                if (saved) {
                    onChanged?.invoke()
                    Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Address unchanged", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Refresh catalogue") { _, _ ->
                onChanged?.invoke()
                Toast.makeText(context, "Catalogue cleared, rebuilding", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun status(): String {
        val rails = FindDramaRegistry.cached().size
        if (rails == 0) return "Providers not read yet - open the home screen once."
        val public = FindDramaRegistry.publicIds().size
        return "$rails providers discovered, $public browsable in full. 40+ rails are paged through two endpoints."
    }

    private fun label(context: Context, text: String) = TextView(context).apply {
        this.text = text
        textSize = 11f
        setTextColor(Color.GRAY)
        setPadding(0, 14, 0, 4)
    }

    private fun open(context: Context, url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
