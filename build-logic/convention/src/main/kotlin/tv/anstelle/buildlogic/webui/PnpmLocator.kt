package tv.anstelle.buildlogic.webui

import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * Finds a working pnpm launcher: `pnpm` on PATH first, then `corepack pnpm`.
 * Returns an empty list when neither works. Runs as a ValueSource so the result
 * is configuration-cache friendly.
 */
abstract class PnpmLocator : ValueSource<List<String>, ValueSourceParameters.None> {
    @get:Inject
    abstract val execOperations: ExecOperations

    override fun obtain(): List<String> {
        val candidates = listOf(listOf("pnpm"), listOf("corepack", "pnpm"))
        for (candidate in candidates) {
            val command = shellPrefix() + candidate
            val result = runCatching {
                execOperations.exec {
                    commandLine(command + "--version")
                    environment("COREPACK_ENABLE_DOWNLOAD_PROMPT", "0")
                    isIgnoreExitValue = true
                    standardOutput = ByteArrayOutputStream()
                    errorOutput = ByteArrayOutputStream()
                }.exitValue
            }.getOrDefault(-1)
            if (result == 0) return command
        }
        return emptyList()
    }

    companion object {
        /** On Windows, pnpm and corepack are .cmd shims and need cmd.exe. */
        fun shellPrefix(): List<String> =
            if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                listOf("cmd", "/c")
            } else {
                emptyList()
            }
    }
}
