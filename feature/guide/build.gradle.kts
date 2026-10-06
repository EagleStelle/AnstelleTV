plugins {
    alias(libs.plugins.anstelle.android.feature)
}

dependencies {
    // The preview window shows the channel player.
    implementation(libs.androidx.media3.ui)
    // BackHandler closes the program actions.
    implementation(libs.androidx.activity.compose)
    implementation(libs.coil.compose)
}
