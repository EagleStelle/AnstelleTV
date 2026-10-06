plugins {
    alias(libs.plugins.anstelle.android.library)
    alias(libs.plugins.anstelle.hilt)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(projects.core.model)
    api(projects.core.common)
    api(projects.core.database)
    implementation(projects.core.scheduler)
    implementation(projects.core.jellyfin)
    implementation(projects.core.livetv)

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(libs.kotlinx.coroutines.test)
}
