plugins {
    id("com.android.application")
    // No org.jetbrains.kotlin.android — AGP 9 compiles Kotlin via built-in support.
}

android {
    namespace = "com.arum.scalecapture"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.arum.scalecapture"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Kotlin jvmTarget defaults to compileOptions.targetCompatibility (17) under
    // AGP built-in Kotlin, so no explicit kotlin {} block is required.
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
