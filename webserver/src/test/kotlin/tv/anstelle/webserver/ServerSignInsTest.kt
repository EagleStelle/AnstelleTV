package tv.anstelle.webserver

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import tv.anstelle.core.data.QuickConnectState
import tv.anstelle.core.data.ServerCandidate
import tv.anstelle.core.model.PublicServerInfo
import tv.anstelle.core.model.ServerConnectException
import tv.anstelle.core.model.ServerProblem
import tv.anstelle.webserver.api.ServerSignIns
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ServerSignInsTest {
    private val clock = FakeClock()
    private var approved = false
    private var signedInBy: Long? = null

    private fun candidate(address: String) = ServerCandidate(PublicServerInfo("id-$address", "Home", "10.11", "http://$address:8096"), quickConnectEnabled = true)

    private fun TestScope.signIns() = ServerSignIns(
        find = { address -> candidate(address) },
        password = { _, _, password, actorId ->
            signedInBy = actorId
            if (password != "right") throw ServerConnectException(ServerProblem.BAD_CREDENTIALS, "401")
            QuickConnectState.SignedIn(serverId = 1)
        },
        quickConnect = { _, actorId ->
            signedInBy = actorId
            flow {
                emit(QuickConnectState.WaitingForApproval("123456"))
                while (!approved) delay(1_000)
                emit(QuickConnectState.SignedIn(serverId = 2))
            }
        },
        time = clock,
        scope = backgroundScope,
    )

    @Test
    fun aPasswordSignsInOnce() = runTest {
        val signIns = signIns()
        val found = signIns.find("192.168.1.2")
        assertEquals("http://192.168.1.2:8096", found.address)
        assertFailsWith<ServerConnectException> { signIns.signIn(found.signInId, "alex", "nope") }
        assertEquals(1L, signIns.signIn(found.signInId, "alex", "right", actorId = 42))
        assertEquals(42L, signedInBy)
        // Signed in, the server found is forgotten.
        assertNull(signIns.signIn(found.signInId, "alex", "right"))
    }

    @Test
    fun aServerFoundIsKeptForAWhile() = runTest {
        val signIns = signIns()
        val found = signIns.find("192.168.1.2")
        clock.now += ServerSignIns.TTL_MS + 1
        assertNull(signIns.state(found.signInId))
        assertNull(signIns.signIn(found.signInId, "alex", "right"))
    }

    @Test
    fun onlyAFewWaitAtOnce() = runTest {
        val signIns = signIns()
        val first = signIns.find("192.168.1.1")
        repeat(ServerSignIns.MAX_PENDING) { clock.now += 1; signIns.find("192.168.1.${it + 2}") }
        assertNull(signIns.state(first.signInId))
    }

    @Test
    fun quickConnectWaitsForApproval() = runTest {
        val signIns = signIns()
        val found = signIns.find("192.168.1.2")
        assertEquals("123456", signIns.startQuickConnect(found.signInId, actorId = 42))
        assertEquals(42L, signedInBy)
        assertEquals(ServerSignIns.WAITING, signIns.state(found.signInId)?.state)
        approved = true
        advanceTimeBy(1_001)
        runCurrent()
        val state = signIns.state(found.signInId)
        assertEquals(ServerSignIns.SIGNED_IN, state?.state)
        assertEquals(2L, state?.serverId)
    }

    @Test
    fun quickConnectGivesUpInTime() = runTest {
        val signIns = signIns()
        val found = signIns.find("192.168.1.2")
        signIns.startQuickConnect(found.signInId)
        advanceTimeBy(ServerSignIns.QUICK_CONNECT_MS + 1)
        runCurrent()
        val state = signIns.state(found.signInId)
        assertEquals(ServerSignIns.FAILED, state?.state)
        assertEquals(ServerSignIns.EXPIRED, state?.message)
    }

    @Test
    fun cancellingStopsWaiting() = runTest {
        val signIns = signIns()
        val found = signIns.find("192.168.1.2")
        signIns.startQuickConnect(found.signInId)
        signIns.cancel(found.signInId)
        assertNull(signIns.state(found.signInId))
    }
}
