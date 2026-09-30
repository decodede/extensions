package com.stremio

private fun expect(condition: Boolean, message: () -> String) {
    if (!condition) throw AssertionError(message())
}

fun profileNameIsSanitised() {
    expect(sanitizeProfileName("  Anime   movies  ") == "Anime movies") { "whitespace not collapsed" }
    expect(sanitizeProfileName(null) == "") { "null should be empty" }
    expect(sanitizeProfileName("   ") == "") { "blank should be empty" }
    expect(sanitizeProfileName("a".repeat(80)).length == 40) { "name not capped" }
    expect(sanitizeProfileName("x".repeat(39) + " tail").length == 40) { "name not capped" }
    expect(sanitizeProfileName("a\u0000b\u0007c") == "abc") { "control characters must be dropped" }
    expect(sanitizeProfileName("Line\nBreak") == "Line Break") { "newline must not survive" }
    expect(uniqueProfileName("a\u0000", emptyList()) == "a") { "control-only name" }
}

fun profileNamesDoNotCollide() {    expect(uniqueProfileName("Anime", emptyList()) == "Anime") { "first use should be untouched" }

    val afterOne = uniqueProfileName("Anime", listOf("Anime"))
    expect(afterOne == "Anime 2") { "expected 'Anime 2', got '$afterOne'" }

    val afterTwo = uniqueProfileName("Anime", listOf("Anime", "Anime 2"))
    expect(afterTwo == "Anime 3") { "expected 'Anime 3', got '$afterTwo'" }

    expect(uniqueProfileName("anime", listOf("Anime")) == "anime 2") { "case-insensitive clash missed" }
    expect(uniqueProfileName("  ANIME ", listOf("anime")) == "ANIME 2") { "trim+case clash missed" }

    val long = "a".repeat(40)
    val suffixed = uniqueProfileName(long, listOf(long))
    expect(suffixed == "a".repeat(38) + " 2") { "long name suffix wrong: '$suffixed'" }
    expect(suffixed.length <= 40) { "disambiguated name exceeded the cap" }

    expect(uniqueProfileName("   ", listOf("Anime")).isEmpty()) { "blank request should stay blank" }
}

fun profileIdsAreUnique() {
    val ids = HashSet<String>()
    repeat(2000) { ids.add(newProfileId()) }
    expect(ids.size == 2000) { "id collision: only ${ids.size}/2000 unique" }
    expect(newProfileId().length == 16) { "id wrong length" }
    expect(newProfileId().all { it in "0123456789abcdef" }) { "id not hex" }
}

fun profileStorageIsNamespaced() {
    expect(profileAddonsKey(DEFAULT_PROFILE_ID) == StremioConstants.KEY_ADDONS) {
        "the default profile must keep the legacy key so existing installs keep working"
    }
    val named = profileAddonsKey("abc123")
    expect(named != StremioConstants.KEY_ADDONS) { "named profile must not use the default key" }
    expect(profileAddonsKey("abc123") == named) { "profile key must be stable" }
    expect(profileAddonsKey("abc123") != profileAddonsKey("def456")) { "two profiles share a key" }

    expect(profileCatalogKey(DEFAULT_PROFILE_ID) != profileCatalogKey("abc123")) {
        "the dedupe cache must be namespaced or the second profile's page 1 comes back empty"
    }
    val a = catalogSeenKey(DEFAULT_PROFILE_ID, "https://x.example", "top")
    val b = catalogSeenKey("abc123", "https://x.example", "top")
    expect(a != b) { "two profiles share a catalogue dedupe key" }
    expect(catalogSeenKey("abc123", "https://x.example", "top") == b) { "dedupe key not stable" }
    expect(catalogSeenKey("abc123", "https://y.example", "top") != b) { "dedupe key ignores the addon" }
    expect(b.startsWith(profileCatalogKey("abc123"))) { "dedupe key must be removable per profile" }

    expect(profileMainUrl(DEFAULT_PROFILE_ID) != profileMainUrl("abc123")) {
        "each provider needs its own mainUrl or CloudStream cannot tell them apart"
    }
    expect(profileMainUrl("abc123") != profileMainUrl("def456")) { "provider mainUrl collided" }
}

