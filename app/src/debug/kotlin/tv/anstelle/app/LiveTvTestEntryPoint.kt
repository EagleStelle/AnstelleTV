package tv.anstelle.app

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import tv.anstelle.core.data.ProfileRepository
import tv.anstelle.core.data.channels.ChannelRepository
import tv.anstelle.core.data.playback.ChannelPlayer

/** Playback device checks only; absent from releases. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface LiveTvTestEntryPoint {
    fun playback(): ChannelPlayer
    fun profiles(): ProfileRepository
    fun channelRepository(): ChannelRepository
}
