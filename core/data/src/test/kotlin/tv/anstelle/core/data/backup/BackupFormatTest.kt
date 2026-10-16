package tv.anstelle.core.data.backup

import tv.anstelle.core.model.ChannelKind
import tv.anstelle.core.model.MediaType
import tv.anstelle.core.model.PlaybackOrder
import tv.anstelle.core.model.ProfileRole
import tv.anstelle.core.model.RuleField
import tv.anstelle.core.model.RuleOp
import tv.anstelle.core.model.WatchSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupFormatTest {
    private val channel = BackupChannel(
        id = 7, number = 5001, name = "Saturday Morning", kind = ChannelKind.GENERATED.code, seed = 42,
        seriesOrder = PlaybackOrder.EPISODE_ORDER.code, seriesBlock = 1, movieOrder = PlaybackOrder.SHUFFLE.code, movieBlock = 1,
        moviesOncePerDay = true, hidden = false, favorite = true, groupId = 3, createdAtUtcMs = 1, updatedAtUtcMs = 2,
        rules = listOf(BackupRule(logicGroup = 0, field = RuleField.GENRE.code, op = RuleOp.ANY_OF.code, value = "Animation", weight = 1f)),
    )

    private val backup = Backup(
        createdAtUtcMs = 1_791_000_000_000,
        app = "0.1.0",
        device = "Test TV",
        servers = listOf(BackupServer(1, "jf-1", "Home", "http://192.168.1.20:8096", "u1", "Alex", "12.1.0", 0, listOf(BackupLibrary("lib-1", "Shows", "tvshows", enabled = true)))),
        profiles = listOf(
            BackupProfile(1, "Alex", ProfileRole.OWNER.code, sortOrder = 0, createdAtUtcMs = 0, builtInTheme = "rose", accounts = listOf(BackupAccount(1, "u1", "Alex", "profile-1"))),
            BackupProfile(2, "Sam", ProfileRole.MEMBER.code, maxRatingScore = 12, pinHash = "pbkdf2$20000\$salt\$hash", sortOrder = 1, createdAtUtcMs = 0),
        ),
        groups = listOf(BackupGroup(3, "Mine", 0, 5000)),
        channels = listOf(channel),
        settings = mapOf("playback.audio.1" to "jpn,eng", "dashboard.2" to "false"),
        history = listOf(
            BackupView(1, WatchSource.CHANNEL.code, channelId = 7, serverId = 1, item = "item-9", title = "Pilot", showTitle = "Show", mediaType = MediaType.EPISODE.code, startUtcMs = 10, durationMs = 60_000, localDay = 20_000, completed = true),
        ),
    )

    @Test
    fun `a backup reads back as it was written`() {
        val text = BackupFormat.encode(backup)
        assertTrue(text.contains("\"format\":\"anstelletv-backup\""))
        assertTrue(text.contains("\"version\":1"))
        assertEquals(backup, BackupFormat.decode(text))
    }

    @Test
    fun `server ownership round trips and cannot reference a missing profile`() {
        val owned = backup.copy(servers = backup.servers.map { it.copy(ownerProfileId = 1) })
        assertEquals(owned, BackupFormat.decode(BackupFormat.encode(owned)))
        assertNull(BackupFormat.decode(BackupFormat.encode(backup)).servers.single().ownerProfileId)
        assertEquals(BackupFormat.DAMAGED, backup.copy(servers = backup.servers.map { it.copy(ownerProfileId = 99) }).problem())
    }

    @Test
    fun `other files are refused`() {
        assertEquals(BackupFormat.NOT_A_BACKUP, assertFailsWith<BackupException> { BackupFormat.decode("{\"key\":\"rose\",\"colors\":{}}") }.message)
        assertEquals(BackupFormat.NOT_A_BACKUP, assertFailsWith<BackupException> { BackupFormat.decode("not json") }.message)
        assertEquals(BackupFormat.NOT_A_BACKUP, backup.copy(format = "something-else").problem())
    }

    @Test
    fun `a newer backup waits for a newer app`() {
        assertEquals(BackupFormat.NEWER, backup.copy(version = Backup.VERSION + 1).problem())
        // Fields a newer app adds within a version are skipped.
        val withExtra = BackupFormat.encode(backup).replaceFirst("{", "{\"later\":true,")
        assertEquals(backup, BackupFormat.decode(withExtra))
    }

    @Test
    fun `references must hold`() {
        assertNull(backup.problem())
        // Exactly one owner.
        assertEquals(BackupFormat.DAMAGED, backup.copy(profiles = backup.profiles.map { it.copy(role = ProfileRole.OWNER.code) }).problem())
        assertEquals(BackupFormat.DAMAGED, backup.copy(profiles = backup.profiles.drop(1)).problem())
        // A channel in a group that isn't there, a theme that isn't there, history of nobody.
        assertEquals(BackupFormat.DAMAGED, backup.copy(channels = listOf(channel.copy(groupId = 99))).problem())
        assertEquals(BackupFormat.DAMAGED, backup.copy(profiles = backup.profiles.map { it.copy(customTheme = 5) }).problem())
        assertEquals(BackupFormat.DAMAGED, backup.copy(history = backup.history.map { it.copy(profileId = 9) }).problem())
        // A code this version doesn't know.
        assertEquals(BackupFormat.DAMAGED, backup.copy(channels = listOf(channel.copy(kind = 99))).problem())
        // A rule tied to a season its channel doesn't have.
        assertEquals(BackupFormat.DAMAGED, backup.copy(channels = listOf(channel.copy(rules = channel.rules.map { it.copy(seasonId = 4) }))).problem())
    }

    @Test
    fun `the summary counts what comes in`() {
        val summary = backup.summary()
        assertEquals(BackupSummary(profiles = 2, channels = 1, customChannels = 1, groups = 1, themes = 0, servers = 1, historyDays = 1), summary)
    }

    @Test
    fun `the restore question says what comes in`() {
        assertEquals("2 profiles, 1 channel and 1 day of history", backup.summary().brings())
        assertEquals("1 profile", BackupSummary(profiles = 1).brings())
        assertEquals(
            "2 profiles, 1 channel and 1 day of history from the file replace what this device holds. " +
                "Servers this device isn't signed in to need signing in again.",
            backup.summary().replaceExplanation("the file"),
        )
    }
}
