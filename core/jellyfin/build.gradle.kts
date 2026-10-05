plugins {
    alias(libs.plugins.anstelle.android.library)
    alias(libs.plugins.anstelle.hilt)
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.database)

    // jellyfin-core uses the OkHttp-based API client, so the app keeps a single OkHttpClient.
    api(libs.jellyfin.sdk.core)
    api(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
}
