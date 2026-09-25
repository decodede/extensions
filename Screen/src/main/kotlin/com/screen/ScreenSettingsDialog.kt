package com.screen

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

object ScreenSettingsDialog {
    var onBaseChanged: (() -> Unit)? = null

    fun show(context: Context, currentBase: String) {
        val pad = (16 * context.resources.displayMetrics.density).toInt()

        val input = EditText(context).apply {
            hint = "Paste new site link here"
            setText("")
            isSingleLine = true
            setSelectAllOnFocus(true)
        }

        fun save(): Boolean {
            val raw = input.text?.toString()?.trim().orEmpty()
            if (raw.isEmpty()) {
                Toast.makeText(context, "Unchanged — current link kept", Toast.LENGTH_SHORT).show()
                return true
            }
            if (!ScreenStore.saveBase(raw)) {
                Toast.makeText(context, "Invalid URL", Toast.LENGTH_SHORT).show()
                return false
            }
            onBaseChanged?.invoke()
            Toast.makeText(context, "Saved ✓", Toast.LENGTH_SHORT).show()
            return true
        }

        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(TextView(context).apply {
                text = "Current link"
                textSize = 11f
                setTextColor(Color.GRAY)
                setPadding(0, 10, 0, 2)
            })
            addView(TextView(context).apply {
                text = currentBase
                textSize = 14f
                setTextColor(Color.parseColor("#1A73E8"))
                paint.isUnderlineText = true
                setOnClickListener { v ->
                    runCatching {
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(currentBase)
                        ).apply {
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            v.context.startActivity(this)
                        }
                    }
                }
            })
            addView(TextView(context).apply {
                text = "New link (leave blank to keep current)"
                textSize = 11f
                setTextColor(Color.GRAY)
                setPadding(0, 10, 0, 2)
            })
            addView(input)
        }

        val root = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
            )
            addView(ScrollView(context).apply {
                addView(
                    inner,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            })
        }

        AlertDialog.Builder(context)
            .setTitle("Screen Settings")
            .setView(root)
            .setPositiveButton("Save") { _, _ -> save() }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
