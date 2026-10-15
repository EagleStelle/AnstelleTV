package tv.anstelle.core.data

import tv.anstelle.core.model.ProfileRole

data class RemovalAdmin(val id: Long, val name: String)

/** A profile survives removal when it has a valid account on another signed-in server. */
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
) {
    fun validate(actorId: Long, transferOwnerTo: Long?) {
        if ((requiresTransfer || resetsApp) && ownerId != actorId) {
            throw ServerRemovalException("owner_only", "Only the owner can remove this server.")
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

internal fun serverRemovalPlan(serverId: Long, signedInServers: Set<Long>, profiles: List<RemovalProfile>): ServerRemovalPlan {
    val remainingServers = signedInServers - serverId
    val survivors = profiles.filter { (it.signedInServers intersect remainingServers).isNotEmpty() }
    val removed = profiles.filter { serverId in it.accountServers && it !in survivors }.mapTo(HashSet()) { it.id }
    val owner = profiles.firstOrNull { it.role == ProfileRole.OWNER }
    val reset = remainingServers.isEmpty() && survivors.isEmpty()
    return ServerRemovalPlan(
        resetsApp = reset,
        ownerId = owner?.id,
        requiresTransfer = !reset && owner != null && owner.id in removed,
        admins = survivors.filter { it.role == ProfileRole.ADMIN }.map { RemovalAdmin(it.id, it.name) },
        deletedProfileIds = if (reset) profiles.mapTo(HashSet()) { it.id } else removed,
    )
}
