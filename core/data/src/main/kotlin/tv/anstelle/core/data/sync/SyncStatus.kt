package tv.anstelle.core.data.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** What a running sync is doing, for the settings screens. */
data class SyncProgress(val message: String)

/** Live progress of running syncs by server row id. The last result is stored on the server row. */
@Singleton
class SyncStatusTracker @Inject constructor() {
    private val _running = MutableStateFlow<Map<Long, SyncProgress>>(emptyMap())
    val running: StateFlow<Map<Long, SyncProgress>> = _running.asStateFlow()

    fun update(serverId: Long, message: String) {
        _running.update { it + (serverId to SyncProgress(message)) }
    }

    fun finish(serverId: Long) {
        _running.update { it - serverId }
    }
}
