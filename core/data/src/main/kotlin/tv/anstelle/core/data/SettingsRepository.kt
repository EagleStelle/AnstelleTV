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
}

object Settings {
    /** Whether the LAN web server runs. */
    val WebServerEnabled = SettingKey.BoolKey("web.enabled", true)
    val WebServerPort = SettingKey.IntKey("web.port", 8765)

    /** Profile shown after launch; 0 means none chosen yet. */
    val ActiveProfileId = SettingKey.LongKey("profile.active", 0L)
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
