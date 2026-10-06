plugins {
    alias(libs.plugins.anstelle.android.feature)
}

dependencies {
    implementation(projects.core.player)
    implementation(libs.androidx.media3.ui)
    // BackHandler closes the controls panel.
    implementation(libs.androidx.activity.compose)
}
