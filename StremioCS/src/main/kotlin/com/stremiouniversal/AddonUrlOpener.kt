package com.stremiouniversal
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast

object AddonUrlOpener {
    fun maybeClipboardManifest(context: Context): String? = try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return null
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
            .orEmpty()
        if (text.isEmpty()) return null
        normalizeAddonUrl(text)
    } catch (_: Exception) {
        null
    }
    fun copyToClipboard(context: Context, label: String, text: String) {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return
            cm.setPrimaryClip(ClipData.newPlainText(label, text))
            Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(context, "Copy failed", Toast.LENGTH_SHORT).show()
        }
    }
}
