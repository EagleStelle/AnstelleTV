package tv.anstelle.app

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tv.anstelle.core.data.ondemand.CatalogKind
import tv.anstelle.core.data.ondemand.ShelfType
import tv.anstelle.core.model.ServerConnectException
import tv.anstelle.feature.ondemand.CatalogViewModel

/** Uses the device's own library; does not change library or watch data. */
@RunWith(AndroidJUnit4::class)
class CatalogLoadingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val graph = EntryPointAccessors.fromApplication(
        instrumentation.targetContext.applicationContext, CatalogTestEntryPoint::class.java,
    )

    @Test
    fun metadataIsReusedAcrossVisits() = runBlocking {
        assumeTrue(graph.profiles().hasProfiles())
        val access = graph.profiles().access(graph.profiles().activeProfile().id)
        for (kind in CatalogKind.entries) {
            val began = SystemClock.elapsedRealtime()
            val first = graph.catalog().titles(kind, access)
            val initialMs = SystemClock.elapsedRealtime() - began
            val cachedAt = SystemClock.elapsedRealtime()
            val cached = graph.catalog().titles(kind, access)
            assertSame("$kind titles rebuilt on revisit", first, cached)
            Log.i("Anstelle/CatalogTest", "$kind: ${first.size} titles; first $initialMs ms; cached ${SystemClock.elapsedRealtime() - cachedAt} ms")
        }
        val channels = graph.catalog().channelTitles(CatalogKind.SERIES)
        assertSame("Channel matching repeated", channels, graph.catalog().channelTitles(CatalogKind.SERIES))
    }

    @Test
    fun onlyOpenKindIsPublishedWithoutWaitingForServerRows() = runBlocking<Unit> {
        assumeTrue(graph.profiles().hasProfiles())
        val store = ViewModelStore()
        lateinit var model: CatalogViewModel
        val began = SystemClock.elapsedRealtime()
        instrumentation.runOnMainSync {
            model = CatalogViewModel(graph.profiles(), graph.catalog(), graph.settings(), graph.reporter(), graph.changes(), Dispatchers.Default)
            store.put("catalog", model)
            model.select(CatalogKind.SERIES)
            model.start()
            model.setVisible(true)
        }
        try {
            val shown = withTimeout(10_000) { model.state.first { it.tabs[CatalogKind.SERIES]?.loaded == true } }
            assertTrue("Closed Movies tab was built", CatalogKind.MOVIES !in shown.tabs)
            Log.i("Anstelle/CatalogTest", "Series first local state in ${SystemClock.elapsedRealtime() - began} ms")
        } finally {
            instrumentation.runOnMainSync { model.setVisible(false); store.clear() }
        }
    }

    @Test
    fun serverHistoryRowsAndEpisodeCountsReachPublishedState() = runBlocking<Unit> {
        assumeTrue(graph.profiles().hasProfiles())
        val profile = graph.profiles().activeProfile()
        for (server in graph.servers().all()) {
            val token = graph.tokens().get(server.jellyfinServerId) ?: continue
            val account = graph.profiles().watchAccount(profile.id, server, token) ?: continue
            try {
                graph.auth().currentUser(server.baseUrl, account.accessToken, account.device)
            } catch (e: ServerConnectException) {
                assumeTrue("Profile must be signed in for history verification", false)
            }
        }
        val access = graph.profiles().access(profile.id)
        for (kind in CatalogKind.entries) verifyHistory(kind, profile.id, access)
    }

    private suspend fun verifyHistory(kind: CatalogKind, profileId: Long, access: tv.anstelle.core.model.ProfileAccess) {
        val expectedWatch = graph.catalog().watch(profileId, kind)
        assertTrue("$kind server history unavailable", expectedWatch.known)
        if (kind.episodic) {
            assertTrue("Series episode counts missing from server response", expectedWatch.byId.values.any { it.unwatched != null })
        }
        val expectedResume = graph.catalog().continueWatching(kind, profileId, access)
        val expectedNext = if (ShelfType.NEXT_UP in kind.fixedShelves) graph.catalog().nextUp(profileId, access) else emptyList()
        val continuingTitles = expectedResume.mapTo(HashSet()) { it.titleId }
        assertTrue("Next up repeats a series from Continue watching", expectedNext.none { it.titleId in continuingTitles })
        val store = ViewModelStore()
        lateinit var model: CatalogViewModel
        instrumentation.runOnMainSync {
            model = CatalogViewModel(graph.profiles(), graph.catalog(), graph.settings(), graph.reporter(), graph.changes(), Dispatchers.Default)
            store.put("history", model)
            model.select(kind)
            model.start()
            model.setVisible(true)
        }
        try {
            val shown = withTimeout(30_000) { model.state.first { it.watch.known && it.tabs[kind]?.loaded == true } }
            val shelves = shown.tabs.getValue(kind).shelves
            assertEquals(expectedResume.map { it.itemId }, shelves.firstOrNull { it.shelf.type == ShelfType.CONTINUE }?.cards.orEmpty().map { it.itemId })
            assertEquals(expectedNext.map { it.itemId }, shelves.firstOrNull { it.shelf.type == ShelfType.NEXT_UP }?.cards.orEmpty().map { it.itemId })
            assertEquals(expectedWatch.byId, shown.watch.byId)
            Log.i("Anstelle/CatalogTest", "$kind server history verified: continue=${expectedResume.isNotEmpty()}; nextUp=${expectedNext.isNotEmpty()}")
        } finally {
            instrumentation.runOnMainSync { model.setVisible(false); store.clear() }
        }
    }
}
