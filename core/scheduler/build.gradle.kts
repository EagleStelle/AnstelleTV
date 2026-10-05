// Pure Kotlin/JVM schedule engine. No Android dependencies, so it can be tested
// and benchmarked on the JVM.
plugins {
    alias(libs.plugins.anstelle.jvm.library)
}

dependencies {
    api(projects.core.model)
}
