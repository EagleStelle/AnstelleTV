package tv.anstelle.core.player

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.OkHttpClient
import okhttp3.Request
import tv.anstelle.core.common.AnstelleDispatchers
import tv.anstelle.core.common.AppLog
import tv.anstelle.core.common.Dispatcher
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fonts for side-loaded ASS subtitles, downloaded from the item's attachments and kept on
 * disk, so tuning to the same item again doesn't fetch them again. Every episode of a show
 * attaches the same fonts under URLs of its own, so each font is kept once, by its contents:
 * a small `.ref` file per URL names the `.font` file it gave. Loading gives up after a few
 * seconds; subtitles then fall back to libass' default font rather than holding up playback.
 */
@Singleton
class FontCache @Inject constructor(
    @param:ApplicationContext context: Context,
    okHttpClient: OkHttpClient,
    private val auth: StreamAuth,
    @param:Dispatcher(AnstelleDispatchers.IO) private val io: CoroutineDispatcher,
) {
    private val dir = File(context.cacheDir, "fonts")
    private val client = okHttpClient.newBuilder().callTimeout(LOAD_LIMIT_MS, TimeUnit.MILLISECONDS).build()

    /** The fonts [stream] asks for; any that can't be had within the time limit are left out. */
    suspend fun load(stream: ProgramStream): List<FontFile> {
        if (stream.fonts.isEmpty()) return emptyList()
        return withContext(io) {
            val fonts = ArrayList<FontFile>(stream.fonts.size)
            withTimeoutOrNull(LOAD_LIMIT_MS) {
                for (font in stream.fonts) {
                    currentCoroutineContext().ensureActive()
                    val ref = File(dir, sha1(font.url.toByteArray()) + REF)
                    val file = ref.takeIf { it.exists() }?.let { File(dir, it.readText()) }?.takeIf { it.exists() }
                        ?: download(font.url)?.also { ref.writeText(it.name) }
                    if (file == null) continue
                    val now = System.currentTimeMillis()
                    file.setLastModified(now)
                    ref.setLastModified(now)
                    runCatching { file.readBytes() }.onSuccess { fonts += FontFile(font.fileName, it) }
                }
            }
            // Housekeeping trims the cache; channel startup does not scan its directory.
            fonts
        }
    }

    /** Downloads the font at [url] and returns its file, named by its contents. */
    private suspend fun download(url: String): File? = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(url).apply { auth.headerFor(url)?.let { header("Authorization", it) } }.build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resume(null)
            }
            override fun onResponse(call: Call, response: Response) {
                val file = runCatching {
                    response.use {
                        check(it.isSuccessful) { "HTTP ${it.code}" }
                        val data = it.body.bytes()
                        if (!continuation.isActive) return@use null
                        dir.mkdirs()
                        val target = File(dir, sha1(data) + FONT)
                        if (!target.exists()) {
                            val partial = File.createTempFile("font-", PART, dir)
                            try { partial.writeBytes(data); partial.renameTo(target) } finally { partial.delete() }
                        }
                        target.takeIf { it.exists() }
                    }
                }.onFailure { AppLog.w(TAG, "Font download failed: ${it.message}") }.getOrNull()
                continuation.resume(file)
            }
        })
    }

    /** Trims the cache to its size; daily housekeeping runs it, so nothing waits on a tune to free the space. */
    suspend fun tidy() = withContext(io) { trim() }

    /**
     * Drops the least recently used fonts once the cache is over its size, the URLs that named
     * them, and files of the old one-per-URL layout.
     */
    private fun trim() {
        val files = dir.listFiles() ?: return
        var total = 0L
        val kept = HashSet<String>()
        for (file in files.filter { it.name.endsWith(FONT) }.sortedByDescending { it.lastModified() }) {
            total += file.length()
            if (total > MAX_BYTES) file.delete() else kept += file.name
        }
        val stale = System.currentTimeMillis() - PARTIAL_MS
        for (file in files) {
            when {
                file.name.endsWith(FONT) -> Unit
                file.name.endsWith(REF) -> if (runCatching { file.readText() }.getOrNull() !in kept) file.delete()
                // Another download may still be writing it.
                file.name.endsWith(PART) -> if (file.lastModified() < stale) file.delete()
                else -> file.delete()
            }
        }
    }

    private fun sha1(data: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(data).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "Fonts"
        const val LOAD_LIMIT_MS = 3_000L
        const val MAX_BYTES = 100L * 1024 * 1024
        const val FONT = ".font"
        const val REF = ".ref"
        const val PART = ".part"
        const val PARTIAL_MS = 60_000L
    }
}
