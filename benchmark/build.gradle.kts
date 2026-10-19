plugins {
    alias(libs.plugins.anstelle.android.test)
    alias(libs.plugins.baselineprofile)
}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(projects.core.common)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
