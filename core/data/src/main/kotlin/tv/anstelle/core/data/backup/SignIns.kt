package tv.anstelle.core.data.backup

/**
 * Which Jellyfin sign-ins a restore keeps. A backup never carries tokens, so a server or profile
 * sign-in only survives where this device is already signed in the same way: the same server, or
 * the same profile sign-in (a restore on the device that made the backup). Everything else this
 * device was signed in to is signed out.
 */
object SignIns {
    /** A server this device knows, and whether it holds a token for it. */
    data class Server(
        val jellyfinServerId: String,
        val baseUrl: String,
        val userId: String?,
        val userName: String?,
        val signedIn: Boolean,
    )

    /** A profile's own sign-in on a server, by the device it speaks as. */
    data class Account(val jellyfinServerId: String, val device: String, val baseUrl: String, val signedIn: Boolean)

    class Plan(
        /** Servers whose sign-in stays, by Jellyfin server id: the restored server takes this device's user and address. */
        val kept: Map<String, Server>,
        /** Server sign-ins to sign out and forget. */
        val dropServers: List<Server>,
        /** Profile sign-ins to sign out and forget. */
        val dropAccounts: List<Account>,
    )

    /**
     * [backupServers] are the restored servers' Jellyfin ids; [backupAccounts] the restored profile
     * sign-ins as (Jellyfin server id, device).
     */
    fun plan(
        servers: List<Server>,
        accounts: List<Account>,
        backupServers: Set<String>,
        backupAccounts: Set<Pair<String, String>>,
    ): Plan {
        val signedIn = servers.filter { it.signedIn }
        val kept = signedIn.filter { it.jellyfinServerId in backupServers }.associateBy { it.jellyfinServerId }
        return Plan(
            kept = kept,
            dropServers = signedIn.filter { it.jellyfinServerId !in kept },
            dropAccounts = accounts.filter { it.signedIn && (it.jellyfinServerId to it.device) !in backupAccounts },
        )
    }
}
