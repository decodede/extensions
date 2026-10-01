package com.stremio.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
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
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
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

private const val BG = 0xFF0D0F14.toInt()
private const val BG_HERO = 0xFF1A1730.toInt()
private const val CARD = 0xFF13161E.toInt()
private const val CARD_BORDER = 0xFF1F2235.toInt()
private const val ROW = 0xFF171B25.toInt()
private const val INPUT = 0xFF0D1117.toInt()
private const val INPUT_BORDER = 0xFF2E2850.toInt()
private const val TEXT_PRIMARY = 0xFFF0F2FF.toInt()
private const val TEXT_SECONDARY = 0xFF7B82A0.toInt()
private const val SWITCH_ON = 0xFF6C63FF.toInt()
private const val SWITCH_OFF = 0xFF2A2D3E.toInt()
private const val DANGER = 0xFFFF4E6A.toInt()
private const val DANGER_BG = 0x22F87171
private const val INFO_BG = 0xFF0C1A2E.toInt()
private const val INFO_BORDER = 0xFF1D4ED8.toInt()
private const val INFO_DOT = 0xFF3B82F6.toInt()
private const val INFO_HEAD = 0xFF60A5FA.toInt()
private const val INFO_BODY = 0xFF93C5FD.toInt()

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

fun showStremioSettings(context: Context) {
    StremioSettings(context).present()
}

private class StremioSettings(context: Context) {