fun profilesDoNotShareAddonStorage() {
    val prefs = FakePrefs()
    val store = StremioRepository(prefs)

    val anime = store.createProfile("Anime")
    val live = store.createProfile("Live TV")
    expect(anime != null && live != null) { "profile creation failed" }
    expect(anime!!.id != live!!.id) { "two profiles got the same id" }

    val animeRepo = store.forProfile(anime.id)
    val liveRepo = store.forProfile(live.id)
    val defaultRepo = store.forProfile(DEFAULT_PROFILE_ID)

    expect(animeRepo.addAddon("https://anime.example/manifest.json")) { "anime add failed" }
    expect(liveRepo.addAddon("https://live.example/manifest.json")) { "live add failed" }
    expect(defaultRepo.addAddon("https://default.example/manifest.json")) { "default add failed" }

    expect(animeRepo.userAddons().size == 1) { "anime profile should hold exactly one addon" }
    expect(liveRepo.userAddons().size == 1) { "live profile should hold exactly one addon" }
    expect(defaultRepo.userAddons().size == 1) { "default profile should hold exactly one addon" }

    val animeUrl = animeRepo.userAddons().single().manifestUrl
    val liveUrl = liveRepo.userAddons().single().manifestUrl
    expect(animeUrl != liveUrl) { "profiles resolved to the same addon" }

    expect(animeRepo.addAddon("https://shared.example/manifest.json")) { "shared add failed" }
    expect(liveRepo.addAddon("https://shared.example/manifest.json")) { "shared add failed" }
    expect(animeRepo.userAddons().size == 2) { "anime lost an addon" }
    expect(liveRepo.userAddons().size == 2) { "live lost an addon" }
    expect(defaultRepo.userAddons().size == 1) { "default profile leaked an addon" }

    animeRepo.removeAddonAt(0)
    expect(animeRepo.userAddons().size == 1) { "anime remove failed" }
    expect(liveRepo.userAddons().size == 2) { "removing from anime changed live" }
    expect(defaultRepo.userAddons().size == 1) { "removing from anime changed default" }

    expect(!animeRepo.addAddon("https://shared.example/manifest.json")) {
        "anime should already have that addon"
    }
    expect(liveRepo.userAddons().size == 2) { "a rejected duplicate must not change anything" }

    expect(store.deleteProfile(anime.id)) { "delete failed" }
    expect(store.profiles().none { it.id == anime.id }) { "profile still listed after delete" }
    expect(liveRepo.userAddons().size == 2) { "deleting anime changed live" }
    expect(defaultRepo.userAddons().size == 1) { "deleting anime changed default" }
    expect(!store.deleteProfile(DEFAULT_PROFILE_ID)) { "the default profile must not be deletable" }

    expect(store.renameProfile(live.id, "Sport")) { "rename failed" }
    expect(store.profiles().first { it.id == live.id }.name == "Sport") { "rename did not stick" }
    expect(liveRepo.userAddons().size == 2) { "rename orphaned the addon list" }

    expect(store.createProfile("   ") == null) { "blank profile name was accepted" }
}

fun theDefaultProfileIsRenameable() {
    val store = StremioRepository(FakePrefs())
    expect(store.profiles().single().name == StremioConstants.PROVIDER_NAME) { "unexpected default name" }

    expect(store.renameProfile(DEFAULT_PROFILE_ID, "My Stremio")) { "default profile should be renameable" }
    expect(store.profiles().single().name == "My Stremio") { "default rename did not stick" }

    val second = store.createProfile("Second")!!
    expect(store.renameProfile(DEFAULT_PROFILE_ID, "Second")) { "clashing rename should succeed" }
    val names = store.profiles().map { it.name.lowercase() }
    expect(names.size == names.toSet().size) { "two profiles share a name: $names" }
    expect(store.profiles().first().name != "second") { "clash was not disambiguated" }

    expect(store.addAddon("https://x.example/manifest.json")) { "add failed" }
    store.renameProfile(second.id, "Renamed")
    expect(store.userAddons().size == 1) { "rename orphaned the addon list" }
}

fun profilesSurviveCorruptStorage() {
    val prefs = FakePrefs()
    prefs.putString(StremioConstants.KEY_PROFILES, "{not json")
    val store = StremioRepository(prefs)
    expect(store.profiles().size == 1) { "corrupt profile JSON should fall back to default only" }
    expect(store.profiles().first().id == DEFAULT_PROFILE_ID) { "default profile missing" }
    expect(store.createProfile("Fresh") != null) { "could not create after corruption" }
}

fun liveContentStaysOutOfMovieAndSeries() {
    for (type in listOf("channel", "live", "livestream", "iptv", "sport")) {
        val types = streamTypesFor(type)
        expect(types == listOf(type)) { "'$type' should query only itself, got $types" }
    }
    expect(streamTypesFor("movie") == listOf("movie")) { "movie must not widen" }
    expect(streamTypesFor("series") == listOf("series")) { "series must not widen" }
    expect(streamTypesFor("anime") == listOf("series")) { "anime maps to series" }
    expect(streamTypesFor("tv") == listOf("series")) { "tv is a Stremio synonym for series" }
    val unknown = streamTypesFor("podcast")
    expect(unknown.contains("podcast") && unknown.contains("movie") && unknown.contains("series")) {
        "unknown type should widen, got $unknown"
    }
}

