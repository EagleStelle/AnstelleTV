package tv.anstelle.core.data

import tv.anstelle.core.model.ProfileRole
import tv.anstelle.core.model.ServerRights

data class RemovalAdmin(val id: Long, val name: String)

internal data class RemovalServer(val id: Long, val name: String, val ownerProfileId: Long?, val signedIn: Boolean)

/** A linked profile is deleted in full, even when it has an account on another server. */
internal data class RemovalProfile(
    val id: Long,
    val name: String,
    val role: ProfileRole,
    val accountServers: Set<Long>,
    val signedInServers: Set<Long>,
)

data class ServerRemovalPlan(
    val resetsApp: Boolean,
    val ownerId: Long?,
    val requiresTransfer: Boolean,
    val admins: List<RemovalAdmin>,
    val deletedProfileIds: Set<Long>,
    val deletedServerIds: Set<Long>,
    val allowedActorIds: Set<Long>,
    /** Shared by TV and web so both confirmations describe the same committed deletion. */
    val explanation: String,
) {
    fun canRemove(actorId: Long): Boolean = actorId in allowedActorIds

    fun validate(actorId: Long, transferOwnerTo: Long?) {
        if (!canRemove(actorId)) {
            throw ServerRemovalException("owner_only", "Admins can remove only their own servers. Only the owner can remove other servers or reset the app.")
        }
        if (requiresTransfer && transferOwnerTo == null) {
            throw ServerRemovalException("owner_transfer_required", if (admins.isEmpty()) {
                "Transfer ownership before removing this server. Sign in an admin on another server first."
            } else {
                "Choose another signed-in admin to become the owner before removing this server."
            })
        }
        if (transferOwnerTo != null && (!requiresTransfer || admins.none { it.id == transferOwnerTo })) {
            throw ServerRemovalException("invalid_owner", "Choose a signed-in admin who will remain after this server is removed.")
        }
    }
}

class ServerRemovalException(val code: String, message: String) : IllegalStateException(message)

internal fun serverRemovalPlan(serverId: Long, servers: List<RemovalServer>, profiles: List<RemovalProfile>): ServerRemovalPlan {
    val removedServers = hashSetOf(serverId)
    val removedProfiles = hashSetOf<Long>()
    // A deleted profile cannot leave another connection without its owning profile.
    do {
        val previousSize = removedServers.size
        profiles.filter { profile ->
            profile.accountServers.any { it in removedServers } ||
                servers.any { it.id in removedServers && it.ownerProfileId == profile.id }
        }.mapTo(removedProfiles) { it.id }
        servers.filter { it.ownerProfileId in removedProfiles }.mapTo(removedServers) { it.id }
    } while (removedServers.size != previousSize)
    val owner = profiles.firstOrNull { it.role == ProfileRole.OWNER }
    val remainingServers = servers.filter { it.signedIn && it.id !in removedServers }.mapTo(HashSet()) { it.id }
    val reset = remainingServers.isEmpty()
    if (reset) {
        servers.mapTo(removedServers) { it.id }
        profiles.mapTo(removedProfiles) { it.id }
    }
    val transfer = !reset && owner != null && owner.id in removedProfiles
    val admins = profiles.filter { it.id !in removedProfiles && it.role == ProfileRole.ADMIN && it.signedInServers.any { it in remainingServers } }
    val allowed = profiles.filter { actor ->
        actor.role == ProfileRole.OWNER || (actor.role == ProfileRole.ADMIN && !reset && !transfer &&
            servers.filter { it.id in removedServers }.all { ServerRights.canRemove(actor.id, owner?.id, it.ownerProfileId) })
    }.mapTo(HashSet()) { it.id }
    val names = profiles.filter { it.id in removedProfiles }.joinToString { it.name }
    val explanation = if (reset) {
        "Deletes all servers, profiles, local history, channels and settings, including signed-out entries. Returns to first-time setup."
    } else buildString {
        append("Permanently deletes the linked profiles")
        if (names.isNotEmpty()) append(": $names")
        append(", including their local history, settings, paired browsers and sign-ins on every server. ")
        append("Removes these server connections and everything synced from them: ")
        append(servers.filter { it.id in removedServers }.joinToString { it.name })
        append(". Jellyfin users and media are kept.")
        if (transfer) append(" The selected admin becomes the owner.")
    }
    return ServerRemovalPlan(reset, owner?.id, transfer, admins.map { RemovalAdmin(it.id, it.name) },
        removedProfiles, removedServers, allowed, explanation)
}