    private val ctx = context
    private val rootStore = StremioRepository(
        context.getSharedPreferences(StremioConstants.PREFS_NAME, Context.MODE_PRIVATE)
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val probed = ConcurrentHashMap<String, ConfiguredAddon>()

    private val addonsBox = column()
    private val quickBox = column()
    private val profileBox = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    private val addonsEmpty = hint("No add-ons in this profile yet.")
    private val status = hint("")
    private val scopeLabel = hint("")
    private val addonBadge = TextView(ctx).apply {
        textSize = 11f
        setTextColor(TEXT_SECONDARY)
    }
    private val quickBadge = TextView(ctx).apply {
        textSize = 11f
        setTextColor(TEXT_SECONDARY)
    }
    private val profileBadge = TextView(ctx).apply {
        textSize = 11f
        setTextColor(TEXT_SECONDARY)
    }
    private val userAgentLabel = caption(StremioConstants.UA_DESKTOP, primary = true)

    private var profileId: String = DEFAULT_PROFILE_ID
    private val current: StremioRepository get() = rootStore.forProfile(profileId)

    fun present() {
        val root = column {
            setPadding(0, 0, 0, dp(20))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(BG_HERO, BG))
            addView(hero())
        }
        root.addView(
            card("📡  Add-ons", ACCENT_ADDONS, addonBadge) {
                addView(infoBanner())
                addView(hint("Paste an add-on's manifest link, or browse the directory. Add-ons appear as providers in CloudStream."))
                addView(addonsEmpty)
                addView(addonsBox)
                addView(divider())
                addView(addRow())
                addView(status)
                addView(pair("Browse directory", "Paste from clipboard") { browse ->
                    if (browse) showBrowser(ctx) else pasteFromClipboard()
                })
            }
        )
        root.addView(
            card("⚡  Ready-made add-ons", ACCENT_QUICK, quickBadge) {
                addView(hint("Turn these on to start quickly. They ship off so nothing is added without you asking."))
                addView(quickBox)
                addView(pair("Turn all on", "Turn all off") { on ->
                    StremioDefaultAddons.all.forEach { current.setDefaultEnabled(it, on) }
                    reloadHome()
                    render()
                })
            }
        )
        root.addView(
            card("👤  Profiles", ACCENT_PROFILES, profileBadge) {
                addView(hint("A profile is its own set of add-ons and its own provider, so you can keep content apart."))
                addView(
                    HorizontalScrollView(ctx).apply {
                        isHorizontalScrollBarEnabled = false
                        addView(profileBox)
                    }
                )
                addView(scopeLabel)
                addView(pair("New profile", "Rename this one") { new ->
                    if (new) promptNewProfile() else promptRename()
                })
            }
        )
        root.addView(
            card("🔧  Playback", ACCENT_PLAYBACK, null) {
                addView(hint("These apply to every profile."))
                addView(uaRow())
                addView(
                    toggleRow(
                        "Add trackers to magnet links",
                        "Helps peers find each other when a stream is downloaded later.",
                        StremioConstants.KEY_APPEND_TRACKERS,
                        true,
                    )
                )
                addView(
                    toggleRow(
                        "Fall back to OpenSubtitles",
                        "Used only when an add-on offers no subtitles of its own.",
                        StremioConstants.KEY_OPENSUBS,
                        true,
                    )
                )
                addView(
                    toggleRow(
                        "Verbose log",
                        "Records stream kinds and anything skipped, for debugging.",
                        StremioConstants.KEY_DEBUG,
                        false,
                    )
                )
            }
        )

        render()

        val dialog = AlertDialog.Builder(ctx, android.R.style.Theme_Material_Dialog)
            .setView(ScrollView(ctx).apply { addView(root) })
            .create()
        dialog.window?.setBackgroundDrawable(roundRect(BG, dp(20).toFloat()))
        dialog.setOnDismissListener { scope.cancel() }
        dialog.show()
        dialog.window?.setLayout(
            (ctx.resources.displayMetrics.widthPixels * 0.92f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun render() {
        renderProfiles()
        renderQuick()
        renderOwned()
        probeManifests()
    }

    private fun renderProfiles() {
        profileBox.removeAllViews()
        val profiles = rootStore.profiles()
        profiles.forEach { profile ->
            val selected = profile.id == profileId
            profileBox.addView(
                pill(profile.name, selected, onClick = {
                    if (profileId != profile.id) {
                        profileId = profile.id
                        probed.clear()
                        status.text = ""
                        render()
                    }
                }, onLongClick = {
                    if (profile.isDefault) {
                        Toast.makeText(ctx, "The default profile cannot be deleted", Toast.LENGTH_SHORT).show()
                    } else {
                        promptDelete(profile)
                    }
                    true
                })
            )
            profileBox.addView(gap())
        }
        val profile = profiles.firstOrNull { it.id == profileId }
        val name = profile?.name ?: StremioConstants.PROVIDER_NAME
        profileBadge.text = if (profiles.size <= 1) "" else profiles.joinToString(" · ") { it.name }
        scopeLabel.text = if (profile?.isDefault == true) {
            "Editing \"$name\". The default profile cannot be deleted."
        } else {
            "Editing \"$name\". Long-press a profile to delete it."
        }
    }

    private fun renderQuick() {
        quickBox.removeAllViews()
        StremioDefaultAddons.all.forEach { url ->
            val on = current.isDefaultEnabled(url)
            quickBox.addView(
                toggleRow(addonDisplayHost(url), if (on) "On" else "Off", on) {
                    current.setDefaultEnabled(url, it)
                    reloadHome()
                    render()
                }
            )
            quickBox.addView(divider())
        }
        val enabled = StremioDefaultAddons.all.count { current.isDefaultEnabled(it) }
        quickBadge.text = "$enabled of ${StremioDefaultAddons.all.size} on"
    }

    private fun renderOwned() {
        val configs = current.userAddons()
        addonsBox.removeAllViews()
        addonsEmpty.visibility = if (configs.isEmpty()) View.VISIBLE else View.GONE
        addonBadge.text = "${configs.size} addon${if (configs.size == 1) "" else "s"}"
        configs.forEachIndexed { index, config ->
            addonsBox.addView(
                column {
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    background = roundRect(ROW, dp(10).toFloat())
                    addView(
                        row {
                            addView(
                                TextView(ctx).apply {
                                    text = "${index + 1}"
                                    textSize = 12f
                                    gravity = Gravity.CENTER
                                    setTextColor(TEXT_SECONDARY)
                                    background = roundRect(INPUT, dp(12).toFloat())
                                    layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                                        gravity = Gravity.CENTER_VERTICAL
                                    }
                                }
                            )
                            addView(gap())
                            addView(
                                column {
                                    layoutParams = weighted()
                                    addView(label(describeAddon(config), 13f))
                                    addView(caption("#${index + 1} · ${addonDisplayHost(config.manifestUrl)}", size = 10f))
                                }
                            )
                            addView(
                                switch(config.enabled, "Enable ${config.name}") { checked ->
                                    current.setAddonEnabled(index, checked)
                                    reloadHome()
                                }
                            )
                        }
                    )
                    addView(
                        row {
                            addView(
                                arrow("▲", index > 0) {
                                    current.moveAddon(index, index - 1)
                                    render()
                                }
                            )
                            addView(arrow("▼", index < configs.size - 1) {
                                current.moveAddon(index, index + 1)
                                render()
                            })
                            addView(gap())
                            addView(
                                TextView(ctx).apply {
                                    text = "✕"
                                    textSize = 13f
                                    gravity = Gravity.CENTER
                                    setTextColor(DANGER)
                                    background = withRipple(roundRect(DANGER_BG, dp(18).toFloat()))
                                    layoutParams = LinearLayout.LayoutParams(dp(36), dp(32)).apply {
                                        gravity = Gravity.CENTER_VERTICAL
                                    }
                                    isClickable = true
                                    isFocusable = true
                                    contentDescription = "Remove ${config.name}"
                                    setOnClickListener { confirmRemove(index, config) }
                                }
                            )
                        }
                    )
                }
            )
            addonsBox.addView(divider())
        }
        if (configs.isNotEmpty()) {
            addonsBox.addView(caption("Top to bottom is the order add-ons are asked for streams.", size = 11f))
        }
    }

    private fun confirmRemove(index: Int, config: AddonConfig) {
        AlertDialog.Builder(ctx, android.R.style.Theme_Material_Dialog)
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
        val input = EditText(ctx).apply {
            hint = "Anime, Movies, Live TV…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine()
        }
        AlertDialog.Builder(ctx, android.R.style.Theme_Material_Dialog)
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
        val profile = rootStore.profiles().firstOrNull { it.id == profileId } ?: return
        val input = EditText(ctx).apply {
            setText(profile.name)
            setSelection(profile.name.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine()
        }
        AlertDialog.Builder(ctx, android.R.style.Theme_Material_Dialog)
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

    private fun promptDelete(profile: StremioProfile) {
        AlertDialog.Builder(ctx, android.R.style.Theme_Material_Dialog)
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
        val input = EditText(ctx).apply {
            hint = "https://host/manifest.json"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_DONE
            layoutParams = weighted()
            background = roundRect(INPUT, dp(10).toFloat()).also { it.setStroke(dp(1), INPUT_BORDER) }
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    add(text?.toString().orEmpty())
                    true
                } else {
                    false
                }
            }
        }
        return row {
            addView(input)
            addView(gap())
            addView(
                MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "Add"
                    isAllCaps = false
                    setOnClickListener { add(input.text?.toString().orEmpty()) }
                }
            )
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

    private fun pasteFromClipboard() {
        val clip = maybeClipboardManifest(ctx)
        if (clip == null) {
            Toast.makeText(ctx, "Clipboard has no add-on URL", Toast.LENGTH_SHORT).show()
            return
        }
        add(clip)
    }

    private fun uaRow(): View {
        val stored = rootStore.behaviorString(StremioConstants.KEY_UA_PRESET) ?: StremioConstants.UA_DESKTOP
        userAgentLabel.text = StremioConstants.UA_PRESETS.firstOrNull { it.second == stored }?.first
            ?: "Desktop Chrome"
        return row {
            addView(userAgentLabel.apply { layoutParams = weighted() })
            addView(
                MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "Change"
                    isAllCaps = false
                    setOnClickListener {
                        val choices = StremioConstants.UA_PRESETS.map { it.first }.toTypedArray()
                        AlertDialog.Builder(ctx, android.R.style.Theme_Material_Dialog)
                            .setTitle("User-Agent")
                            .setSingleChoiceItems(choices, choices.indexOf(userAgentLabel.text.toString())) { d, which ->
                                rootStore.setBehavior(
                                    StremioConstants.KEY_UA_PRESET,
                                    StremioConstants.UA_PRESETS[which].second,
                                )
                                userAgentLabel.text = choices[which]
                                d.dismiss()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
            )
        }
    }

    private fun toggleRow(
        title: String,
        help: String,
        key: String,
        fallback: Boolean,
    ): View {
        val toggle = switch(rootStore.behaviorBoolean(key, fallback), title) { checked ->
            rootStore.setBehavior(key, checked)
            reloadHome()
        }
        return row {
            addView(
                column {
                    layoutParams = weighted()
                    addView(label(title))
                    addView(hint(help))
                }
            )
            addView(toggle)
        }
    }

    private fun toggleRow(title: String, state: String, on: Boolean, onChange: (Boolean) -> Unit): View {
        val toggle = switch(on, title, onChange)
        return row {
            addView(
                column {
                    layoutParams = weighted()
                    addView(label(title))
                    addView(caption(state, primary = on, size = 11f))
                }
            )
            addView(toggle)
        }
    }

    private fun reloadHome() {
        runCatching { com.lagradost.cloudstream3.MainActivity.reloadHomeEvent.invoke(true) }
    }

    private fun hero() = column {
        setPadding(dp(28), dp(32), dp(28), dp(24))
        addView(
            View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(4))
                background = roundRect(ACCENT_PROFILES.first, 99f)
                background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(ACCENT_PROFILES.first, ACCENT_PROFILES.second),
                )
            }
        )
        addView(
            TextView(ctx).apply {
                text = "Stremio"
                textSize = 22f
                setTypeface(null, Typeface.BOLD)
                setTextColor(TEXT_PRIMARY)
                letterSpacing = -0.02f
            }
        )
        addView(
            TextView(ctx).apply {
                text = "Configure add-ons, profiles and playback"
                textSize = 13f
                setTextColor(TEXT_SECONDARY)
                setPadding(0, dp(6), 0, 0)
            }
        )
    }

    private fun infoBanner() = column {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = roundRect(INFO_BG, dp(10).toFloat()).also { it.setStroke(dp(2), INFO_BORDER) }
        addView(
            View(ctx).apply {
                background = roundRect(INFO_DOT, 99f)
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    rightMargin = dp(8)
                    topMargin = dp(5)
                }
            }
        )
        addView(
            column {
                addView(
                    TextView(ctx).apply {
                        text = "How this works"
                        textSize = 12f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(INFO_HEAD)
                    }
                )
                addView(
                    TextView(ctx).apply {
                        text = "Each add-on becomes its own provider. #1 is asked for streams first."
                        textSize = 11f
                        setTextColor(INFO_BODY)
                    }
                )
            }
        )
    }

    private fun card(
        title: String,
        accent: Pair<Int, Int>,
        badge: TextView?,
        block: LinearLayout.() -> Unit,
    ): View {
        val content = column {
            setPadding(dp(12), 0, dp(12), dp(8))
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
            setPadding(dp(16), dp(16), dp(16), dp(16))
            isClickable = true
            isFocusable = true
            contentDescription = "Expand $title"
            addView(
                View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(3), dp(18))
                    background = GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(accent.first, accent.second),
                    )
                }
            )
            addView(gap())
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
            if (badge != null) {
                addView(badge)
                addView(gap())
            }
            addView(chevron)
        }
        header.background = withRipple(roundRect(CARD, 0f))
        content.visibility = View.GONE
        header.setOnClickListener {
            val open = content.visibility != View.VISIBLE
            content.visibility = if (open) View.VISIBLE else View.GONE
            chevron.text = if (open) "▲" else "▼"
            header.contentDescription = if (open) "Collapse $title" else "Expand $title"
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(CARD, dp(16).toFloat()).also { it.setStroke(dp(1), CARD_BORDER) }
            elevation = 4f
            addView(header)
            addView(divider())
            addView(content)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(dp(16), dp(12), dp(16), 0) }
        }
    }

    private fun pill(text: String, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Boolean = { false }) =
        MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.text = text
            isAllCaps = false
            setBackgroundColor(if (selected) 0x336C63FF else 0x00000000)
            setOnClickListener { onClick() }
            setOnLongClickListener { onLongClick() }
            contentDescription = if (selected) "Selected profile $text" else "Profile $text"
        }

    private fun arrow(text: String, enabled: Boolean, onClick: () -> Unit) = TextView(ctx).apply {
        this.text = text
        textSize = 11f
        gravity = Gravity.CENTER
        setTextColor(if (enabled) TEXT_PRIMARY else SWITCH_OFF)
        background = withRipple(roundRect(SWITCH_OFF, dp(8).toFloat()))
        layoutParams = LinearLayout.LayoutParams(dp(32), dp(28)).apply { gravity = Gravity.CENTER_VERTICAL }
        isEnabled = enabled
        isClickable = enabled
        isFocusable = enabled
        alpha = if (enabled) 1f else 0.4f
        contentDescription = if (text == "▲") "Move up" else "Move down"
        setOnClickListener { onClick() }
    }

    private fun switch(checked: Boolean, description: String, onChange: (Boolean) -> Unit) =
        Switch(ctx).apply {
            isChecked = checked
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(0xFFFFFFFF.toInt(), TEXT_SECONDARY),
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(SWITCH_ON, SWITCH_OFF),
            )
            contentDescription = description
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }

    private fun dp(value: Int) = (value * ctx.resources.displayMetrics.density).toInt()

    private fun weighted() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    private fun column(build: LinearLayout.() -> Unit = {}) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        build()
    }

    private fun row(build: LinearLayout.() -> Unit) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(4), 0, dp(4))
        build()
    }

    private fun label(text: String, size: Float = 15f) = TextView(ctx).apply {
        this.text = text
        textSize = size
        setTextColor(TEXT_PRIMARY)
    }

    private fun caption(text: String, primary: Boolean = false, size: Float = 12f) = TextView(ctx).apply {
        this.text = text
        textSize = size
        setTextColor(if (primary) TEXT_PRIMARY else TEXT_SECONDARY)
        setPadding(0, dp(2), 0, 0)
    }

    private fun hint(text: String) = TextView(ctx).apply {
        this.text = text
        textSize = 12f
        setTextColor(TEXT_SECONDARY)
        maxLines = 5
        ellipsize = TextUtils.TruncateAt.END
        setPadding(0, dp(4), 0, dp(2))
    }

    private fun divider() = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            ctx.resources.displayMetrics.density.toInt().coerceAtLeast(1),
        )
        setBackgroundColor(CARD_BORDER)
    }

    private fun gap() = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(dp(8), 1)
    }

    private fun padWrap(child: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), 0)
        addView(child)
    }

    private fun roundRect(color: Int, radius: Float) = GradientDrawable().apply {
        cornerRadius = radius
        setColor(color)
    }

    private fun withRipple(shape: GradientDrawable) = RippleDrawable(
        ColorStateList.valueOf(0x40FFFFFF),
        shape,
        shape,
    )

    private fun pair(first: String, second: String, onClick: (Boolean) -> Unit) = row {
        addView(outlined(first) { onClick(true) }.apply { layoutParams = weighted() })
        addView(gap())
        addView(outlined(second) { onClick(false) }.apply { layoutParams = weighted() })
    }

    private fun outlined(text: String, onClick: () -> Unit) = MaterialButton(
        ctx,
        null,
        com.google.android.material.R.attr.materialButtonOutlinedStyle,
    ).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }
}

private fun showBrowser(context: Context) {
    val web = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.userAgentString = StremioConstants.UA_DESKTOP
        settings.mediaPlaybackRequiresUserGesture = true
        webViewClient = WebViewClient()
    }
    CookieManager.getInstance().setAcceptCookie(true)
    web.loadUrl(StremioConstants.BROWSE_ADDONS_URL)
    val pad = (16 * context.resources.displayMetrics.density).toInt()
    val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(
            FrameLayout(context).apply {
                addView(
                    web,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        (420 * context.resources.displayMetrics.density).toInt(),
                    )
                )
            }
        )
        addView(
            TextView(context).apply {
                text = "Copy an add-on's manifest link, then use Paste."
                textSize = 12f
                setPadding(pad, pad / 2, pad, pad / 2)
            }
        )
    }
    AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog)
        .setTitle("Browse add-ons")
        .setView(container)
        .setNegativeButton("Close", null)
        .setOnDismissListener { runCatching { web.stopLoading(); web.destroy() } }
        .show()
}