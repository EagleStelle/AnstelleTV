plugins {
    alias(libs.plugins.anstelle.android.library)
    alias(libs.plugins.anstelle.android.room)
}

dependencies {
    api(projects.core.model)
    implementation(libs.kotlinx.coroutines.android)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.kotlin.test)
}
