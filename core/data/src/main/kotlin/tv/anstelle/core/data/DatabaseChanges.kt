package tv.anstelle.core.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn
import tv.anstelle.core.common.ApplicationScope
import tv.anstelle.core.database.AnstelleDatabase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Table-level change notifications from Room. The web server forwards these to browsers
 * over `/ws` so the web UI refetches exactly what changed; the TV UI observes Room Flows
 * directly. Room batches invalidations per transaction, so a big sync is one event.
 */
@Singleton
class DatabaseChanges @Inject constructor(
    database: AnstelleDatabase,
    @ApplicationScope scope: CoroutineScope,
) {
    val tables: SharedFlow<Set<String>> = database.invalidationTracker
        .createFlow(*AnstelleDatabase.OBSERVED_TABLES, emitInitialState = false)
        .shareIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS))

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
