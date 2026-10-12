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
    /** Whether the LAN web server runs. */
    val WebServerEnabled = SettingKey.BoolKey("web.enabled", true)
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

    /** Whether the profile keeps what it watches for its dashboard; off hides the Dashboard tab. */
    fun dashboard(profileId: Long) = SettingKey.BoolKey("dashboard.$profileId", true)

    /** The channel the profile watched last, for resuming; 0 when none. */
    fun lastChannel(profileId: Long) = SettingKey.LongKey("playback.channel.$profileId", 0L)

    /** The guide tab the profile had open: "all", "favorites" or "group:<id>". */
    fun guideTab(profileId: Long) = SettingKey.StringKey("guide.tab.$profileId", "all")

    /** How many channels, picked at random, the On demand banner cycles through. Shared, like the channels. */
    val OnDemandBanner = SettingKey.IntKey("ondemand.banner", 10)

    /** The choices for [OnDemandBanner]. */
    val OnDemandBannerSizes = listOf(5, 10, 15, 20, 25, 30)

    /**
     * The server's parental rating table (`/Localization/ParentalRatings`), one "score<TAB>name"
     * per line, kept at every sync for the parental rating choices.
     */
    fun ratingTable(serverId: Long) = SettingKey.StringKey("ratings.table.$serverId", "")

    /** The group the last channel someone built went into, which the next one starts with; 0 for none yet. */
    val NewChannelGroup = SettingKey.LongKey("channels.new.group", 0L)
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
