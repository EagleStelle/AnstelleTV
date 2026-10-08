package tv.anstelle.feature.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import tv.anstelle.core.data.ProfileRepository

/** The active profile's id, read once: switching profiles rebuilds every screen anyway. */
internal fun ProfileRepository.activeProfileId(): Flow<Long> = flow { emit(ensureActiveProfile().id) }