fun freshInstallHasNoPreSeededProfiles() {
    val store = StremioRepository(FakePrefs())
    val profiles = store.profiles()
    expect(profiles.size == 1) { "a fresh install must have exactly one profile, got ${profiles.map { it.name }}" }
    expect(profiles.single().isDefault) { "that profile must be the default" }
    expect(profiles.single().name == StremioConstants.PROVIDER_NAME) { "default profile name wrong" }
    expect(store.userAddons().isEmpty()) { "a fresh install should have no addons" }
    expect(store.addAddon("https://a.example/manifest.json")) { "add failed on default profile" }
    expect(store.userAddons().size == 1) { "default profile could not hold an addon" }
    store.removeAddonAt(0)
    expect(store.userAddons().isEmpty()) { "addons must be removable from the default profile" }
}

fun legacyMigrationDoesNotDeleteLiveKeys() {
    val prefs = FakePrefs()
    prefs.putString("stremio_addon", "https://legacy-one.example/manifest.json")
    prefs.putString("stremio_addon2", "https://legacy-two.example/manifest.json")
    prefs.putString("stremio_saved_links", "[{\"link\":\"https://legacy-three.example/manifest.json\"}]")

    val store = StremioRepository(prefs)
    expect(store.userAddons().size == 3) { "migration should have found 3 legacy addons" }

    expect(!prefs.contains("stremio_addon")) { "legacy key not cleared" }
    expect(!prefs.contains("stremio_addon2")) { "second legacy key not cleared" }
    expect(!prefs.contains("stremio_saved_links")) { "legacy links key not cleared" }

    expect(prefs.contains(StremioConstants.KEY_ADDONS)) { "migration deleted the live key it just wrote" }
    expect(store.userAddons().size == 3) { "migrated addons lost on the second read" }
    expect(store.userAddons().size == 3) { "migrated addons lost on the third read" }

    val other = StremioRepository(prefs)
    val extra = other.createProfile("Other") ?: run { throw AssertionError("createProfile failed") }
    val extraKey = profileAddonsKey(extra.id)
    other.forProfile(extra.id).addAddon("https://profile-only.example/manifest.json")
    expect(prefs.contains(extraKey)) { "profile key missing" }

    prefs.putString("stremio_addon", "https://legacy-four.example/manifest.json")
    StremioRepository(prefs).userAddons()
    expect(prefs.contains(extraKey)) { "a later migration deleted another profile's key" }
    expect(StremioRepository(prefs).forProfile(extra.id).userAddons().size == 1) {
        "the other profile's addon list was emptied"
    }
}

fun runProfileChecks() {
    profileNameIsSanitised()
    profileNamesDoNotCollide()
    profileIdsAreUnique()
    profileStorageIsNamespaced()
    profilesDoNotShareAddonStorage()
    profilesSurviveCorruptStorage()
    legacyMigrationDoesNotDeleteLiveKeys()
    freshInstallHasNoPreSeededProfiles()
    theDefaultProfileIsRenameable()
    timeoutNestingIsCoherent()
    aNinetySecondAddonStillReachesThePlayer()
    liveContentStaysOutOfMovieAndSeries()
}

fun timeoutNestingIsCoherent() {
    expect(REQUEST_TIMEOUT_MS < ADDON_TIMEOUT_MS) {
        "a request must be capped below its addon, or one hang eats the addon's whole budget"
    }
    expect(ADDON_TIMEOUT_MS < STREAM_FANOUT_TIMEOUT_MS) {
        "a per-addon limit at or above the outer limit never fires; the nesting is dead config"
    }
    expect(ADDON_TIMEOUT_MS < CATALOG_FANOUT_TIMEOUT_MS) {
        "same problem on the catalogue path"
    }
    expect(STREAM_FANOUT_TIMEOUT_MS < HOST_LOAD_LINKS_TIMEOUT_MS) {
        "the outer stream limit must stay under the host's ${HOST_LOAD_LINKS_TIMEOUT_MS}ms or the " +
            "host reports the title as unavailable and discards results that already arrived"
    }
    expect(CATALOG_FANOUT_TIMEOUT_MS <= HOST_LOAD_LINKS_TIMEOUT_MS) {
        "the catalogue limit must also stay inside the host budget"
    }
}

fun aNinetySecondAddonStillReachesThePlayer() {
    val slowAddonSeconds = 90L
    val reachedRequest = slowAddonSeconds * 1000 < REQUEST_TIMEOUT_MS
    val reachedAddon = slowAddonSeconds * 1000 < ADDON_TIMEOUT_MS
    val reachedSearch = slowAddonSeconds * 1000 < STREAM_FANOUT_TIMEOUT_MS
    expect(reachedRequest && reachedAddon && reachedSearch) {
        "a ${slowAddonSeconds}s addon is cut before it finishes: request=$reachedRequest " +
            "addon=$reachedAddon search=$reachedSearch"
    }
}
