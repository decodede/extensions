package com.stremio.ui

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.switchmaterial.SwitchMaterial
import com.lagradost.cloudstream3.MainActivity
import com.stremio.AddonConfig
import com.stremio.ConfiguredAddon
import com.stremio.DEFAULT_PROFILE_ID
import com.stremio.StremioConstants
import com.stremio.StremioDefaultAddons
import com.stremio.StremioProfile
import com.stremio.StremioProviderRegistry
import com.stremio.StremioRepository
import com.stremio.addonDisplayHost
import com.stremio.manifestBase
import com.stremio.maybeClipboardManifest
import com.stremio.normalizeAddonUrl
import com.stremio.sanitizeProfileName
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SENTINEL = ConfiguredAddon(
    order = -1,
    displayName = "",
    base = "",
    querySuffix = "",
    idPrefixes = emptyList(),
    catalogs = emptyList(),
    hasCatalog = false,
    hasStream = false,
    hasMeta = false,
    hasSubtitles = false,
)

class StremioSettingsFragment : BottomSheetDialogFragment() {

    private lateinit var rootStore: StremioRepository
    private lateinit var profileBox: LinearLayout
    private lateinit var defaultsBox: LinearLayout
    private lateinit var ownedBox: LinearLayout
    private lateinit var emptyView: TextView
    private lateinit var status: TextView
    private lateinit var scopeLabel: TextView
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val probed = ConcurrentHashMap<String, ConfiguredAddon>()

