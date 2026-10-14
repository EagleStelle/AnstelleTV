package tv.anstelle.feature.ondemand

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tv.anstelle.core.data.ondemand.OnDemandRepository

internal data class WatchEdit(val saving: Boolean = false, val error: String? = null)

/** One in-flight edit per detail page. Server failure never pretends that a mark was saved. */
class WatchedEditor internal constructor(private val repository: OnDemandRepository, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(WatchEdit())
    internal val state = _state.asStateFlow()
    internal var revision = 0
        private set

    internal fun setWatched(itemId: Long, profileId: Long, watched: Boolean, refresh: () -> Unit, saved: (Set<Long>) -> Unit) {
        if (_state.value.saving) return
        revision++
        _state.value = WatchEdit(saving = true)
        scope.launch {
            try {
                saved(repository.setWatched(itemId, profileId, watched))
                revision++
                _state.value = WatchEdit()
            } catch (e: CancellationException) {
                revision++
                _state.value = WatchEdit()
                throw e
            } catch (e: Exception) {
                revision++
                _state.value = WatchEdit(error = if (e is IllegalStateException) e.message else "Couldn't update watched status. Check your connection and try again.")
                refresh()
            }
        }
    }
}
