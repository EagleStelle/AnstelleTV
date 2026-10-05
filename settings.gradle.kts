pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Provisions JDK 17 for the Gradle daemon and toolchains (see gradle/gradle-daemon-jvm.properties).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AnstelleTV"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":app")

include(":core:model")
include(":core:common")
include(":core:database")
include(":core:scheduler")
include(":core:jellyfin")
include(":core:livetv")
include(":core:player")
include(":core:theme")
include(":core:data")

include(":feature:guide")
include(":feature:player")
include(":feature:ondemand")
include(":feature:profiles")
include(":feature:settings")
include(":feature:dashboard")

include(":webserver")

include(":baselineprofile")
include(":benchmark")
