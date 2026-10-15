package tv.anstelle.app

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import tv.anstelle.core.data.channels.ScheduleUpdater
import tv.anstelle.core.database.dao.ChannelDao
import tv.anstelle.core.database.dao.MediaItemDao
import tv.anstelle.core.database.dao.ScheduleDao
import tv.anstelle.core.jellyfin.sync.FileDurationProbe

/** Device verification of automatic duration repair; absent from release builds. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DurationTestEntryPoint {
    fun updater(): ScheduleUpdater
    fun items(): MediaItemDao
    fun channels(): ChannelDao
    fun schedules(): ScheduleDao
    fun files(): FileDurationProbe
}
