package tv.anstelle.webserver.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tv.anstelle.core.common.AppLog
import tv.anstelle.core.common.ApplicationScope
import tv.anstelle.core.data.backup.BackupFile
import tv.anstelle.core.data.backup.BackupRepository
import tv.anstelle.core.data.backup.replaceExplanation
import tv.anstelle.core.data.backup.summary
import javax.inject.Inject

/**
 * Backups, for the web UI and for another AnstelleTV copying this one. A file that can't be
 * restored throws [tv.anstelle.core.data.backup.BackupException] saying why.
 */
interface BackupApi {
    /** What a backup of this device holds; [canRestore] is whether the one asking may restore. */
    suspend fun summary(canRestore: Boolean): BackupDto

    /** This device's backup: its file name and JSON. */
    suspend fun export(): BackupFile

    /** The question a restore of [text] asks first. */
    suspend fun check(text: String): BackupCheckDto

    /** Starts replacing everything with [text]; the app then starts over on it. */
    suspend fun restore(text: String)
}

internal class RoomBackupApi @Inject constructor(
    private val backups: BackupRepository,
    @param:ApplicationScope private val scope: CoroutineScope,
) : BackupApi {
    override suspend fun summary(canRestore: Boolean): BackupDto {
        val summary = backups.observeSummary().first()
        return BackupDto(
            profiles = summary.profiles,
            channels = summary.channels,
            groups = summary.groups,
            themes = summary.themes,
            servers = summary.servers,
            historyDays = summary.historyDays,
            savedUtcMs = summary.savedUtcMs,
            canRestore = canRestore,
        )
    }

    override suspend fun export(): BackupFile = backups.export()

    override suspend fun check(text: String): BackupCheckDto {
        val backup = backups.parse(text)
        return BackupCheckDto("Replace everything here?", backup.summary().replaceExplanation("the file"))
    }

    override suspend fun restore(text: String) {
        val backup = backups.parse(text)
        // The answer goes out first: the restore ends with the app starting over.
        scope.launch {
            try {
                backups.restore(backup)
            } catch (e: Exception) {
                AppLog.e(TAG, "Restore from a browser failed", e)
            }
        }
    }

    private companion object {
        const val TAG = "WebServer"
    }
}
