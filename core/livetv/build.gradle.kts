plugins {
    alias(libs.plugins.anstelle.jvm.library)
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)
    // Android ships an XmlPullParser implementation; the JVM tests use kxml2.
    compileOnly(libs.xmlpull)
    testImplementation(libs.kxml2)
    testImplementation(libs.kotlinx.coroutines.test)
}
