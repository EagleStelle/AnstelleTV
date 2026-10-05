package tv.anstelle.buildlogic.webui

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.zip.GZIPOutputStream

/**
 * Copies the built SPA into the APK assets folder.
 *
 * Text files are stored pre-gzipped (`app.js` -> `app.js.gzip`) so the embedded server
 * can send them with `Content-Encoding: gzip` without compressing on the device. The
 * suffix is `.gzip`, not `.gz`: the Android asset packager unpacks `.gz` files and drops
 * the extension. The app stores `.gzip` assets uncompressed (`noCompress`).
 * Small or binary files are copied as-is. An `asset-index.txt` lists every served
 * path so the server never has to walk the asset tree at runtime.
 */
abstract class PackWebUiTask : DefaultTask() {
    @get:InputFiles
    @get:SkipWhenEmpty
    @get:IgnoreEmptyDirectories
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun pack() {
        val source = inputDir.get().asFile
        val target = outputDir.get().asFile
        target.deleteRecursively()
        target.mkdirs()

        val index = mutableListOf<String>()
        source.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { file ->
            val relative = file.relativeTo(source).invariantSeparatorsPath
            val destination = File(target, relative)
            destination.parentFile.mkdirs()
            if (file.extension.lowercase() in GZIP_EXTENSIONS && file.length() >= GZIP_MIN_BYTES) {
                GZIPOutputStream(File(target, "$relative$GZIP_SUFFIX").outputStream().buffered()).use { out ->
                    file.inputStream().buffered().use { it.copyTo(out) }
                }
                index += "$relative\tgz"
            } else {
                file.copyTo(destination, overwrite = true)
                index += "$relative\traw"
            }
        }
        File(target, INDEX_FILE).writeText(index.joinToString("\n", postfix = "\n"))
        logger.lifecycle("Packed ${index.size} web UI files into $target")
    }

    companion object {
        const val INDEX_FILE = "asset-index.txt"
        const val GZIP_SUFFIX = ".gzip"
        private const val GZIP_MIN_BYTES = 1024L
        private val GZIP_EXTENSIONS = setOf("html", "js", "mjs", "css", "svg", "json", "txt", "map", "webmanifest")
    }
}
