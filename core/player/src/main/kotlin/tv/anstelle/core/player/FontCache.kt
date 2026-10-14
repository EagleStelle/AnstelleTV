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
            val deadline = System.currentTimeMillis() + LOAD_LIMIT_MS
            val fonts = ArrayList<FontFile>(stream.fonts.size)
            var changed = false
            for (font in stream.fonts) {
                val ref = File(dir, sha1(font.url.toByteArray()) + REF)
                val file = ref.takeIf { it.exists() }?.let { File(dir, it.readText()) }?.takeIf { it.exists() }
                    ?: if (System.currentTimeMillis() > deadline) null else download(font.url)?.also {
                        ref.writeText(it.name)
                        changed = true
                    }
                if (file == null) continue
                val now = System.currentTimeMillis()
                file.setLastModified(now)
                ref.setLastModified(now)
                runCatching { file.readBytes() }.onSuccess { fonts += FontFile(font.fileName, it) }
            }
            // Cache hits only touch their LRU timestamps; daily housekeeping handles old debris.
            if (changed) trim()
            fonts
        }
    }

    /** Downloads the font at [url] and returns its file, named by its contents. */
    private fun download(url: String): File? = runCatching {
        val request = Request.Builder().url(url).apply { auth.headerFor(url)?.let { header("Authorization", it) } }.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val data = response.body.bytes()
            dir.mkdirs()
            val file = File(dir, sha1(data) + FONT)
            if (!file.exists()) {
                val partial = File(dir, file.name + PART)
                partial.writeBytes(data)
                partial.renameTo(file)
            }
            file
        }
    }.onFailure { AppLog.w(TAG, "Font download failed: ${it.message}") }.getOrNull()

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
