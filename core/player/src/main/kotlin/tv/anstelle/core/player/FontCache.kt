package tv.anstelle.core.player

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import tv.anstelle.core.common.AnstelleDispatchers
import tv.anstelle.core.common.AppLog
import tv.anstelle.core.common.Dispatcher
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fonts for side-loaded ASS subtitles, downloaded from the item's attachments and kept on
 * disk, so tuning to the same item again doesn't fetch them again. Loading gives up after a
 * few seconds; subtitles then fall back to libass' default font rather than holding up playback.
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
            val deadline = System.currentTimeMillis() + LOAD_LIMIT_MS
            val fonts = ArrayList<FontFile>(stream.fonts.size)
            var fetched = false
            for (font in stream.fonts) {
                val file = File(dir, key(font.url))
                if (!file.exists()) {
                    if (System.currentTimeMillis() > deadline || !download(font.url, file)) continue
                    fetched = true
                }
                file.setLastModified(System.currentTimeMillis())
                fonts += FontFile(font.fileName, file.readBytes())
            }
            if (fetched) trim()
            fonts
        }
    }

    private fun download(url: String, file: File): Boolean = runCatching {
        val request = Request.Builder().url(url).apply { auth.headerFor(url)?.let { header("Authorization", it) } }.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            dir.mkdirs()
            val partial = File(dir, "${file.name}.part")
            partial.outputStream().use { response.body.byteStream().copyTo(it) }
            partial.renameTo(file)
        }
    }.onFailure { AppLog.w(TAG, "Font download failed: ${it.message}") }.getOrDefault(false)

    /** Drops the least recently used fonts once the cache is over its size. */
    private fun trim() {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        for (file in files) {
            total += file.length()
            if (total > MAX_BYTES) file.delete()
        }
    }

    private fun key(url: String): String =
        MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "Fonts"
        const val LOAD_LIMIT_MS = 3_000L
        const val MAX_BYTES = 150L * 1024 * 1024
    }
}
