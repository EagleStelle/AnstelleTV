package tv.anstelle.webserver.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import tv.anstelle.core.common.TimeSource
import tv.anstelle.core.data.QuickConnectState
import tv.anstelle.core.data.ServerCandidate
import tv.anstelle.core.data.sync.userMessage
import tv.anstelle.core.model.ServerConnectException
import tv.anstelle.core.model.ServerProblem
import tv.anstelle.webserver.pairing.Tokens
import java.util.concurrent.ConcurrentHashMap

/**
 * Adding a server from a browser, which takes several requests: the address finds the server,
 * then a password or Quick Connect signs in to it. Each server found is kept under a random id
 * for [TTL_MS], at most [MAX_PENDING] at a time; Quick Connect waits for approval in the
 * background for at most [QUICK_CONNECT_MS] while the browser asks how it stands.
 */
class ServerSignIns(
    private val find: suspend (address: String) -> ServerCandidate,
    private val password: suspend (ServerCandidate, username: String, password: String) -> QuickConnectState.SignedIn,
    private val quickConnect: (ServerCandidate) -> Flow<QuickConnectState>,
    private val time: TimeSource,
    private val scope: CoroutineScope,
) {
    private class Pending(val candidate: ServerCandidate, val foundAtUtcMs: Long) {
        @Volatile var state = SignInStateDto(IDLE)
        var job: Job? = null
    }

    private val pending = ConcurrentHashMap<String, Pending>()
    private val lock = Mutex()

    /** Finds the server behind [address]; throws [ServerConnectException] when there is none. */
    suspend fun find(address: String): FoundServerDto {
        val candidate = find.invoke(address)
        val id = Tokens.newRequestId()
        lock.withLock {
            expire()
            // The oldest goes when too many wait, so a forgotten tab never holds a place.
            while (pending.size >= MAX_PENDING) {
                pending.entries.minByOrNull { it.value.foundAtUtcMs }?.let { (key, value) -> value.job?.cancel(); pending.remove(key) }
            }
            pending[id] = Pending(candidate, time.nowUtcMs())
        }
        val info = candidate.info
        return FoundServerDto(id, info.name, info.url, info.version, candidate.quickConnectEnabled)
    }

    /** Signs in with a password; returns the server's id, or null when [id] is unknown or expired. */
    suspend fun signIn(id: String, username: String, secret: String): Long? {
        val entry = live(id) ?: return null
        val signedIn = password(entry.candidate, username, secret)
        pending.remove(id)?.job?.cancel()
        return signedIn.serverId
    }

    /** Starts Quick Connect and returns its code, or null when [id] is unknown or expired. */
    suspend fun startQuickConnect(id: String): String? {
        val entry = live(id) ?: return null
        entry.job?.cancel()
        entry.state = SignInStateDto(IDLE)
        var code: String? = null
        val ready = CompletableDeferred<String>()
        entry.job = scope.launch {
            try {
                withTimeout(QUICK_CONNECT_MS) {
                    quickConnect(entry.candidate).collect { update ->
                        when (update) {
                            is QuickConnectState.WaitingForApproval -> {
                                code = update.code
                                entry.state = SignInStateDto(WAITING, code = update.code)
                                ready.complete(update.code)
                            }
                            is QuickConnectState.SignedIn -> entry.state = SignInStateDto(SIGNED_IN, serverId = update.serverId)
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                entry.state = SignInStateDto(FAILED, message = EXPIRED)
                ready.completeExceptionally(ServerConnectException(ServerProblem.SERVER_ERROR, EXPIRED))
            } catch (e: CancellationException) {
                ready.cancel()
                throw e
            } catch (e: ServerConnectException) {
                entry.state = SignInStateDto(FAILED, message = if (e.problem == ServerProblem.SERVER_ERROR && code != null) EXPIRED else e.userMessage())
                ready.completeExceptionally(e)
            } catch (e: Exception) {
                entry.state = SignInStateDto(FAILED, message = e.message ?: "That didn't work.")
                ready.completeExceptionally(e)
            }
        }
        return ready.await()
    }

    /** How the sign-in [id] stands, or null when it is unknown or expired. */
    fun state(id: String): SignInStateDto? = pending[id]?.takeIf { !expired(it) }?.state

    /** Gives up on [id]: a running Quick Connect stops. */
    fun cancel(id: String) {
        pending.remove(id)?.job?.cancel()
    }

    private suspend fun live(id: String): Pending? = lock.withLock {
        expire()
        pending[id]
    }

    private fun expire() {
        pending.entries.removeIf { (_, entry) -> expired(entry).also { if (it) entry.job?.cancel() } }
    }

    private fun expired(entry: Pending) = time.nowUtcMs() - entry.foundAtUtcMs > TTL_MS

    companion object {
        const val TTL_MS = 10 * 60 * 1000L
        const val MAX_PENDING = 4
        const val QUICK_CONNECT_MS = 5 * 60 * 1000L
        const val IDLE = "idle"
        const val WAITING = "waiting"
        const val SIGNED_IN = "signedIn"
        const val FAILED = "failed"
        const val EXPIRED = "The code expired. Try again."
    }
}
