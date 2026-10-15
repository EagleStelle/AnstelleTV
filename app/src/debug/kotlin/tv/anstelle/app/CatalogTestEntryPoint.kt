package tv.anstelle.app

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import tv.anstelle.core.data.DatabaseChanges
import tv.anstelle.core.data.ProfileRepository
import tv.anstelle.core.data.SettingsRepository
import tv.anstelle.core.data.ondemand.CatalogRepository
import tv.anstelle.core.data.playback.PlaybackReporter
import tv.anstelle.core.database.dao.MediaServerDao
import tv.anstelle.core.jellyfin.auth.JellyfinAuth
import tv.anstelle.core.jellyfin.auth.TokenStore
import tv.anstelle.core.jellyfin.PlaystateFactory
import tv.anstelle.core.jellyfin.JellyfinClients

/** App graph for catalog device tests and opt-in owner-session recovery; absent from releases. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface CatalogTestEntryPoint {
    fun catalog(): CatalogRepository
    fun profiles(): ProfileRepository
    fun settings(): SettingsRepository
    fun reporter(): PlaybackReporter
    fun changes(): DatabaseChanges
    fun servers(): MediaServerDao
    fun auth(): JellyfinAuth
    fun tokens(): TokenStore
    fun playstates(): PlaystateFactory
    fun clients(): JellyfinClients
}
