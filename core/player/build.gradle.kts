plugins {
    alias(libs.plugins.anstelle.android.library)
    alias(libs.plugins.anstelle.hilt)
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.database)
    implementation(projects.core.jellyfin)

    api(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.datasource.okhttp)
    // Software audio decoders (AC3, E-AC3, DTS, TrueHD) used only when the device has no decoder.
    implementation(libs.jellyfin.media3.ffmpeg)
    implementation(libs.kotlinx.coroutines.android)

    // FfmpegDecoderTest decodes sample files on a device.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
