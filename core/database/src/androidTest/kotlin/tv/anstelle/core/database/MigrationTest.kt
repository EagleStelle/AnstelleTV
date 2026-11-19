package tv.anstelle.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/** Each migration from the first public release on, against the exported schemas, keeping the user's rows. */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AnstelleDatabase::class.java,
        listOf(AnstelleDatabase.DropLiveTv()),
    )

    @Test
    fun liveTvTablesGoAndHistoryStays() {
        helper.createDatabase(FILE, 14).use { db ->
            db.execSQL("INSERT INTO profile (id, name, role, sortOrder, createdAtUtcMs) VALUES (1, 'Owner', 2, 0, 0)")
            db.execSQL(
                "INSERT INTO watch_event (profileId, source, liveChannelId, title, mediaType, startUtcMs, durationMs, localDay, completed) " +
                    "VALUES (1, 0, NULL, 'Pilot', 1, 1000, 60000, 20000, 1)",
            )
        }
        helper.runMigrationsAndValidate(FILE, 15, true).use { db ->
            db.query("SELECT title, durationMs FROM watch_event").use {
                it.moveToFirst()
                assertEquals("Pilot", it.getString(0))
                assertEquals(60_000L, it.getLong(1))
            }
            db.query("SELECT name FROM sqlite_master WHERE name LIKE 'live_%'").use { assertEquals(0, it.count) }
        }
    }

    private companion object {
        const val FILE = "migration-test.db"
    }
}
