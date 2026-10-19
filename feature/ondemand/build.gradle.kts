plugins {
    alias(libs.plugins.anstelle.android.feature)
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.coil.compose)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
