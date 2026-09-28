package com.nunodrama

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

object NunoDramaSettingsDialog {

    var onChanged: (() -> Unit)? = null

    fun show(context: Context) {
        val pad = (16 * context.resources.displayMetrics.density).toInt()

        val baseInput = EditText(context).apply {
            hint = "Site address"
            setText(NunoDramaStore.base())
            isSingleLine = true
        }

        val englishId = View.generateViewId()
        val indonesianId = View.generateViewId()
        val languageGroup = RadioGroup(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                RadioButton(context).apply {
                    id = englishId
                    text = "English"
                    isChecked = NunoDramaStore.language() == LANG_EN
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                RadioButton(context).apply {
                    id = indonesianId
                    text = "Indonesia"
                    isChecked = NunoDramaStore.language() == LANG_ID
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
        }

        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(label(context, "Content language"))
            addView(languageGroup)
            addView(label(context, "Site address (leave blank to keep current)"))
            addView(baseInput)
            addView(TextView(context).apply {
                text = NunoDramaStore.base()
                textSize = 13f
                setTextColor(Color.parseColor("#1A73E8"))
                paint.isUnderlineText = true
                setOnClickListener { open(context, NunoDramaStore.base()) }
            })
        }

        val root = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            )
            addView(ScrollView(context).apply {
                addView(
                    inner,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                    )
                )
            })
        }

        AlertDialog.Builder(context)
            .setTitle("NunoDrama Settings")
            .setView(root)
            .setPositiveButton("Save") { _, _ ->
                var changed = false
                if (NunoDramaStore.saveBase(baseInput.text?.toString())) changed = true
                val picked = when (languageGroup.checkedRadioButtonId) {
                    englishId -> LANG_EN
                    indonesianId -> LANG_ID
                    else -> null
                }
                if (picked != null && NunoDramaStore.saveLanguage(picked)) changed = true
                if (changed) {
                    onChanged?.invoke()
                    Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Refresh providers") { _, _ ->
                onChanged?.invoke()
                Toast.makeText(context, "Provider list refreshed", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
                android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
