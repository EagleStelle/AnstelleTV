package tv.anstelle.webserver

import kotlinx.coroutines.test.runTest
import tv.anstelle.webserver.pairing.PairResult
import tv.anstelle.webserver.pairing.PairStartResult
import tv.anstelle.webserver.pairing.PairingManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingManagerTest {
    private val server = TestServer()
    private val pairing = server.pairing

    private suspend fun startPairing() =
        assertIs<PairStartResult.Started>(pairing.start("Laptop", "192.168.1.5")).request

    @Test
    fun correctPinIssuesTokenAndStoresOnlyItsHash() = runTest {
        val request = startPairing()
        assertEquals(6, request.pin.length)

        val result = assertIs<PairResult.Paired>(pairing.confirm(request.id, request.pin, "UA", "192.168.1.5"))
        val stored = server.store.clients.values.single()
        assertTrue(stored.tokenHash != result.token)
        assertEquals(64, stored.tokenHash.length)
        assertNull(pairing.activeRequest.value)
        assertNotNull(pairing.authenticate(result.token, "192.168.1.5"))
    }

    @Test
    fun wrongPinThenRightPinStillPairs() = runTest {
        val request = startPairing()
        val wrong = if (request.pin == "000000") "111111" else "000000"
        assertEquals(PairResult.WrongPin, pairing.confirm(request.id, wrong, null, null))
        assertIs<PairResult.Paired>(pairing.confirm(request.id, request.pin, null, null))
    }

    @Test
    fun tooManyWrongPinsCancelsRequest() = runTest {
        val request = startPairing()
        val wrong = if (request.pin == "000000") "111111" else "000000"
        repeat(PairingManager.MAX_ATTEMPTS - 1) {
            assertEquals(PairResult.WrongPin, pairing.confirm(request.id, wrong, null, null))
        }
        assertEquals(PairResult.TooManyAttempts, pairing.confirm(request.id, wrong, null, null))
        assertEquals(PairResult.NoRequest, pairing.confirm(request.id, request.pin, null, null))
    }

    @Test
    fun expiredPinIsRejected() = runTest {
        val request = startPairing()
        server.clock.now += PairingManager.PIN_TTL_MS + 1
        assertEquals(PairResult.Expired, pairing.confirm(request.id, request.pin, null, null))
    }

    @Test
    fun newRequestReplacesOldPin() = runTest {
        val first = startPairing()
        val second = startPairing()
        assertEquals(PairResult.NoRequest, pairing.confirm(first.id, first.pin, null, null))
        assertIs<PairResult.Paired>(pairing.confirm(second.id, second.pin, null, null))
    }

    @Test
    fun pairingStartsAreRateLimited() = runTest {
        repeat(PairingManager.MAX_STARTS) { startPairing() }
        assertEquals(PairStartResult.RateLimited, pairing.start("Laptop", null))
        server.clock.now += PairingManager.START_WINDOW_MS + 1
        startPairing()
    }

    @Test
    fun unknownOrRevokedTokensAreRejected() = runTest {
        assertNull(pairing.authenticate("not-a-token", null))
        assertNull(pairing.authenticate(null, null))
        val request = startPairing()
        val paired = assertIs<PairResult.Paired>(pairing.confirm(request.id, request.pin, null, null))
        pairing.revoke(paired.client.id)
        assertNull(pairing.authenticate(paired.token, null))
    }
}
