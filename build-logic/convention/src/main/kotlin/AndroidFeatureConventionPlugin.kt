import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import tv.anstelle.buildlogic.libs
import tv.anstelle.buildlogic.library

/** A feature module: Android library + Compose (TV and phone) + Hilt ViewModels. */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("anstelle.android.library")
        pluginManager.apply("anstelle.android.compose")
        pluginManager.apply("anstelle.hilt")

        dependencies {
            add("implementation", project(":core:model"))
            add("implementation", project(":core:common"))
            add("implementation", project(":core:theme"))
            add("implementation", project(":core:data"))

            add("implementation", libs.library("androidx-compose-material3"))
            add("implementation", libs.library("androidx-tv-material"))
            add("implementation", libs.library("androidx-compose-material-icons-extended"))
            add("implementation", libs.library("androidx-lifecycle-runtime-compose"))
            add("implementation", libs.library("androidx-lifecycle-viewmodel-compose"))
            add("implementation", libs.library("androidx-hilt-viewmodel-compose"))
            add("implementation", libs.library("kotlinx-coroutines-android"))
        }
    }
}
