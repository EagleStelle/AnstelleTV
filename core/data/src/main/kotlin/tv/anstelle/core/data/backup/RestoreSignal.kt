package tv.anstelle.core.data.backup

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Says a restore is done, so the app starts over on it: every screen and cache built anew. Apart
 * from [BackupRepository], so the activity watching it builds nothing more at launch.
 */
@Singleton
class RestoreSignal @Inject constructor() {
    private val _restored = MutableStateFlow(false)
    val restored: StateFlow<Boolean> = _restored.asStateFlow()

    internal fun restored() {
        _restored.value = true
    }
}
