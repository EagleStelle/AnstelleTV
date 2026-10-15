package tv.anstelle.core.jellyfin.sync

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaDataSource
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CoroutineStart
import okhttp3.Call
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.CoroutineContext
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.jellyfin.sdk.model.serializer.toUUID
import okhttp3.OkHttpClient
import okhttp3.Request
import tv.anstelle.core.common.AppLog
import tv.anstelle.core.jellyfin.JellyfinClients
import javax.inject.Inject
import javax.inject.Singleton
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Reads the last playable samples, rather than trusting a possibly broken container duration. */
interface FileDurationProbe {
    suspend fun durationMs(baseUrl: String, token: String, itemId: String, sourceId: String?): Long?
    suspend fun durationMs(baseUrl: String, token: String, itemId: String, sourceId: String?, budgetMs: Long): Long? =
        durationMs(baseUrl, token, itemId, sourceId)
}

@Singleton
class AndroidFileDurationProbe @Inject constructor(
    private val clients: JellyfinClients,
    private val http: OkHttpClient,
) : FileDurationProbe {
    override suspend fun durationMs(baseUrl: String, token: String, itemId: String, sourceId: String?): Long? =
        durationMs(baseUrl, token, itemId, sourceId, MAX_SCAN_MS)

    override suspend fun durationMs(baseUrl: String, token: String, itemId: String, sourceId: String?, budgetMs: Long): Long? = withContext(Dispatchers.IO) {
        val api = clients.api(baseUrl, token)
        val url = api.createUrl("/Videos/{itemId}/stream", mapOf("itemId" to itemId.toUUID()),
            mapOf("static" to true, "mediaSourceId" to (sourceId ?: itemId)), false)
        val authorization = AuthorizationHeaderBuilder.buildHeader(
            api.clientInfo.name, api.clientInfo.version, api.deviceInfo.id, api.deviceInfo.name, token)
        val extractor = MediaExtractor()
        val began = SystemClock.elapsedRealtime()
        val source = RangeSource(http, url, authorization, began + minOf(MAX_SCAN_MS, budgetMs), coroutineContext)
        // MediaExtractor calls the data source synchronously. A sibling cancellation
        // handler cancels the socket immediately even while native extraction is blocked.
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { source.cancel() }
        }
        try {
            extractor.setDataSource(source)
            val last = LongArray(extractor.trackCount) { -1 }
            val beforeLast = LongArray(extractor.trackCount) { -1 }
            var selected = 0
            for (track in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    extractor.selectTrack(track)
                    selected++
                }
            }
            if (selected == 0) return@withContext null
            // An indexed seek goes to the final cluster even when the header's duration is wrong.
            extractor.seekTo(Long.MAX_VALUE / 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            var samples = 0
            while (extractor.sampleTime >= 0) {
                coroutineContext.ensureActive()
                if (++samples > MAX_SAMPLES || SystemClock.elapsedRealtime() - began > MAX_SCAN_MS) return@withContext null
                val track = extractor.sampleTrackIndex
                val position = extractor.sampleTime
                if (position > last[track]) {
                    beforeLast[track] = last[track]
                    last[track] = position
                } else if (position in (beforeLast[track] + 1) until last[track]) {
                    beforeLast[track] = position
                }
                if (!extractor.advance()) break
            }
            if (source.failed) return@withContext null
            // A timestamp marks the start of the last sample. Include its observed sample span.
            last.indices.filter { beforeLast[it] >= 0 }.maxOfOrNull { track ->
                (last[track] + (last[track] - beforeLast[track]).coerceAtMost(MAX_SAMPLE_US) + 999) / 1000
            }?.takeIf { it > 0 }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A failed probe never replaces the server's duration or prevents library sync.
            AppLog.w("Duration", "Could not read the file's final samples")
            null
        } finally {
            cancellation.cancel()
            extractor.release()
            source.close()
        }
    }

    /** Explicit range reads bound opening and native seeking too, not only the sample loop. */
    private class RangeSource(
        private val http: OkHttpClient,
        private val url: String,
        private val authorization: String,
        private val deadline: Long,
        private val context: CoroutineContext,
    ) : MediaDataSource() {
        private var length = -1L
        private var start = -1L
        private var bytes = byteArrayOf()
        @Volatile private var activeCall: Call? = null
        @Volatile private var cancelled = false
        @Volatile var failed = false
            private set

        private fun failure(message: String): IOException {
            failed = true
            return IOException(message)
        }

        private fun fetch(position: Long, count: Int): ByteArray {
            context.ensureActive()
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) throw failure("File duration probe timed out")
            val request = Request.Builder().url(url).header("Authorization", authorization)
                .header("Range", "bytes=$position-${position + count - 1}").header("Accept-Encoding", "identity").build()
            val call = http.newCall(request)
            activeCall = call
            if (cancelled) call.cancel()
            call.timeout().timeout(remaining, TimeUnit.MILLISECONDS)
            return try { call.execute().use { response ->
                if (response.code != 206 && !(position == 0L && response.code == 200)) throw failure("File range unavailable")
                val total = response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                    ?: response.header("Content-Length")?.toLongOrNull()?.takeIf { response.code == 200 }
                if (total != null) length = total
                response.body.byteStream().use { input ->
                    val result = ByteArray(count)
                    var read = 0
                    while (read < count) {
                        val n = input.read(result, read, count - read)
                        if (n < 0) break
                        read += n
                    }
                    result.copyOf(read)
                }
            } } catch (e: IOException) {
                failed = true
                throw e
            } finally { activeCall = null }
        }

        @Synchronized
        override fun getSize(): Long {
            if (length < 0) fetch(0, 1)
            return length
        }

        @Synchronized
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (size == 0) return 0
            if (position < 0 || (length >= 0 && position >= length)) return -1
            var filled = 0
            while (filled < size) {
                context.ensureActive()
                if (SystemClock.elapsedRealtime() >= deadline) throw failure("File duration probe timed out")
                val current = position + filled
                if (length >= 0 && current >= length) break
                if (current < start || current >= start + bytes.size) {
                    start = current
                    bytes = fetch(current, maxOf(size - filled, 256 * 1024).coerceAtMost(1024 * 1024))
                }
                val at = (current - start).toInt()
                val count = minOf(size - filled, bytes.size - at)
                if (count <= 0) {
                    if (length >= 0 && current < length) throw failure("Incomplete file range")
                    break
                }
                bytes.copyInto(buffer, offset + filled, at, at + count)
                filled += count
            }
            return if (filled > 0) filled else -1
        }

        @Synchronized
        override fun close() { bytes = byteArrayOf() }

        fun cancel() { cancelled = true; activeCall?.cancel() }
    }

    private companion object {
        const val MAX_SAMPLES = 10_000
        const val MAX_SCAN_MS = 20_000L
        const val MAX_SAMPLE_US = 1_000_000L
    }
}
