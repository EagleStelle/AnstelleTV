package tv.anstelle.core.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import tv.anstelle.core.common.AnstelleDispatchers
import tv.anstelle.core.common.AppLog
import tv.anstelle.core.common.Dispatcher
import tv.anstelle.core.common.TimeSource
import tv.anstelle.core.database.dao.ProfileDao
import tv.anstelle.core.database.dao.ThemeDao
import tv.anstelle.core.database.entity.ThemeEntity
import tv.anstelle.core.model.ThemeSpec
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A theme as the theme list shows it. */
data class ThemeSummary(val id: Long, val spec: ThemeSpec, val isBuiltIn: Boolean)

/** A theme file that couldn't be read or doesn't make a theme. */
class ThemeImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Themes: the built-in ones from core/theme's `assets/themes`, copied into the `theme` table
 * at start, and the ones people make or import. Each profile picks one; none picks the default.
 * Built-in themes can't change; editing one edits a copy.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ThemeRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: ThemeDao,
    private val profileDao: ProfileDao,
    private val profiles: ProfileRepository,
    private val time: TimeSource,
    @Dispatcher(AnstelleDispatchers.IO) private val io: CoroutineDispatcher,
) {
    private val seedLock = Mutex()
    private var seeded = false

    fun observeThemes(): Flow<List<ThemeSummary>> = dao.observeAll().map { list -> list.mapNotNull { it.toSummary() } }

    fun observeTheme(themeId: Long): Flow<ThemeSummary?> = dao.observe(themeId).map { it?.toSummary() }

    /** The active profile's theme; null for the default. */
    fun observeActiveTheme(): Flow<ThemeSpec?> =
        profiles.observeActiveProfile().map { it.themeId }.distinctUntilChanged().flatMapLatest { id ->
            if (id == null) flowOf(null) else dao.observe(id).map { it?.toSummary()?.spec }
        }.distinctUntilChanged()

    /** Copies the shipped themes into the table, once per process, keeping their ids. */
    suspend fun seedBuiltIns() = seedLock.withLock {
        if (seeded) return@withLock
        withContext(io) {
            val shipped = context.assets.list(ASSET_DIR).orEmpty().filter { it.endsWith(".json") }.mapNotNull { file ->
                try {
                    val text = context.assets.open("$ASSET_DIR/$file").bufferedReader().use { it.readText() }
                    ThemeSpec.parse(text).also { it.validate() }.let { it to text }
                } catch (e: Exception) {
                    AppLog.e(TAG, "Bundled theme $file is unreadable", e)
                    null
                }
            }
            val keys = shipped.mapTo(HashSet()) { it.first.key }
            for ((spec, text) in shipped) {
                val existing = dao.byBuiltInKey(spec.key)
                if (existing?.json == text && existing.name == spec.name) continue
                dao.upsert(ThemeEntity(id = existing?.id ?: 0, name = spec.name, json = text, isBuiltIn = true, builtInKey = spec.key, updatedAtUtcMs = time.nowUtcMs()))
            }
            // A theme no longer shipped goes; its profiles fall back to the default.
            for (gone in dao.builtIns().filter { it.builtInKey !in keys }) {
                profileDao.clearTheme(gone.id)
                dao.delete(gone.id)
            }
        }
        seeded = true
    }

    suspend fun use(profileId: Long, themeId: Long?) = profiles.setTheme(profileId, themeId)

    /** A new theme of the user's own, starting as a copy of [themeId]. */
    suspend fun duplicate(themeId: Long): Long = withContext(io) {
        val source = dao.byId(themeId)?.toSummary() ?: throw ThemeImportException("That theme is gone")
        insert(source.spec.copy(name = freeName("${source.spec.name} copy")))
    }

    /** Saves an edit of a theme someone made. Built-in themes can't change. */
    suspend fun save(themeId: Long, spec: ThemeSpec) = withContext(io) {
        val existing = dao.byId(themeId) ?: return@withContext
        if (existing.isBuiltIn) return@withContext
        spec.validate()
        val json = spec.toJson()
        if (json == existing.json) return@withContext
        dao.upsert(existing.copy(name = spec.name, json = json, updatedAtUtcMs = time.nowUtcMs()))
    }

    /** Deletes a theme someone made; profiles using it go back to the default. */
    suspend fun delete(themeId: Long) = withContext(io) {
        val existing = dao.byId(themeId) ?: return@withContext
        if (existing.isBuiltIn) return@withContext
        profileDao.clearTheme(themeId)
        dao.delete(themeId)
    }

    /** The theme as a JSON file anyone can import. */
    suspend fun export(themeId: Long): String? = withContext(io) { dao.byId(themeId)?.toSummary()?.spec?.toJson() }

    /**
     * Adds the theme in [json] as one of the user's own and returns its id. A name already
     * taken gets a number. Throws [ThemeImportException] when the file isn't a theme.
     */
    suspend fun import(json: String): Long = withContext(io) {
        if (json.length > MAX_IMPORT_CHARS) throw ThemeImportException("That file is too big to be a theme")
        val spec = try {
            ThemeSpec.parse(json).also { it.validate() }
        } catch (e: SerializationException) {
            throw ThemeImportException("That file isn't an AnstelleTV theme", e)
        } catch (e: IllegalArgumentException) {
            throw ThemeImportException(e.message ?: "That theme has a value it can't have", e)
        }
        insert(spec.copy(name = freeName(spec.name.trim())))
    }

    private suspend fun insert(spec: ThemeSpec): Long {
        // Every theme of the user's own gets a key nobody else has, so it never shadows a built-in one.
        val own = spec.copy(key = "custom-" + UUID.randomUUID().toString().take(8))
        return dao.upsert(ThemeEntity(name = own.name, json = own.toJson(), isBuiltIn = false, builtInKey = null, updatedAtUtcMs = time.nowUtcMs()))
    }

    private suspend fun freeName(wanted: String): String {
        val taken = dao.names().mapTo(HashSet()) { it.lowercase() }
        if (wanted.lowercase() !in taken) return wanted
        return (2..999).asSequence().map { "$wanted $it" }.first { it.lowercase() !in taken }
    }

    private fun ThemeEntity.toSummary(): ThemeSummary? = try {
        ThemeSummary(id, ThemeSpec.parse(json).copy(name = name), isBuiltIn)
    } catch (e: Exception) {
        AppLog.w(TAG, "Theme $id is unreadable: ${e.message}")
        null
    }

    companion object {
        /** The built-in theme a profile wears until it picks another. */
        const val DEFAULT_KEY = "rose"
        private const val TAG = "Themes"
        private const val ASSET_DIR = "themes"
        private const val MAX_IMPORT_CHARS = 64 * 1024
    }
}
