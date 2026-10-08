plugins {
    alias(libs.plugins.anstelle.android.library)
    alias(libs.plugins.anstelle.android.compose)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(projects.core.model)
    api(libs.androidx.compose.material3)
    api(libs.androidx.tv.material)
    implementation(libs.kotlinx.serialization.json)
    // Back closes the program actions.
    implementation(libs.androidx.activity.compose)
    // Backdrops and title logos from the server.
    implementation(libs.coil.compose)
}
