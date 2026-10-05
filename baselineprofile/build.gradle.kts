plugins {
    alias(libs.plugins.anstelle.android.test)
    alias(libs.plugins.baselineprofile)
}

baselineProfile {
    // Generated on a connected device (a Fire TV Stick in Phase 11, or an AVD).
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
