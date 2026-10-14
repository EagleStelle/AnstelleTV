package tv.anstelle.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tv.anstelle.core.model.ServerConnectException

/** Read-only diagnosis of device sign-ins. Logs outcomes only, never credentials or user data. */
@RunWith(AndroidJUnit4::class)
class HistoryAuthenticationTest {
    @Test
    fun inspectStoredSignInHealth() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val graph = EntryPointAccessors.fromApplication(context.applicationContext, CatalogTestEntryPoint::class.java)
        assumeTrue(graph.profiles().hasProfiles())
        val profile = graph.profiles().activeProfile()
        for (server in graph.servers().all()) {
            val serverToken = graph.tokens().get(server.jellyfinServerId) ?: continue
            suspend fun outcome(token: String, device: String?): String = try {
                val user = graph.auth().currentUser(server.baseUrl, token, device)
                if (user.id == server.userId) "valid" else "different-user"
            } catch (e: ServerConnectException) {
                e.problem.name
            }
            val account = graph.profiles().watchAccount(profile.id, server, serverToken)
            val serverState = outcome(serverToken, null)
            val profileState = account?.let { outcome(it.accessToken, it.device) } ?: "missing"
            val quickConnect = graph.auth().isQuickConnectEnabled(server.baseUrl)
            Log.i("Anstelle/HistoryAuthTest", "server=$serverState; profile=$profileState; " +
                "sameUser=${account?.userId == server.userId}; keepsHistory=${account?.keepsHistory}; quickConnect=$quickConnect")
        }
    }
}
