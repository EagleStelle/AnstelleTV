package tv.anstelle.webserver.pairing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tv.anstelle.core.common.AppLog
import tv.anstelle.core.common.TimeSource
import tv.anstelle.core.database.entity.PairedClientEntity
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** A pairing attempt in progress. The PIN is shown on the TV, never sent to the browser. */
data class PairingRequest(
    val id: String,
    val pin: String,
    val clientName: String,
    val address: String?,
    val expiresAtUtcMs: Long,
)

sealed interface PairStartResult {
    data class Started(val request: PairingRequest) : PairStartResult
    data object RateLimited : PairStartResult
}

sealed interface PairResult {
    data class Paired(val token: String, val client: PairedClientEntity) : PairResult
    data object WrongPin : PairResult
    data object Expired : PairResult
    data object TooManyAttempts : PairResult
    data object NoRequest : PairResult
}

/**
 * Pairs browsers with the TV.
 *
 * 1. A browser asks to pair; the TV shows a 6-digit PIN for [PIN_TTL_MS].
 * 2. The browser sends the PIN back. After [MAX_ATTEMPTS] wrong PINs the request dies.
 * 3. On success the browser gets a random 256-bit token; only its SHA-256 is stored.
 *
 * New requests are limited to [MAX_STARTS] per [START_WINDOW_MS] so a LAN client cannot
 * cycle PINs quickly. Each new request replaces the PIN on screen, so the person at the
 * TV sees every attempt.
 */
@Singleton
class PairingManager @Inject constructor(
    private val store: PairedClientStore,
    private val time: TimeSource,
) {
    private val mutex = Mutex()
    private val _activeRequest = MutableStateFlow<PairingRequest?>(null)
    private var attempts = 0
    private val recentStarts = ArrayDeque<Long>()

    /** Cache of authenticated token hashes so `/api` calls rarely touch the database. */
    private val authCache = ConcurrentHashMap<String, CachedClient>()

    /** The request whose PIN the TV should show, or null. */
    val activeRequest: StateFlow<PairingRequest?> = _activeRequest.asStateFlow()

    suspend fun start(clientName: String, address: String?): PairStartResult = mutex.withLock {
        val now = time.nowUtcMs()
        while (recentStarts.isNotEmpty() && now - recentStarts.first() > START_WINDOW_MS) recentStarts.removeFirst()
        if (recentStarts.size >= MAX_STARTS) return@withLock PairStartResult.RateLimited
        recentStarts.addLast(now)

        val request = PairingRequest(
            id = Tokens.newRequestId(),
            pin = Tokens.newPin(),
            clientName = clientName.take(MAX_NAME_LENGTH).ifBlank { "Browser" },
            address = address,
            expiresAtUtcMs = now + PIN_TTL_MS,
        )
        attempts = 0
        _activeRequest.value = request
        AppLog.i(TAG, "Pairing requested by ${request.clientName} ($address)")
        PairStartResult.Started(request)
    }

    suspend fun confirm(requestId: String, pin: String, userAgent: String?, address: String?): PairResult =
        mutex.withLock {
            val request = _activeRequest.value
            if (request == null || !Tokens.constantTimeEquals(request.id, requestId)) {
                return@withLock PairResult.NoRequest
            }
            val now = time.nowUtcMs()
            if (now > request.expiresAtUtcMs) {
                _activeRequest.value = null
                return@withLock PairResult.Expired
            }
            if (!Tokens.constantTimeEquals(request.pin, pin.trim())) {
                attempts++
                if (attempts >= MAX_ATTEMPTS) {
                    _activeRequest.value = null
                    AppLog.w(TAG, "Pairing from ${request.clientName} cancelled after $attempts wrong PINs")
                    return@withLock PairResult.TooManyAttempts
                }
                return@withLock PairResult.WrongPin
            }

            val token = Tokens.newToken()
            val entity = PairedClientEntity(
                tokenHash = Tokens.hash(token),
                name = request.clientName,
                userAgent = userAgent?.take(MAX_AGENT_LENGTH),
                lastAddress = address,
                createdAtUtcMs = now,
                lastSeenUtcMs = now,
            )
            val id = store.insert(entity)
            _activeRequest.value = null
            AppLog.i(TAG, "Paired ${request.clientName}")
            PairResult.Paired(token, entity.copy(id = id))
        }

    /** Dismisses the PIN on the TV. */
    fun cancel() {
        _activeRequest.value = null
    }

    /** Returns the paired client for [token], or null when the token is unknown. */
    suspend fun authenticate(token: String?, address: String?): PairedClientEntity? {
        if (token.isNullOrEmpty() || token.length > MAX_TOKEN_LENGTH) return null
        val hash = Tokens.hash(token)
        val now = time.nowUtcMs()
        val cached = authCache[hash]
        if (cached != null) {
            if (now - cached.lastTouchUtcMs > TOUCH_INTERVAL_MS) {
                authCache[hash] = cached.copy(lastTouchUtcMs = now)
                store.touch(cached.client.id, now, address)
            }
            return cached.client
        }
        val client = store.byTokenHash(hash) ?: return null
        authCache[hash] = CachedClient(client, now)
        store.touch(client.id, now, address)
        return client
    }

    suspend fun revoke(clientId: Long) {
        authCache.entries.removeIf { it.value.client.id == clientId }
        store.delete(clientId)
    }

    private data class CachedClient(val client: PairedClientEntity, val lastTouchUtcMs: Long)

    companion object {
        private const val TAG = "Pairing"
        const val PIN_TTL_MS = 2 * 60 * 1000L
        const val MAX_ATTEMPTS = 5
        const val MAX_STARTS = 10
        const val START_WINDOW_MS = 10 * 60 * 1000L
        private const val TOUCH_INTERVAL_MS = 60 * 1000L
        private const val MAX_NAME_LENGTH = 64
        private const val MAX_AGENT_LENGTH = 256
        private const val MAX_TOKEN_LENGTH = 128
    }
}
