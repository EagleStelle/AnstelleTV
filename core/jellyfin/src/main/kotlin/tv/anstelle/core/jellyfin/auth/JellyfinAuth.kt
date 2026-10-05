package tv.anstelle.core.jellyfin.auth

import org.jellyfin.sdk.api.client.extensions.authenticateUserByName
import org.jellyfin.sdk.api.client.extensions.authenticateWithQuickConnect
import org.jellyfin.sdk.api.client.extensions.authenticationApi
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.model.api.AuthenticationResult
import tv.anstelle.core.common.AppLog
import tv.anstelle.core.jellyfin.JellyfinClients
import tv.anstelle.core.jellyfin.jellyfinCall
import tv.anstelle.core.jellyfin.normalizeJellyfinId
import tv.anstelle.core.jellyfin.toJellyfinId
import tv.anstelle.core.model.ServerConnectException
import tv.anstelle.core.model.ServerProblem
import javax.inject.Inject
import javax.inject.Singleton

/** A signed-in Jellyfin user. */
data class AuthSession(
    val accessToken: String,
    val userId: String,
    val userName: String,
    val serverId: String?,
)

/** A pending Quick Connect request: the user approves [code] in another Jellyfin client. */
data class QuickConnectCode(val code: String, val secret: String)

/** Password and Quick Connect sign-in. */
@Singleton
class JellyfinAuth @Inject constructor(
    private val clients: JellyfinClients,
) {
    suspend fun signInWithPassword(url: String, username: String, password: String): AuthSession {
        val result = jellyfinCall("Signing in", unauthorized = ServerProblem.BAD_CREDENTIALS) {
            clients.api(url).authenticationApi.authenticateUserByName(username, password).content
        }
        return result.toSession()
    }

    suspend fun isQuickConnectEnabled(url: String): Boolean = jellyfinCall("Checking Quick Connect") {
        clients.api(url).authenticationApi.getQuickConnectEnabled().content
    }

    suspend fun startQuickConnect(url: String): QuickConnectCode {
        val result = jellyfinCall("Starting Quick Connect", unauthorized = ServerProblem.QUICK_CONNECT_DISABLED) {
            clients.api(url).authenticationApi.initiateQuickConnect().content
        }
        return QuickConnectCode(result.code, result.secret)
    }

    /** True once the user approved the code. Throws when the request expired. */
    suspend fun isQuickConnectApproved(url: String, secret: String): Boolean = jellyfinCall("Checking Quick Connect") {
        clients.api(url).authenticationApi.getQuickConnectState(secret).content.authenticated
    }

    suspend fun finishQuickConnect(url: String, secret: String): AuthSession {
        val result = jellyfinCall("Finishing Quick Connect", unauthorized = ServerProblem.BAD_CREDENTIALS) {
            clients.api(url).authenticationApi.authenticateWithQuickConnect(secret).content
        }
        return result.toSession()
    }

    /** Ends the session on the server so the token stops working. Best effort. */
    suspend fun signOut(url: String, accessToken: String) {
        try {
            jellyfinCall("Signing out") { clients.api(url, accessToken).sessionApi.reportSessionEnded() }
        } catch (e: ServerConnectException) {
            AppLog.w(TAG, "Server sign-out failed: ${e.message}")
        }
    }

    private fun AuthenticationResult.toSession(): AuthSession {
        val token = accessToken
        val user = user
        if (token.isNullOrBlank() || user == null) {
            throw ServerConnectException(ServerProblem.SERVER_ERROR, "The server did not return a session")
        }
        return AuthSession(
            accessToken = token,
            userId = user.id.toJellyfinId(),
            userName = user.name.orEmpty(),
            serverId = serverId?.let(::normalizeJellyfinId),
        )
    }

    private companion object {
        const val TAG = "JellyfinAuth"
    }
}
