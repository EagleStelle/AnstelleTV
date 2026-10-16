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
    api(projects.core.player)

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(libs.kotlinx.coroutines.test)

    // The backup round trip runs against a real SQLite on a device.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.kotlin.test)
    androidTestRuntimeOnly(libs.slf4j.nop)
}
