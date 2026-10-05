import androidx.room.gradle.RoomExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import tv.anstelle.buildlogic.libs
import tv.anstelle.buildlogic.library

class AndroidRoomConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("androidx.room")
        pluginManager.apply("com.google.devtools.ksp")

        extensions.configure<RoomExtension> {
            // Exported schemas are checked in and drive migration tests.
            schemaDirectory("$projectDir/schemas")
        }

        dependencies {
            // api: modules that inject the database see RoomDatabase as its supertype.
            add("api", libs.library("androidx-room-runtime"))
            add("implementation", libs.library("androidx-room-ktx"))
            add("ksp", libs.library("androidx-room-compiler"))
            add("androidTestImplementation", libs.library("androidx-room-testing"))
        }
    }
}
