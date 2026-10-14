package tv.anstelle.webserver.pairing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import tv.anstelle.core.common.ApplicationScope
import tv.anstelle.core.data.ProfileRepository
import tv.anstelle.core.model.ProfileRights
import tv.anstelle.core.model.ProfileRole
import javax.inject.Inject
import javax.inject.Singleton

/** A profile that may manage the app, and so pair browsers and use the web UI. */
data class Manager(val id: Long, val name: String, val role: ProfileRole)

/**
 * Who may pair and who a pairing speaks for. Only the owner and admins manage the app, so only
 * they approve a pairing, and a pairing counts only while its profile still is one of them.
 */
interface PairingGate {
    /** The profile approving a pairing now: the active one when it is the owner or an admin, else null. */
    suspend fun approver(): Long?

    /** The owner and admins by profile id, cached and kept current from the first request on. */
    suspend fun managers(): Map<Long, Manager>

    /** The same, each time it changes, so open sockets can check their pairing again. */
    fun observeManagers(): Flow<Map<Long, Manager>>
}

@Singleton
internal class ProfilePairingGate @Inject constructor(
    private val profiles: ProfileRepository,
    @param:ApplicationScope scope: CoroutineScope,
) : PairingGate {
    /**
     * Null until the profiles are first read, so nothing is refused before they are known. Read
     * from the first request on, so the profiles aren't watched while web control is off.
     */
    private val current: StateFlow<Map<Long, Manager>?> = profiles.observeProfiles()
        .map { list -> list.filter { ProfileRights.canManageApp(it) }.associate { it.id to Manager(it.id, it.name, it.role) } }
        .stateIn(scope, SharingStarted.Lazily, null)

    override suspend fun approver(): Long? {
        if (!profiles.hasProfiles()) return null
        val active = profiles.activeProfile()
        return active.id.takeIf { ProfileRights.canManageApp(active) }
    }

    override suspend fun managers(): Map<Long, Manager> = current.filterNotNull().first()

    override fun observeManagers(): Flow<Map<Long, Manager>> = current.filterNotNull()
}
