import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.of
import org.gradle.kotlin.dsl.register
import tv.anstelle.buildlogic.webui.PackWebUiTask
import tv.anstelle.buildlogic.webui.PnpmLocator
import tv.anstelle.buildlogic.webui.PnpmTask

/**
 * Builds the SvelteKit web UI in `/webui` (`pnpm install && pnpm build`) and copies
 * the output into `src/main/assets/webui` of the application module.
 *
 * Release-type builds (release, benchmark, baseline profile, bundle) fail without pnpm.
 * Debug builds skip the web UI with a warning when pnpm is missing.
 */
class WebUiConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        val webUiDir = rootProject.layout.projectDirectory.dir("webui")
        val pnpm = providers.of(PnpmLocator::class) {}
        val releaseRequested = gradle.startParameter.taskNames.any { name ->
            RELEASE_MARKERS.any { name.contains(it, ignoreCase = true) }
        }

        val install = tasks.register<PnpmTask>("webUiInstall") {
            group = "web ui"
            description = "Installs web UI dependencies with pnpm."
            projectDir.set(webUiDir)
            pnpmCommand.set(pnpm)
            arguments.set(listOf("install", "--frozen-lockfile", "--prefer-offline"))
            required.set(releaseRequested)
            sources.from(webUiDir.file("package.json"), webUiDir.file("pnpm-lock.yaml"))
            outputMarkers.from(webUiDir.file("node_modules/.modules.yaml"))
        }

        val build = tasks.register<PnpmTask>("webUiBuild") {
            group = "web ui"
            description = "Builds the web UI single-page app with pnpm."
            dependsOn(install)
            projectDir.set(webUiDir)
            pnpmCommand.set(pnpm)
            arguments.set(listOf("run", "build"))
            required.set(releaseRequested)
            sources.from(
                webUiDir.dir("src"),
                webUiDir.dir("static"),
                webUiDir.file("package.json"),
                webUiDir.file("pnpm-lock.yaml"),
                webUiDir.file("vite.config.ts"),
                webUiDir.file("tsconfig.json"),
            )
            outputDirs.from(webUiDir.dir("build"))
        }

        val pack = tasks.register<PackWebUiTask>("copyWebUi") {
            group = "web ui"
            description = "Copies the built web UI (pre-gzipped) into src/main/assets/webui."
            dependsOn(build)
            inputDir.set(webUiDir.dir("build"))
            outputDir.set(layout.projectDirectory.dir("src/main/assets/webui"))
        }

        // Every variant's pre-build step waits for the web UI assets.
        tasks.matching { PRE_BUILD.matches(it.name) }.configureEach { dependsOn(pack) }
    }

    private companion object {
        val RELEASE_MARKERS = listOf("release", "bundle", "benchmark", "baselineprofile")
        val PRE_BUILD = Regex("pre\\w*Build")
    }
}
