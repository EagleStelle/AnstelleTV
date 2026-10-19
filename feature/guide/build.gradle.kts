plugins {
    alias(libs.plugins.anstelle.android.feature)
}

dependencies {
    // The preview window shows the channel player.
    implementation(libs.androidx.media3.ui)
    // BackHandler closes the program actions.
    implementation(libs.androidx.activity.compose)
    implementation(libs.coil.compose)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockito.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
