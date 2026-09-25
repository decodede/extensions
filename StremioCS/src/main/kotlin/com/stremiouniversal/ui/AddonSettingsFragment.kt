package com.stremiouniversal.ui
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.stremiouniversal.AddonConfig
import com.stremiouniversal.AddonUrlOpener
import com.stremiouniversal.StremioConstants
import com.stremiouniversal.StremioRepository
import com.stremiouniversal.addonDisplayHost
import com.stremiouniversal.normalizeAddonUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
class AddonSettingsFragment : BottomSheetDialogFragment() {
    private lateinit var repository: StremioRepository
    private lateinit var rowsBox: LinearLayout
    private lateinit var emptyView: TextView
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = requireContext().getSharedPreferences(
            StremioConstants.PREFS_NAME, Context.MODE_PRIVATE
        )
        repository = StremioRepository(prefs)
    }
    override fun onDestroyView() {
        uiScope.cancel()
        super.onDestroyView()
    }
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val pad = (16 * ctx.resources.displayMetrics.density).toInt()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad * 2)
        }
        root.addView(header(ctx, "StremioCS settings", 20f))
        root.addView(hint(ctx, "Addons are fully yours — no defaults. Browse, paste a manifest URL, reorder, toggle."))
        root.addView(header(ctx, "Addons", 16f))
        emptyView = TextView(ctx).apply {
            text = "No addons yet.\nTap Browse Addons below to find one, then paste its link in Add new."
            textSize = 13f
            setPadding(0, 12, 0, 12)
        }
        root.addView(emptyView)
        rowsBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(rowsBox)
        root.addView(header(ctx, "Add new", 16f))
        val status = TextView(ctx).apply { textSize = 12f }
        root.addView(addRow(ctx, status))
        root.addView(status)
        root.addView(hint(ctx, "Order sets catalogue & stream priority."))
        root.addView(browseRow(ctx))
        rebuildRows(ctx)
        return ScrollView(ctx).apply { addView(root) }
    }
    private fun browseRow(ctx: Context): LinearLayout {
        val browse = MaterialButton(ctx).apply {
            text = "Browse Addons"
            isAllCaps = false
            setOnClickListener { AddonBrowserDialog.show(ctx) }
        }
        val paste = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Paste"
            isAllCaps = false
            setOnClickListener {
                val clip = AddonUrlOpener.maybeClipboardManifest(ctx)
                if (clip == null) {
                    Toast.makeText(ctx, "Clipboard has no manifest URL", Toast.LENGTH_SHORT).show()
                } else {
                    tryAdd(ctx, clip, null)
                }
            }
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 4)
            addView(browse.apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            val gap = View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(12, 1)
            }
            addView(gap)
            addView(paste.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })
        }
    }
    private fun rebuildRows(ctx: Context) {
        rowsBox.removeAllViews()
        val configs = repository.loadConfiguredAddons().toMutableList()
        emptyView.visibility = if (configs.isEmpty()) View.VISIBLE else View.GONE
        if (configs.isEmpty()) return
        configs.forEachIndexed { index, config ->
            rowsBox.addView(
                addonRow(
                    ctx = ctx,
                    config = config,
                    position = index,
                    total = configs.size,
                    onToggle = { checked ->
                        repository.setAddonEnabled(index, checked)
                    },
                    onDelete = {
                        repository.removeAddonAt(index)
                        rebuildRows(ctx)
                    },
                    onMove = { delta ->
                        repository.moveAddon(index, index + delta)
                        rebuildRows(ctx)
                    }
                )
            )
            rowsBox.addView(divider(ctx))
        }
    }
    private fun addonRow(
        ctx: Context,
        config: AddonConfig,
        position: Int,
        total: Int,
        onToggle: (Boolean) -> Unit,
        onDelete: () -> Unit,
        onMove: (Int) -> Unit
    ): LinearLayout {
        val host = addonDisplayHost(config.manifestUrl)
        val title = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(ctx).apply {
                text = config.name.ifEmpty { host }
                textSize = 16f
            })
            addView(TextView(ctx).apply {
                text = config.manifestUrl
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            addView(TextView(ctx).apply {
                text = "#${position + 1} • $host"
                textSize = 11f
            })
        }
        val toggle = SwitchMaterial(ctx).apply {
            isChecked = config.enabled
            contentDescription = "Enable addon"
            setOnCheckedChangeListener { _, checked -> onToggle(checked) }
        }
        val up = ImageButton(ctx).apply {
            setImageResource(android.R.drawable.arrow_up_float)
            background = null
            contentDescription = "Move up"
            isEnabled = position > 0
            alpha = if (isEnabled) 1f else 0.3f
            setOnClickListener { onMove(-1) }
        }
        val down = ImageButton(ctx).apply {
            setImageResource(android.R.drawable.arrow_down_float)
            background = null
            contentDescription = "Move down"
            isEnabled = position < total - 1
            alpha = if (isEnabled) 1f else 0.3f
            setOnClickListener { onMove(1) }
        }
        val delete = ImageButton(ctx).apply {
            setImageResource(android.R.drawable.ic_menu_delete)
            background = null
            contentDescription = "Remove addon"
            setOnClickListener {
                AlertDialog.Builder(ctx)
                    .setTitle("Remove addon?")
                    .setMessage(config.name.ifEmpty { host } + "\n" + config.manifestUrl)
                    .setPositiveButton("Remove") { _, _ -> onDelete() }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 12)
            addView(title)
            addView(up)
            addView(down)
            addView(toggle)
            addView(delete)
        }
    }
    private fun addRow(ctx: Context, status: TextView): LinearLayout {
        val input = EditText(ctx).apply {
            hint = "Paste manifest URL (https://… or stremio://…, https preferred)"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_DONE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    tryAdd(ctx, text.toString(), status)
                    true
                } else false
            }
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 4)
            addView(input)
            addView(MaterialButton(ctx).apply {
                text = "Add"
                setOnClickListener {
                    tryAdd(ctx, input.text.toString(), status)
                    if (status.text.startsWith("Added")) input.setText("")
                }
            })
        }
    }
    private fun tryAdd(ctx: Context, raw: String, status: TextView?) {
        val normalized = normalizeAddonUrl(raw)
        if (normalized == null) {
            status?.text = "That is not a manifest URL (need http(s):// or stremio://)."
            Toast.makeText(ctx, "That is not a manifest URL", Toast.LENGTH_SHORT).show()
            return
        }
        if (!repository.addAddon(normalized)) {
            status?.text = "Addon already listed."
            Toast.makeText(ctx, "Addon already listed", Toast.LENGTH_SHORT).show()
            return
        }
        status?.text = "Added ✓ — fetching manifest name…"
        rebuildRows(ctx)
        uiScope.launch {
            val preview = withContext(Dispatchers.IO) { repository.previewAddon(normalized) }
            if (preview != null) {
                status?.text = "Added ${preview.name} (${preview.catalogCount} catalogs) ✓"
                rebuildRows(ctx)
            } else {
                status?.text = "Added, but manifest did not load — check the URL."
            }
        }
    }
    private fun header(ctx: Context, text: String, size: Float): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = size
            setPadding(0, 16, 0, 4)
        }
    private fun hint(ctx: Context, text: String): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = 12f
            setPadding(0, 2, 0, 6)
        }
    private fun divider(ctx: Context): View =
        View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (ctx.resources.displayMetrics.density).toInt()
            )
            setBackgroundColor(0x1F000000)
        }
}
