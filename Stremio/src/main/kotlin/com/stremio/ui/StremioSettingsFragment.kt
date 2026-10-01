package com.stremio.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
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

private const val BG = 0xFF12121A.toInt()
private const val BG_HERO = 0xFF1A1730.toInt()
private const val SURFACE = 0xFF1C1C26.toInt()
private const val TEXT_PRIMARY = 0xFFF2F2F7.toInt()
private const val TEXT_SECONDARY = 0xFF9E9EA7.toInt()
private const val HAIRLINE = 0x1FFFFFFF
private const val PILL_OFF = 0x332196F3

private val ACCENT_ADDONS = 0xFF22D3EE.toInt() to 0xFF0891B2.toInt()
private val ACCENT_QUICK = 0xFF10B981.toInt() to 0xFF059669.toInt()
private val ACCENT_PROFILES = 0xFF6C63FF.toInt() to 0xFFA855F7.toInt()
private val ACCENT_PLAYBACK = 0xFFF59E0B.toInt() to 0xFF818CF8.toInt()

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
    private lateinit var addonsBox: LinearLayout
    private lateinit var addonsEmpty: TextView
    private lateinit var status: TextView
    private lateinit var quickBox: LinearLayout
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
        val root = column()
        root.addView(hero())

        root.addView(
            card("📡  Add-ons", ACCENT_ADDONS) {
                addView(hint("Paste an add-on's manifest link, or browse the directory. Add-ons appear as providers in CloudStream."))
                addonsEmpty = hint("No add-ons in this profile yet.")
                addView(addonsEmpty)
                addonsBox = column()
                addView(addonsBox)
                addView(divider())
                addView(addRow())
                status = hint("")
                addView(status)
                addView(pair("Browse directory", "Paste from clipboard") { browse ->
                    if (browse) showBrowser(ctx) else pasteFromClipboard(ctx)
                })
            }
        )

        root.addView(
            card("⚡  Ready-made add-ons", ACCENT_QUICK) {
                addView(hint("Turn these on to start quickly. They ship off so nothing is added without you asking."))
                quickBox = column()
                addView(quickBox)
                addView(pair("Turn all on", "Turn all off") { on ->
                    StremioDefaultAddons.all.forEach { current.setDefaultEnabled(it, on) }
                    render()
                })
            }
        )

        profileBox = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(
            card("👤  Profiles", ACCENT_PROFILES) {
                addView(hint("A profile is its own set of add-ons and its own provider, so you can keep content apart."))
                addView(
                    HorizontalScrollView(ctx).apply {
                        isHorizontalScrollBarEnabled = false
                        addView(profileBox)
                    }
                )
                scopeLabel = hint("")
                addView(scopeLabel)
                addView(pair("New profile", "Rename this one") { new ->
                    if (new) promptNewProfile() else promptRename()
                })
            }
        )

        root.addView(
            card("🔧  Playback", ACCENT_PLAYBACK) {
                addView(hint("These apply to every profile."))
                addView(uaRow())
                addView(toggleRow("Add trackers to magnet links", "Helps peers find each other when a stream is downloaded later.", StremioConstants.KEY_APPEND_TRACKERS, true))
                addView(
                    toggleRow(
                        "Fall back to OpenSubtitles",
                        "Used only when an add-on offers no subtitles of its own.",
                        StremioConstants.KEY_OPENSUBS,
                        true,
                    )
                )
                addView(toggleRow("Verbose log", "Records stream kinds and anything skipped, for debugging.", StremioConstants.KEY_DEBUG, false))
            }
        )

        render()
        return ScrollView(ctx).apply {
            isScrollbarFadingEnabled = true
            addView(root)
        }
    }

    private fun render() {
        renderProfiles()
        renderQuick()
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
                    setBackgroundColor(if (selected) PILL_OFF else Color.TRANSPARENT)
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
        val profile = rootStore.profiles().firstOrNull { it.id == profileId }
        val name = profile?.name ?: StremioConstants.PROVIDER_NAME
        scopeLabel.text = if (profile?.isDefault == true) {
            "Editing \"$name\". The default profile cannot be deleted."
        } else {
            "Editing \"$name\". Long-press a profile to delete it."
        }
    }

    private fun renderQuick() {
        val ctx = requireContext()
        quickBox.removeAllViews()
        StremioDefaultAddons.all.forEach { url ->
            quickBox.addView(
                row {
                    addView(
                        column {
                            layoutParams = weighted()
                            addView(label(addonDisplayHost(url)))
                            addView(
                                caption(
                                    if (current.isDefaultEnabled(url)) "On" else "Off",
                                    primary = current.isDefaultEnabled(url),
                                )
                            )
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
            quickBox.addView(divider())
        }
    }

    private fun renderOwned() {
        val ctx = requireContext()
        addonsBox.removeAllViews()
        val configs = current.userAddons()
        addonsEmpty.visibility = if (configs.isEmpty()) View.VISIBLE else View.GONE
        configs.forEachIndexed { index, config ->
            addonsBox.addView(
                row {
                    addView(
                        column {
                            layoutParams = weighted()
                            addView(label(describeAddon(config)))
                            addView(caption("#${index + 1} · ${addonDisplayHost(config.manifestUrl)}"))
                        }
                    )
                    addView(textButton("↑", index > 0) {
                        current.moveAddon(index, index - 1)
                        render()
                    })
                    addView(textButton("↓", index < configs.size - 1) {
                        current.moveAddon(index, index + 1)
                        render()
                    })
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
                    addView(textButton("✕") { confirmRemove(index, config) })
                }
            )
            addonsBox.addView(divider())
        }
        if (configs.isNotEmpty()) {
            addonsBox.addView(caption("Top to bottom is the order add-ons are asked for streams."))
        }
    }

    private fun confirmRemove(index: Int, config: AddonConfig) {
        val ctx = requireContext()
        AlertDialog.Builder(ctx)
            .setTitle("Remove add-on?")
            .setMessage(config.manifestUrl)
            .setPositiveButton("Remove") { _, _ ->
                current.removeAddonAt(index)
                render()
                reloadHome()
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

    private fun promptNewProfile() {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            hint = "Anime, Movies, Live TV…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine()
        }
        AlertDialog.Builder(ctx)
            .setTitle("New profile")
            .setMessage("It becomes its own provider in CloudStream.")
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
            .setMessage("Its add-ons are removed. Other profiles are untouched.")
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
        val normalized = normalizeAddonUrl(raw)
        if (normalized == null) {
            status.text = "That needs to be an http:// or https:// manifest link"
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
            Toast.makeText(ctx, "Clipboard has no add-on URL", Toast.LENGTH_SHORT).show()
            return
        }
        add(clip)
    }

    private fun uaRow(): View {
        val ctx = requireContext()
        val stored = rootStore.behaviorString(StremioConstants.KEY_UA_PRESET) ?: StremioConstants.UA_DESKTOP
        val label = caption(
            StremioConstants.UA_PRESETS.firstOrNull { it.second == stored }?.first ?: "Desktop Chrome",
            primary = true,
        )
        return row {
            addView(label.apply { layoutParams = weighted() })
            addView(outlined("Change") {
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
            })
        }
    }

    private fun toggleRow(text: String, help: String, key: String, fallback: Boolean): View = row {
        addView(
            column {
                layoutParams = weighted()
                addView(label(text))
                addView(hint(help))
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

    private fun hero() = column {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(26), dp(22), dp(18))
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(BG_HERO, BG),
        )
        addView(
            TextView(requireContext()).apply {
                text = "Stremio"
                textSize = 22f
                setTypeface(null, Typeface.BOLD)
                setTextColor(TEXT_PRIMARY)
                letterSpacing = -0.02f
            }
        )
        addView(
            TextView(requireContext()).apply {
                text = "Add-ons, profiles and playback"
                textSize = 13f
                setTextColor(TEXT_SECONDARY)
                setPadding(0, dp(6), 0, 0)
            }
        )
    }

    private fun card(
        title: String,
        accent: Pair<Int, Int>,
        block: LinearLayout.() -> Unit,
    ): View {
        val ctx = requireContext()
        val content = column {
            setPadding(dp(16), 0, dp(16), dp(8))
            block()
        }
        val chevron = TextView(ctx).apply {
            text = "▼"
            textSize = 11f
            setTextColor(TEXT_SECONDARY)
        }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            setBackgroundColor(BG)
            isClickable = true
            isFocusable = true
            addView(
                View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(3), dp(18))
                    background = GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(accent.first, accent.second),
                    )
                }
            )
            addView(
                TextView(ctx).apply {
                    layoutParams = weighted()
                    text = title
                    textSize = 12f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(TEXT_SECONDARY)
                    letterSpacing = 0.08f
                }
            )
            addView(chevron)
        }
        content.visibility = View.GONE
        header.setOnClickListener {
            val open = content.visibility != View.VISIBLE
            content.visibility = if (open) View.VISIBLE else View.GONE
            chevron.text = if (open) "▲" else "▼"
            header.contentDescription = if (open) "Collapse $title" else "Expand $title"
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(SURFACE)
            }
            addView(header)
            addView(divider())
            addView(content)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, dp(10), 0, 0) }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun weighted() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    private fun column(build: LinearLayout.() -> Unit = {}) = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        build()
    }

    private fun row(build: LinearLayout.() -> Unit) = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(4), 0, dp(4))
        build()
    }

    private fun label(text: String) = TextView(requireContext()).apply {
        this.text = text
        textSize = 15f
        setTextColor(TEXT_PRIMARY)
    }

    private fun caption(text: String, primary: Boolean = false) = TextView(requireContext()).apply {
        this.text = text
        textSize = 12f
        setTextColor(if (primary) TEXT_PRIMARY else TEXT_SECONDARY)
        setPadding(0, dp(2), 0, 0)
    }

    private fun hint(text: String) = TextView(requireContext()).apply {
        this.text = text
        textSize = 12f
        setTextColor(TEXT_SECONDARY)
        maxLines = 4
        ellipsize = TextUtils.TruncateAt.END
        setPadding(0, dp(4), 0, dp(2))
    }

    private fun divider() = View(requireContext()).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            resources.displayMetrics.density.toInt().coerceAtLeast(1),
        )
        setBackgroundColor(HAIRLINE)
    }

    private fun gap() = View(requireContext()).apply {
        layoutParams = LinearLayout.LayoutParams(dp(8), 1)
    }

    private fun padWrap(child: View) = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), 0)
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
            setPadding(dp(8), 0, dp(8), 0)
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
                text = "Copy an add-on's manifest link, then use Paste."
                textSize = 12f
                setPadding(pad, pad / 2, pad, pad / 2)
            }
        )
    }
    AlertDialog.Builder(ctx)
        .setTitle("Browse add-ons")
        .setView(container)
        .setNegativeButton("Close", null)
        .setOnDismissListener { runCatching { web.stopLoading(); web.destroy() } }
        .show()
}