import com.android.build.api.dsl.TestExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import tv.anstelle.buildlogic.configureKotlinAndroid
import tv.anstelle.buildlogic.libs
import tv.anstelle.buildlogic.version

/** `com.android.test` modules (baseline profile generator, macrobenchmarks) that target :app. */
class AndroidTestConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.test")
        extensions.configure<TestExtension> {
            configureKotlinAndroid(this)
            namespace = "tv.anstelle." + path.removePrefix(":").replace(':', '.')
            // Baseline profile capture and Macrobenchmark frame metrics need API 28+.
            // Fire TV Stick 4K runs Fire OS 7 (API 28).
            defaultConfig.minSdk = MIN_SDK
            defaultConfig.targetSdk = libs.version("targetSdk").toInt()
            defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            targetProjectPath = ":app"
        }
    }

    private companion object {
        const val MIN_SDK = 28
    }
}
