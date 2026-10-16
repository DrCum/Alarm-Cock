import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.taskalarm.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.taskalarm.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.3.0"
    }
    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        getByName("release") { isMinifyEnabled = false }
    }
}
kotlin { jvmToolchain(17) }

tasks.withType<Test>().configureEach {
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
    // Robolectric downloads its SDK in the test JVM; preserve the managed HTTP proxy there too.
    System.getenv("HTTPS_PROXY")?.let { value ->
        val proxy = URI(value)
        if (proxy.host != null) for (scheme in listOf("http", "https")) {
            systemProperty("$scheme.proxyHost", proxy.host)
            systemProperty("$scheme.proxyPort", if (proxy.port > 0) proxy.port else 80)
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.compose.ui:ui:1.10.5")
    implementation("androidx.compose.foundation:foundation:1.10.5")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.camera:camera-camera2:1.6.0")
    implementation("androidx.camera:camera-lifecycle:1.6.0")
    implementation("androidx.camera:camera-view:1.6.0")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
