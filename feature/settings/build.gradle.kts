plugins {
    alias(libs.plugins.anstelle.android.feature)
}

dependencies {
    // BackHandler steps back through the add-server flow.
    implementation(libs.androidx.activity.compose)
}