    private var profileId: String = DEFAULT_PROFILE_ID
    private val current: StremioRepository get() = rootStore.forProfile(profileId)

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        rootStore = StremioRepository(
            requireContext().getSharedPreferences(StremioConstants.PREFS_NAME, Context.MODE_PRIVATE)
        )
        savedInstanceState?.getString(KEY_PROFILE)?.let { saved ->
            if (rootStore.profiles().any { it.id == saved }) profileId = saved
        }
    }

    override fun onSaveInstanceState(outState: android.os.Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PROFILE, profileId)
    }

    override fun onDestroyView() {
        scope.cancel()
        super.onDestroyView()
    }

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: android.os.Bundle?,
    ): View {
        val ctx = requireContext()
        val root = column().apply { setPadding(pad(), pad(), pad(), pad() * 2) }

        root.addView(title("Stremio", 20f))
        root.addView(hint("Each add-on set below is its own provider inside CloudStream, so you can keep content apart."))

        profileBox = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(
            HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                addView(profileBox)
            }
        )
        root.addView(outlined("Rename this profile") { promptRename() })
        root.addView(gap())
        root.addView(outlined("New profile") { promptNewProfile() })

        scopeLabel = hint("")
        root.addView(scopeLabel)

        root.addView(title("Default addons", 16f))
        defaultsBox = column()
        root.addView(defaultsBox)
        root.addView(pair("All on", "All off") { on ->
            StremioDefaultAddons.all.forEach { current.setDefaultEnabled(it, on) }
            render()
        })

        root.addView(title("Your addons", 16f))
        emptyView = hint("Nothing in this profile yet. Paste a manifest link below, or browse for one.")
        root.addView(emptyView)
        ownedBox = column()
        root.addView(ownedBox)

        root.addView(title("Add an addon", 16f))
        root.addView(addRow())
        status = hint("")
        root.addView(status)
        root.addView(pair("Browse", "Paste") { browse ->
            if (browse) showBrowser(ctx) else pasteFromClipboard(ctx)
        })

        root.addView(title("Playback", 16f))
        root.addView(hint("These apply to every profile."))
        root.addView(uaRow())
        root.addView(switchRow("Add public trackers to magnet links", StremioConstants.KEY_APPEND_TRACKERS, true))
        root.addView(
            switchRow(
                "Fall back to OpenSubtitles when an addon has none",
                StremioConstants.KEY_OPENSUBS,
                true,
            )
        )
        root.addView(switchRow("Verbose log (stream kinds, skipped streams)", StremioConstants.KEY_DEBUG, false))
        root.addView(hint("Top to bottom is the order addons are asked for streams. On wins on ties."))

        render()
        return ScrollView(ctx).apply { addView(root) }
    }

    private fun render() {
        renderProfiles()
        renderDefaults()
        renderOwned()
        probeManifests()
    }

    private fun renderProfiles() {
        val ctx = requireContext()
        profileBox.removeAllViews()
        rootStore.profiles().forEach { profile ->
            val selected = profile.id == profileId
            profileBox.addView(
                MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = profile.name
                    isAllCaps = false
                    setBackgroundColor(
                        if (selected) 0x332196F3 else android.graphics.Color.TRANSPARENT
                    )
                    setOnClickListener {
                        if (profileId != profile.id) {
                            profileId = profile.id
                            probed.clear()
                            status.text = ""
                            render()
                        }
                    }
                    setOnLongClickListener {
                        if (profile.isDefault) {
                            Toast.makeText(ctx, "The default profile cannot be deleted", Toast.LENGTH_SHORT).show()
                        } else {
                            confirmDelete(profile)
                        }
                        true
                    }
                    contentDescription = if (selected) "Selected profile ${profile.name}" else "Profile ${profile.name}"
                }
            )
            profileBox.addView(gap())
        }
        profileBox.addView(
            MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "+"
                isAllCaps = false
                contentDescription = "Add a profile"
                setOnClickListener { promptNewProfile() }
            }
        )
        val profile = rootStore.profiles().firstOrNull { it.id == profileId }
        val name = profile?.name ?: StremioConstants.PROVIDER_NAME
        scopeLabel.text = if (profile?.isDefault == true) {
            "Editing \"$name\". This is the default profile and cannot be deleted."
        } else {
            "Editing \"$name\". Long-press a profile to delete it."
        }
    }

    private fun promptNewProfile() {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            hint = "Anime, Movies, Live TV…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine()
        }
        AlertDialog.Builder(ctx)
            .setTitle("Add Add-ons")
            .setMessage("Name this add-on set. It becomes its own provider in CloudStream.")
            .setView(padWrap(input))
            .setPositiveButton("Create") { _, _ ->
                val name = sanitizeProfileName(input.text?.toString())
                if (name.isEmpty()) {
                    Toast.makeText(ctx, "Give it a name", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val created = rootStore.createProfile(name)
                if (created == null) {
                    Toast.makeText(ctx, "Could not create that profile", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                StremioProviderRegistry.registerProfile(
                    rootStore.forProfile(created.id),
                    StremioProviderRegistry.nameOf(rootStore, created.id),
                )
                profileId = created.id
                probed.clear()
                status.text = ""
                render()
                reloadHome()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptRename() {
        val ctx = requireContext()
        val profile = rootStore.profiles().firstOrNull { it.id == profileId } ?: return
        val input = EditText(ctx).apply {
            setText(profile.name)
            setSelection(profile.name.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine()
        }
        AlertDialog.Builder(ctx)
            .setTitle("Rename profile")
            .setView(padWrap(input))
            .setPositiveButton("Save") { _, _ ->
                if (rootStore.renameProfile(profileId, input.text?.toString().orEmpty())) {
                    StremioProviderRegistry.refreshName(
                        rootStore.forProfile(profileId),
                        StremioProviderRegistry.nameOf(rootStore, profileId),
                    )
                    render()
                    reloadHome()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(profile: StremioProfile) {
        val ctx = requireContext()
        AlertDialog.Builder(ctx)
            .setTitle("Delete \"${profile.name}\"?")
            .setMessage("Its addons are removed. Other profiles are untouched.")
            .setPositiveButton("Delete") { _, _ ->
                StremioProviderRegistry.unregister(profile.id)
                if (rootStore.deleteProfile(profile.id)) {
                    if (profileId == profile.id) {
                        profileId = DEFAULT_PROFILE_ID
                        probed.clear()
                    }
                    render()
                    reloadHome()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun probeManifests() {
        val urls = current.userAddons().map { it.manifestUrl }
        if (urls.isEmpty()) return
        scope.launch {
            val resolved = withContext(Dispatchers.IO) { current.configuredAddons() }
                .associateBy { it.base }
            val byManifest = resolved.values.associateBy { manifestBase(it.base) }
            var changed = false
            urls.forEach { url ->
                val key = manifestBase(url)
                if (probed.putIfAbsent(key, byManifest[key] ?: SENTINEL) == null) changed = true
            }
            if (changed) renderOwned()
        }
    }

    private fun describeAddon(config: AddonConfig): String {
        val info = probed[manifestBase(config.manifestUrl)]
        if (info == null || info === SENTINEL) return addonDisplayHost(config.manifestUrl)
        val version = info.version?.let { " v$it" }.orEmpty()
        val flag = if (info.needsConfiguration) " — needs setting up on its site" else ""
        return info.displayName + version + flag
    }

    private fun renderDefaults() {
        val ctx = requireContext()
        defaultsBox.removeAllViews()
        StremioDefaultAddons.all.forEach { url ->
            defaultsBox.addView(
                row {
                    addView(
                        column {
                            layoutParams = weighted()
                            addView(TextView(ctx).apply {
                                text = addonDisplayHost(url)
                                textSize = 15f
                            })
                            addView(hint(if (current.isDefaultEnabled(url)) "on" else "off"))
                        }
                    )
                    addView(
                        SwitchMaterial(ctx).apply {
                            isChecked = current.isDefaultEnabled(url)
                            contentDescription = "Enable ${addonDisplayHost(url)}"
                            setOnCheckedChangeListener { _, checked ->
                                current.setDefaultEnabled(url, checked)
                                reloadHome()
                                render()
                            }
                        }
                    )
                }
            )
            defaultsBox.addView(divider())
        }
    }

    private fun renderOwned() {
        val ctx = requireContext()
        ownedBox.removeAllViews()
        val configs = current.userAddons()
        emptyView.visibility = if (configs.isEmpty()) View.VISIBLE else View.GONE
        configs.forEachIndexed { index, config ->
            ownedBox.addView(
                row {
                    addView(
                        column {
                            layoutParams = weighted()
                            addView(TextView(ctx).apply {
                                text = describeAddon(config)
                                textSize = 15f
                            })
                            addView(hint("#${index + 1} · ${addonDisplayHost(config.manifestUrl)}"))
                        }
                    )
                    addView(
                        textButton("↑", index > 0) {
                            current.moveAddon(index, index - 1)
                            render()
                        }
                    )
                    addView(
                        textButton("↓", index < configs.size - 1) {
                            current.moveAddon(index, index + 1)
                            render()
                        }
                    )
                    addView(
                        SwitchMaterial(ctx).apply {
                            isChecked = config.enabled
                            contentDescription = "Enable ${config.name}"
                            setOnCheckedChangeListener { _, checked ->
                                current.setAddonEnabled(index, checked)
                                reloadHome()
                            }
                        }
                    )
                    addView(
                        textButton("✕") {
                            AlertDialog.Builder(ctx)
                                .setTitle("Remove addon?")
                                .setMessage(config.manifestUrl)
                                .setPositiveButton("Remove") { _, _ ->
                                    current.removeAddonAt(index)
                                    render()
                                    reloadHome()
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                    )
                }
            )
            ownedBox.addView(divider())
        }
    }

    private fun addRow(): View {
        var input: EditText? = null
        input = EditText(requireContext()).apply {
            hint = "https://host/manifest.json"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_DONE
            layoutParams = weighted()
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    add(input?.text?.toString().orEmpty())
                    true
                } else {
                    false
                }
            }
        }
        return row {
            addView(input)
            addView(textButton("Add") { add(input?.text?.toString().orEmpty()) })
        }
    }

    private fun add(raw: String) {
        val ctx = requireContext()
        val normalized = normalizeAddonUrl(raw)
        if (normalized == null) {
            status.text = "Not a manifest URL — it needs http:// or https://"
            return
        }
        if (!current.addAddon(normalized)) {
            status.text = "Already in this profile."
            return
        }
        status.text = "Added. Checking the manifest…"
        render()
        reloadHome()
        scope.launch {
            val preview = withContext(Dispatchers.IO) { current.previewAddon(normalized) }
            status.text = if (preview == null) {
                "Added, but the manifest did not load. Check the URL."
            } else {
                "${preview.name} · ${preview.catalogCount} catalogues"
            }
        }
    }

    private fun pasteFromClipboard(ctx: Context) {
        val clip = maybeClipboardManifest(ctx)
        if (clip == null) {
            Toast.makeText(ctx, "Clipboard has no addon URL", Toast.LENGTH_SHORT).show()
            return
        }
        add(clip)
    }

    private fun uaRow(): View {
        val ctx = requireContext()
        val current0 = rootStore.behaviorString(StremioConstants.KEY_UA_PRESET) ?: StremioConstants.UA_DESKTOP
        val label = TextView(ctx).apply {
            text = StremioConstants.UA_PRESETS.firstOrNull { it.second == current0 }?.first ?: "Desktop Chrome"
            textSize = 15f
            setPadding(0, pad() / 2, 0, pad() / 2)
        }
        val button = outlined("Change") {
            val choices = StremioConstants.UA_PRESETS.map { it.first }.toTypedArray()
            AlertDialog.Builder(ctx)
                .setTitle("User-Agent")
                .setSingleChoiceItems(choices, choices.indexOf(label.text.toString())) { dialog, which ->
                    rootStore.setBehavior(StremioConstants.KEY_UA_PRESET, StremioConstants.UA_PRESETS[which].second)
                    label.text = choices[which]
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        return row {
            addView(label.apply { layoutParams = weighted() })
            addView(button)
        }
    }

    private fun switchRow(text: String, key: String, fallback: Boolean): View = row {
        addView(
            TextView(requireContext()).apply {
                this.text = text
                textSize = 15f
                setPadding(0, pad() / 2, 0, pad() / 2)
                layoutParams = weighted()
            }
        )
        addView(
            SwitchMaterial(requireContext()).apply {
                isChecked = rootStore.behaviorBoolean(key, fallback)
                contentDescription = text
                setOnCheckedChangeListener { _, checked ->
                    rootStore.setBehavior(key, checked)
                    reloadHome()
                }
            }
        )
    }

    private fun reloadHome() {
        runCatching { MainActivity.reloadHomeEvent.invoke(true) }
    }

    private fun pad() = (14 * resources.displayMetrics.density).toInt()

    private fun weighted() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    private fun column(build: LinearLayout.() -> Unit = {}) = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        build()
    }

    private fun row(build: LinearLayout.() -> Unit) = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, pad() / 3, 0, pad() / 3)
        build()
    }

    private fun title(text: String, size: Float) = TextView(requireContext()).apply {
        this.text = text
        textSize = size
        setPadding(0, pad(), 0, pad() / 2)
    }

    private fun hint(text: String) = TextView(requireContext()).apply {
        this.text = text
        textSize = 12f
        maxLines = 3
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(0, 0, 0, pad() / 2)
    }

    private fun divider() = View(requireContext()).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            resources.displayMetrics.density.toInt().coerceAtLeast(1),
        )
        setBackgroundColor(0x1F000000)
    }

    private fun gap() = View(requireContext()).apply {
        layoutParams = LinearLayout.LayoutParams(pad() / 2, 1)
    }

    private fun padWrap(child: View) = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad(), pad() / 2, pad(), 0)
        addView(child)
    }

    private fun outlined(text: String, onClick: () -> Unit) = MaterialButton(
        requireContext(),
        null,
        com.google.android.material.R.attr.materialButtonOutlinedStyle,
    ).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun textButton(text: String, enabled: Boolean = true, onClick: () -> Unit) =
        MaterialButton(
            requireContext(),
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle,
        ).apply {
            this.text = text
            isAllCaps = false
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.35f
            setPadding(pad() / 2, 0, pad() / 2, 0)
            setOnClickListener { onClick() }
        }

    private fun pair(first: String, second: String, onClick: (Boolean) -> Unit) = row {
        addView(outlined(first) { onClick(true) }.apply { layoutParams = weighted() })
        addView(gap())
        addView(outlined(second) { onClick(false) }.apply { layoutParams = weighted() })
    }

    private companion object {
        const val KEY_PROFILE = "stremio_selected_profile"
    }
}

private fun showBrowser(ctx: Context) {
    val web = WebView(ctx).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.userAgentString = StremioConstants.UA_DESKTOP
        settings.mediaPlaybackRequiresUserGesture = true
        webViewClient = WebViewClient()
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (480 * ctx.resources.displayMetrics.density).toInt(),
        )
    }
    CookieManager.getInstance().setAcceptCookie(true)
    web.loadUrl(StremioConstants.BROWSE_ADDONS_URL)
    val pad = (16 * ctx.resources.displayMetrics.density).toInt()
    val container = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(web)
        addView(
            TextView(ctx).apply {
                text = "Copy an addon's manifest link, then use Paste below."
                textSize = 12f
                setPadding(pad, pad / 2, pad, pad / 2)
            }
        )
    }
    AlertDialog.Builder(ctx)
        .setTitle("Browse addons")
        .setView(container)
        .setNegativeButton("Close", null)
        .setOnDismissListener { runCatching { web.stopLoading(); web.destroy() } }
        .show()
}
