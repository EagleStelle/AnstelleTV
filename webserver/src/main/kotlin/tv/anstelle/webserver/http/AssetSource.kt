package tv.anstelle.webserver.http

import android.content.res.AssetManager
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * Read access to the packed web UI. The build stores text files gzipped and lists every
 * served path in `asset-index.txt` (`path<TAB>gz|raw`), so lookups never scan the APK.
 */
interface AssetSource {
    /** Served path (e.g. `app/immutable/app.js`) to whether it is stored gzipped. Empty if the UI is not built. */
    val index: Map<String, Boolean>

    /** Opens the stored file; for gzipped entries this is the `.gzip` file. */
    fun open(path: String, gzipped: Boolean): InputStream
}

class AndroidAssetSource(private val assets: AssetManager) : AssetSource {
    override val index: Map<String, Boolean> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { loadIndex() }

    override fun open(path: String, gzipped: Boolean): InputStream =
        assets.open(ROOT + path + if (gzipped) GZIP_SUFFIX else "", AssetManager.ACCESS_STREAMING)

    private fun loadIndex(): Map<String, Boolean> = try {
        assets.open(ROOT + INDEX_FILE).bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() }.associate { line ->
                val tab = line.indexOf('\t')
                line.substring(0, tab) to (line.substring(tab + 1) == "gz")
            }
        }
    } catch (_: FileNotFoundException) {
        emptyMap()
    }

    private companion object {
        const val ROOT = "webui/"
        const val INDEX_FILE = "asset-index.txt"

        /** Not `.gz`: the Android asset packager unpacks `.gz` files and drops the extension. */
        const val GZIP_SUFFIX = ".gzip"
    }
}
