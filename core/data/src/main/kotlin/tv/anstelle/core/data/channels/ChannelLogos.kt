package tv.anstelle.core.data.channels

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Channel logos as small PNG files in app storage. Uploads are scaled down once, so the
 * guide never decodes a full-size image.
 */
@Singleton
class ChannelLogos @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val folder: File get() = File(context.filesDir, FOLDER).also { it.mkdirs() }

    /**
     * Decodes [bytes] (PNG, JPEG, WebP or GIF), scales it to fit [MAX_SIZE_PX] and stores it.
     * Returns the logo's URI. Throws [IOException] when the bytes are not an image.
     */
    fun store(channelId: Long, bytes: ByteArray): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Not an image")
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_SIZE_PX && bounds.outHeight / (sample * 2) >= MAX_SIZE_PX) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("Not an image")
        val scale = minOf(1f, MAX_SIZE_PX.toFloat() / maxOf(decoded.width, decoded.height))
        val bitmap = if (scale < 1f) {
            decoded.scale((decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1))
        } else {
            decoded
        }
        // A new name per upload, so anything showing the old logo reloads.
        val file = File(folder, "$channelId-${System.currentTimeMillis()}.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (bitmap !== decoded) bitmap.recycle()
        decoded.recycle()
        return file.toURI().toString()
    }

    /** The stored PNG behind [uri], or null when it is not one of ours. */
    fun file(uri: String?): File? {
        val path = uri?.removePrefix("file:") ?: return null
        val file = File(path).canonicalFile
        return file.takeIf { it.parentFile == folder.canonicalFile && it.isFile }
    }

    fun delete(uri: String?) {
        file(uri)?.delete()
    }

    /** Deletes every stored logo but the ones behind [uris]. */
    fun keepOnly(uris: Collection<String>) {
        val keep = uris.mapNotNullTo(HashSet()) { file(it)?.name }
        folder.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
    }

    companion object {
        private const val FOLDER = "logos"
        const val MAX_SIZE_PX = 256

        /** Uploads above this are refused before decoding. */
        const val MAX_UPLOAD_BYTES = 4 * 1024 * 1024
    }
}
