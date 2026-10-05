import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import tv.anstelle.buildlogic.configureKotlinJvm
import tv.anstelle.buildlogic.libs
import tv.anstelle.buildlogic.library

/** Pure Kotlin/JVM module with no Android dependencies. */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        configureKotlinJvm()
        dependencies {
            add("testImplementation", libs.library("junit"))
            add("testImplementation", libs.library("kotlin-test"))
        }
    }
}
