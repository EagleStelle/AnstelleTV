package tv.anstelle.buildlogic.webui

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectories
import org.gradle.api.tasks.OutputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

/**
 * Runs one pnpm command in the web UI project.
 *
 * When pnpm cannot be found the task fails if [required] is true (release builds)
 * and otherwise logs a warning and does nothing (debug builds).
 */
abstract class PnpmTask : DefaultTask() {
    @get:Inject
    abstract val execOperations: ExecOperations

    @get:Internal
    abstract val projectDir: DirectoryProperty

    @get:Input
    abstract val pnpmCommand: ListProperty<String>

    @get:Input
    abstract val arguments: ListProperty<String>

    @get:Input
    abstract val required: Property<Boolean>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /** Marker files written by the command, e.g. `node_modules/.modules.yaml`. */
    @get:OutputFiles
    abstract val outputMarkers: ConfigurableFileCollection

    /** Directories written by the command, e.g. `build/`. */
    @get:OutputDirectories
    abstract val outputDirs: ConfigurableFileCollection

    @TaskAction
    fun run() {
        val launcher = pnpmCommand.get()
        if (launcher.isEmpty()) {
            if (required.get()) {
                throw GradleException(
                    "pnpm is required to build the web UI for release builds. " +
                        "Install pnpm or enable corepack (Node.js 22+).",
                )
            }
            logger.warn("pnpm not found: skipping '${arguments.get().joinToString(" ")}' for the web UI (debug build).")
            return
        }
        execOperations.exec {
            workingDir = projectDir.get().asFile
            environment("COREPACK_ENABLE_DOWNLOAD_PROMPT", "0")
            environment("CI", "true")
            commandLine(launcher + arguments.get())
        }
    }
}
