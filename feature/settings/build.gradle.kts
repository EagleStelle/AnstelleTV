plugins {
    alias(libs.plugins.anstelle.android.feature)
}

dependencies {
    // BackHandler steps back through the add-server flow.
    implementation(libs.androidx.activity.compose)
    // Turning the dashboard off asks the same keep-or-delete question there and here.
    implementation(projects.feature.dashboard)
    // The subtitle style's sample is drawn by the players' own subtitle view.
    implementation(libs.androidx.media3.ui)
}
