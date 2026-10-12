package tv.anstelle.core.data.channels

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.graphics.scale
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Channel logos as small standard files in app storage, prepared once on upload so the guide
 * and the player never decode a full-size image. Every logo is made the same way set-top picons
 * are: its clear margins trimmed, then fitted inside one 5:3 box ([BOX_WIDTH_PX] ×
 * [BOX_HEIGHT_PX]), so logos drawn with wide or narrow margins come out at the same weight.
 * Files are lossless: WebP where the device encodes WebP lossless (Android 10 and later), PNG
 * before, which older devices decode as well.
 */
@Singleton
class ChannelLogos @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val folder: File get() = File(context.filesDir, FOLDER).also { it.mkdirs() }

    /**
     * Decodes [bytes] (PNG, JPEG, WebP or GIF), makes it a standard logo and stores it. Returns
     * the logo's URI. Throws [IOException] when the bytes are not an image.
     */
    fun store(channelId: Long, bytes: ByteArray): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Not an image")
        // Decode at most about DECODE_MAX_PX a side: the margins may still take much of the image.
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= DECODE_MAX_PX) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: throw IOException("Not an image")
        val trimmed = trim(decoded)
        val fitted = fit(trimmed)
        val (encoded, extension) = encode(fitted)
        // Bitmaps compare by identity, so a stage that changed nothing is recycled once.
        setOf(decoded, trimmed, fitted).forEach(Bitmap::recycle)
        // A new name per upload, so anything showing the old logo reloads.
        val file = File(folder, "$channelId-${System.currentTimeMillis()}.$STANDARD.$extension")
        file.writeBytes(encoded)
        return file.toURI().toString()
    }

    /** The stored logo behind [uri], or null when it is not one of ours. */
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

    /** [bitmap] without its clear margins ([LogoMargins]); an opaque one stays whole. */
    private fun trim(bitmap: Bitmap): Bitmap {
        if (!bitmap.hasAlpha()) return bitmap
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height).also { bitmap.getPixels(it, 0, width, 0, 0, width, height) }
        val (left, top, right, bottom) = LogoMargins.bounds(pixels, width, height) ?: return bitmap
        return Bitmap.createBitmap(bitmap, left, top, right - left + 1, bottom - top + 1)
    }

    /**
     * [bitmap] scaled down to fit the standard box, never up: a small logo stays sharp and the
     * screen scales it. Large steps go by halves first, since one bilinear step over a large
     * factor breaks thin strokes up.
     */
    private fun fit(bitmap: Bitmap): Bitmap {
        val scale = minOf(1f, BOX_WIDTH_PX.toFloat() / bitmap.width, BOX_HEIGHT_PX.toFloat() / bitmap.height)
        if (scale >= 1f) return bitmap
        val width = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        var current = bitmap
        while (current.width / 2 >= width && current.height / 2 >= height) {
            val half = current.scale(current.width / 2, current.height / 2)
            if (current !== bitmap) current.recycle()
            current = half
        }
        val fitted = current.scale(width, height)
        if (current !== bitmap && current !== fitted) current.recycle()
        return fitted
    }

    /** The logo's bytes and file extension, lossless either way. */
    private fun encode(bitmap: Bitmap): Pair<ByteArray, String> {
        val out = ByteArrayOutputStream()
        val extension = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                if (!bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)) throw IOException("Could not encode the logo")
                "webp"
            }
            // On Android 10, WEBP at 100 is lossless; before it, WEBP is always lossy.
            Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> {
                @Suppress("DEPRECATION")
                if (!bitmap.compress(Bitmap.CompressFormat.WEBP, 100, out)) throw IOException("Could not encode the logo")
                "webp"
            }
            else -> {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw IOException("Could not encode the logo")
                "png"
            }
        }
        return out.toByteArray() to extension
    }

    companion object {
        private const val FOLDER = "logos"

        /** The standard box: picons' 5:3 at 400 × 240, ample for a logo on a 4K screen's OSD. */
        const val BOX_WIDTH_PX = 400
        const val BOX_HEIGHT_PX = 240

        /** Uploads are decoded no larger than about this a side before trimming. */
        private const val DECODE_MAX_PX = 1600

        /** Marks a file made by this standard; logos stored before it are made again ([isStandard]). */
        private const val STANDARD = "s1"

        /** Uploads above this are refused before decoding. */
        const val MAX_UPLOAD_BYTES = 4 * 1024 * 1024

        /** True when [uri] names a logo already made to the standard. */
        fun isStandard(uri: String): Boolean = uri.substringAfterLast('/').contains(".$STANDARD.")
    }
}
