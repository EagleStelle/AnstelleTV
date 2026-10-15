package tv.anstelle.core.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import tv.anstelle.core.database.dao.SettingDao
import tv.anstelle.core.database.entity.SettingEntity
import javax.inject.Inject
import javax.inject.Singleton

/** A typed key in the `setting` table. */
sealed class SettingKey<T>(val name: String, val default: T) {
    abstract fun decode(raw: String): T?
    abstract fun encode(value: T): String

    class BoolKey(name: String, default: Boolean) : SettingKey<Boolean>(name, default) {
        override fun decode(raw: String) = raw.toBooleanStrictOrNull()
        override fun encode(value: Boolean) = value.toString()
    }

    class IntKey(name: String, default: Int) : SettingKey<Int>(name, default) {
        override fun decode(raw: String) = raw.toIntOrNull()
        override fun encode(value: Int) = value.toString()
    }

    class LongKey(name: String, default: Long) : SettingKey<Long>(name, default) {
        override fun decode(raw: String) = raw.toLongOrNull()
        override fun encode(value: Long) = value.toString()
    }

    class StringKey(name: String, default: String) : SettingKey<String>(name, default) {
        override fun decode(raw: String) = raw
        override fun encode(value: String) = value
    }
}

object Settings {
    /** Whether the LAN web server runs; off until turned on in Web control. */
    val WebServerEnabled = SettingKey.BoolKey("web.enabled", false)
    val WebServerPort = SettingKey.IntKey("web.port", 8765)

    /** Profile shown after launch; 0 means none chosen yet. */
    val ActiveProfileId = SettingKey.LongKey("profile.active", 0L)

    /**
     * A [tv.anstelle.core.model.WatchSync] code: what the profile's channel airings leave in
     * Jellyfin. By default a program watched through is marked watched, and progress is left
     * to On demand.
     */
    fun liveTvSync(profileId: Long) = SettingKey.IntKey("sync.live.$profileId", tv.anstelle.core.model.WatchSync.STATUS.code)

    /** A [tv.anstelle.core.model.WatchSync] code: what the profile's on-demand playback leaves in Jellyfin, by default what its own apps do. */
    fun onDemandSync(profileId: Long) = SettingKey.IntKey("sync.ondemand.$profileId", tv.anstelle.core.model.WatchSync.BOTH.code)

    /**
     * The profile's audio languages in order of preference, as ISO 639-2/T codes joined by
     * commas ([tv.anstelle.core.model.Languages.encodeList]); empty plays the item's default track.
     */
    fun audioLanguages(profileId: Long) = SettingKey.StringKey("playback.audio.$profileId", "")

    /** The profile's subtitle languages in order of preference, stored like [audioLanguages]. */
    fun subtitleLanguages(profileId: Long) = SettingKey.StringKey("playback.subtitles.$profileId", "")

    /** A [tv.anstelle.core.model.SubtitleMode] code: when the profile's subtitles show. */
    fun subtitleMode(profileId: Long) = SettingKey.IntKey("playback.subtitles.mode.$profileId", tv.anstelle.core.model.SubtitleMode.OFF.code)

    /** A [tv.anstelle.core.model.SegmentAction] code: what On demand does while a [type] segment plays. */
    fun segmentAction(profileId: Long, type: tv.anstelle.core.model.SegmentType) =
        SettingKey.IntKey("playback.skip.${type.key}.$profileId", tv.anstelle.core.model.SegmentAction.ASK_TO_SKIP.code)

    /** Whether the profile keeps what it watches for its dashboard; off hides the Dashboard tab. */
    fun dashboard(profileId: Long) = SettingKey.BoolKey("dashboard.$profileId", true)

    /** The channel the profile watched last, for resuming; 0 when none. */
    fun lastChannel(profileId: Long) = SettingKey.LongKey("playback.channel.$profileId", 0L)

    /** The guide tab the profile had open: "all", "favorites" or "group:<id>". */
    fun guideTab(profileId: Long) = SettingKey.StringKey("guide.tab.$profileId", "all")

    /** How many channels, picked at random, the On demand banner cycles through. Shared, like the channels. */
    val OnDemandBanner = SettingKey.IntKey("ondemand.banner", 10)

    /** The choices for [OnDemandBanner] and [catalogBanner]. */
    val OnDemandBannerSizes = listOf(5, 10, 15, 20, 25, 30)

    /** How many titles of [kind], picked at random, the banner on its On Demand tab cycles through. Shared. */
    fun catalogBanner(kind: tv.anstelle.core.data.ondemand.CatalogKind) = SettingKey.IntKey("ondemand.banner.${kind.key}", 10)

    /**
     * The order of the rows on [kind]'s On Demand tab, as their keys joined by commas
     * ([tv.anstelle.core.data.ondemand.ShelfOrder]); empty keeps the default order. Shared.
     */
    fun catalogShelves(kind: tv.anstelle.core.data.ondemand.CatalogKind) = SettingKey.StringKey("ondemand.rows.${kind.key}", "")

    /**
     * The server's parental rating table (`/Localization/ParentalRatings`), one "score<TAB>name"
     * per line, kept at every sync for the parental rating choices.
     */
    fun ratingTable(serverId: Long) = SettingKey.StringKey("ratings.table.$serverId", "")

    /** The group the last channel someone built went into, which the next one starts with; 0 for none yet. */
    val NewChannelGroup = SettingKey.LongKey("channels.new.group", 0L)

    /** When this device's backup was last taken (saved to a file, by a browser or by another device); 0 for never. */
    val BackupSaved = SettingKey.LongKey("backup.saved", 0L)

    /** When the app installed here was last updated as the built-in themes were last copied from it; 0 for never. */
    val ThemesSeeded = SettingKey.LongKey("themes.seeded", 0L)

    /** The app install and time zone the last full schedule run was made for (`ScheduleWorker`); empty for none. */
    val SchedulesMadeFor = SettingKey.StringKey("schedules.made", "")

    /** Measured file runtimes, retained while the source and its reported runtime stay the same. */
    fun fileDurations(serverId: Long) = SettingKey.StringKey("media.durations.$serverId", "")
    fun fileDurationAttempts(serverId: Long) = SettingKey.StringKey("media.durationAttempts.$serverId", "")

    /** This device's own settings: a backup leaves them out and a restore keeps them. */
    val DeviceOnly = listOf(WebServerEnabled.name, WebServerPort.name, ActiveProfileId.name, BackupSaved.name, ThemesSeeded.name, SchedulesMadeFor.name)
}

@Singleton
class SettingsRepository @Inject constructor(
    private val dao: SettingDao,
) {
    fun <T> observe(key: SettingKey<T>): Flow<T> =
        dao.observe(key.name)
            .map { raw -> raw?.let(key::decode) ?: key.default }
            .distinctUntilChanged()

    suspend fun <T> get(key: SettingKey<T>): T = dao.get(key.name)?.let(key::decode) ?: key.default

    suspend fun <T> set(key: SettingKey<T>, value: T) {
        dao.put(SettingEntity(key.name, key.encode(value)))
    }
}
