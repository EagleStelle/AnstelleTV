package tv.anstelle.app

import android.util.Log
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tv.anstelle.core.data.ProfileRepository
import tv.anstelle.core.data.ondemand.CatalogKind
import tv.anstelle.core.data.ondemand.ShelfType
import tv.anstelle.core.model.ProfileRole
import tv.anstelle.core.model.ServerConnectException
import tv.anstelle.core.model.ServerProblem
import tv.anstelle.feature.ondemand.CatalogViewModel

/** Opt-in recovery of an existing owner sign-in using that same user's valid server session. */
@RunWith(AndroidJUnit4::class)
class HistoryRecoveryTest {
    @Test
    fun reconnectOwnerAndRefreshOpenCatalog() {
        // Never repair sign-ins as a side effect of the regular device regression suite.
        assumeTrue(InstrumentationRegistry.getArguments().getString("repairOwnerHistory") == "true")
        recoverOwnerAndRefresh()
    }

    private fun recoverOwnerAndRefresh() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val graph = EntryPointAccessors.fromApplication(instrumentation.targetContext.applicationContext, CatalogTestEntryPoint::class.java)
        val profile = graph.profiles().activeProfile()
        assertEquals("Recovery is restricted to the existing owner", ProfileRole.OWNER, profile.role)
        val server = graph.servers().all().single()
        val serverToken = requireNotNull(graph.tokens().get(server.jellyfinServerId))
        val serverUser = graph.auth().currentUser(server.baseUrl, serverToken)
        assertEquals("Server credentials must match the stored user", server.userId, serverUser.id)
        val account = graph.profiles().watchAccount(profile.id, server, serverToken)
        assertEquals("Recovery must not switch Jellyfin users", serverUser.id, account?.userId)
        var reconnect = account?.keepsHistory != true
        if (!reconnect) {
            try {
                graph.auth().currentUser(server.baseUrl, requireNotNull(account).accessToken, account.device)
            } catch (e: ServerConnectException) {
                if (e.problem != ServerProblem.SIGNED_OUT) throw e
                reconnect = true
            }
        }
        Log.i("Anstelle/HistoryRecoveryTest", "Owner history account requires reconnect=$reconnect")
        val store = ViewModelStore()
        lateinit var model: CatalogViewModel
        instrumentation.runOnMainSync {
            model = CatalogViewModel(graph.profiles(), graph.catalog(), graph.settings(), graph.reporter(), graph.changes(), Dispatchers.Default)
            store.put("recovery", model)
            model.select(CatalogKind.SERIES)
            model.start()
            model.setVisible(true)
        }
        try {
            withTimeout(10_000) { model.state.first { it.tabs[CatalogKind.SERIES]?.loaded == true } }
            if (reconnect) {
                assertTrue("Quick Connect must be available", graph.auth().isQuickConnectEnabled(server.baseUrl))
                val device = ProfileRepository.newDevice()
                val code = graph.auth().startQuickConnect(server.baseUrl, device)
                graph.auth().authorizeQuickConnect(server.baseUrl, serverToken, code.code)
                withTimeout(10_000) {
                    while (!graph.auth().isQuickConnectApproved(server.baseUrl, code.secret, device)) delay(100)
                }
                val session = graph.auth().finishQuickConnect(server.baseUrl, code.secret, device)
                assertEquals("New session must remain the same user", serverUser.id, session.userId)
                graph.profiles().linkAccount(profile.id, server.id, session, device)
            }
            val shown = withTimeout(30_000) { model.state.first { it.watch.known && it.tabs[CatalogKind.SERIES]?.loaded == true } }
            assertTrue("Episode-count badges missing", shown.watch.byId.values.any { it.unwatched != null })
            val shelves = shown.tabs.getValue(CatalogKind.SERIES).shelves
            val access = graph.profiles().access(profile.id)
            val resume = graph.catalog().continueWatching(CatalogKind.SERIES, profile.id, access)
            val next = graph.catalog().nextUp(profile.id, access)
            assertEquals(resume.map { it.itemId }, shelves.firstOrNull { it.shelf.type == ShelfType.CONTINUE }?.cards.orEmpty().map { it.itemId })
            assertEquals(next.map { it.itemId }, shelves.firstOrNull { it.shelf.type == ShelfType.NEXT_UP }?.cards.orEmpty().map { it.itemId })
            Log.i("Anstelle/HistoryRecoveryTest", "Open catalog recovered: continue=${resume.isNotEmpty()}; nextUp=${next.isNotEmpty()}; episodeCounts=true")
        } finally {
            instrumentation.runOnMainSync { model.setVisible(false); store.clear() }
        }
    }
}
