package tv.anstelle.app

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tv.anstelle.core.jellyfin.JellyfinClients
import tv.anstelle.core.jellyfin.di.NetworkModule
import tv.anstelle.core.jellyfin.sync.AndroidFileDurationProbe
import java.util.concurrent.TimeUnit

/** Opt-in device checks. Only the normal scheduler writes repairs; credentials and titles stay private. */
@RunWith(AndroidJUnit4::class)
class MediaDurationTest {
    @Test
    fun inspectServerDurations() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val itemId = arguments.getString("durationItemId")?.toLongOrNull()
        assumeTrue("Requires an explicit device item id", itemId != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val graph = EntryPointAccessors.fromApplication(context.applicationContext, CatalogTestEntryPoint::class.java)
        val row = SQLiteDatabase.openDatabase(context.getDatabasePath("anstelle.db").path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            database.rawQuery("SELECT serverId, remoteId, durationMs FROM media_item WHERE id = ?", arrayOf(itemId.toString())).use { cursor ->
                assertTrue("Device item missing", cursor.moveToFirst())
                Triple(cursor.getLong(0), cursor.getString(1), cursor.getLong(2))
            }
        }
        val server = requireNotNull(graph.servers().byId(row.first))
        val serverToken = requireNotNull(graph.tokens().get(server.jellyfinServerId))
        val profile = graph.profiles().activeProfile()
        val account = graph.profiles().watchAccount(profile.id, server, serverToken)
        val token = account?.accessToken ?: serverToken
        val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        val api = JellyfinClients(NetworkModule.jellyfin(context, client)).api(server.baseUrl, token, account?.device)
        val userId = account?.userId ?: requireNotNull(server.userId)
        val authorization = AuthorizationHeaderBuilder.buildHeader(
            api.clientInfo.name, api.clientInfo.version, api.deviceInfo.id, api.deviceInfo.name, token)
        val request = Request.Builder()
            .url("${server.baseUrl.trimEnd('/')}/Users/$userId/Items/${row.second}")
            .header("Authorization", authorization)
            .build()
        client.newCall(request).execute().use { response ->
            assertTrue("Metadata request failed: HTTP ${response.code}", response.isSuccessful)
            val dto = JSONObject(requireNotNull(response.body).string())
            Log.i(TAG, "Stored durationMs=${row.third}; item runtimeMs=${dto.optLong("RunTimeTicks") / 10_000}")
            val sources = dto.optJSONArray("MediaSources")
            for (index in 0 until (sources?.length() ?: 0)) {
                val source = requireNotNull(sources).getJSONObject(index)
                Log.i(TAG, "Source $index runtimeMs=${source.optLong("RunTimeTicks") / 10_000}")
                val streams = source.optJSONArray("MediaStreams")
                for (streamIndex in 0 until (streams?.length() ?: 0)) {
                    val stream = requireNotNull(streams).getJSONObject(streamIndex)
                    if (stream.optString("Type") in listOf("Video", "Audio")) {
                        Log.i(TAG, "Stream type=${stream.optString("Type")}; duration=${stream.opt("Duration")}; frameCount=${stream.opt("FrameCount")}; frameRate=${stream.opt("AverageFrameRate")}")
                    }
                }
            }
        }
        val measured = AndroidFileDurationProbe(JellyfinClients(NetworkModule.jellyfin(context, client)), client)
            .durationMs(server.baseUrl, token, row.second, null)
        assertTrue("File timestamps unavailable", measured != null && measured > 0)
        Log.i(TAG, "Measured file durationMs=$measured")
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test
    fun schedulingAutomaticallyCorrectsFileRuntimes() = runBlocking<Unit> {
        val arguments = InstrumentationRegistry.getArguments()
        val itemId = arguments.getString("durationItemId")?.toLongOrNull()
        val number = arguments.getString("durationChannelNumber")?.toIntOrNull()
        assumeTrue("Requires explicit device item and channel", itemId != null && number != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val graph = EntryPointAccessors.fromApplication(context.applicationContext, DurationTestEntryPoint::class.java)
        val accounts = EntryPointAccessors.fromApplication(context.applicationContext, CatalogTestEntryPoint::class.java)
        val before = requireNotNull(graph.items().byId(requireNotNull(itemId)))
        val server = requireNotNull(accounts.servers().byId(before.serverId))
        val token = requireNotNull(accounts.tokens().get(server.jellyfinServerId))
        val measured = requireNotNull(graph.files().durationMs(server.baseUrl, token, before.remoteId, before.mediaSourceId))
        val channel = graph.channels().all().single { it.number == number }
        val now = System.currentTimeMillis()
        // Local scheduling never probes. Exercise the production background worker instead.
        val work = androidx.work.WorkManager.getInstance(context)
        tv.anstelle.core.data.work.DurationWorker.enqueue(work)
        kotlinx.coroutines.withTimeout(90_000) {
            while (kotlin.math.abs(requireNotNull(graph.items().byId(itemId)).durationMs - measured) > 5_000) {
                kotlinx.coroutines.delay(250)
            }
        }
        // The measured row can be visible just before the worker finishes its schedule batch.
        graph.updater().update(channel.id)
        val after = requireNotNull(graph.items().byId(itemId))
        assertTrue("Scheduling did not adopt the file's measured duration", kotlin.math.abs(after.durationMs - measured) <= 5_000)
        val from = graph.schedules().slotAt(channel.id, now)?.startUtcMs ?: now
        val slots = graph.schedules().slotsFrom(channel.id, from, 10_000)
        val repaired = slots.filter { it.mediaItemId == itemId }
        assertTrue("No future airings to verify", repaired.isNotEmpty())
        assertTrue("Future airings still use the broken header", repaired.all {
            kotlin.math.abs(it.endUtcMs - it.startUtcMs - measured) <= 5_000
        })
        assertTrue("Repair created a schedule gap", slots.zipWithNext().all { (a, b) -> a.endUtcMs == b.startUtcMs })
        Log.i(TAG, "Automatic scheduling: ${before.durationMs} ms to ${after.durationMs} ms; ${repaired.size} future airings verified")
    }

    private companion object {
        const val TAG = "Anstelle/MediaDurationTest"
    }
}
