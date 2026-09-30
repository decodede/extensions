package com.stremio

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.plugins.Plugin
import java.util.concurrent.ConcurrentHashMap

object StremioProviderRegistry {

    private val live = ConcurrentHashMap<String, StremioProvider>()

    @Volatile private var plugin: Plugin? = null

    fun bindPlugin(plugin: Plugin) {
        this.plugin = plugin
    }

    fun attach(store: StremioRepository) {
        live.keys.toList().forEach(::unregister)
        store.profiles().forEach { registerProfile(store.forProfile(it.id), it.name) }
    }

    fun defaultName(store: StremioRepository): String =
        store.profiles().firstOrNull { it.id == DEFAULT_PROFILE_ID }?.name
            ?: StremioConstants.PROVIDER_NAME

    fun nameOf(store: StremioRepository, profileId: String): String =
        store.profiles().firstOrNull { it.id == profileId }?.name ?: StremioConstants.PROVIDER_NAME

    fun registerProfile(repo: StremioRepository, name: String) {
        val id = repo.profileId
        live.remove(id)?.let(::detach)
        val provider = StremioProvider(repo, providerLabel(name))
        live[id] = provider
        plugin?.registerMainAPI(provider)
        refresh()
    }

    fun refreshName(store: StremioRepository, name: String) = registerProfile(store, name)

    fun unregister(id: String) {
        live.remove(id)?.let(::detach)
        refresh()
    }

    private fun providerLabel(name: String): String =
        if (name.equals(StremioConstants.PROVIDER_NAME, ignoreCase = true)) name else "$name · Stremio"

    private fun refresh() {
        runCatching { MainActivity.reloadHomeEvent.invoke(true) }
    }

    private fun detach(provider: MainAPI) {
        runCatching { APIHolder.allProviders.remove(provider) }
        runCatching { APIHolder.removePluginMapping(provider) }
    }
}
