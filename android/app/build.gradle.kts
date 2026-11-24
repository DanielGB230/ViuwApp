plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.viuwapp.stream"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.viuwapp.stream"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        buildConfigField(
            "String",
            "SIGNALING_BASE_URL",
            "\"https://interracial-algorithm-thick-blowing.trycloudflare.com\""
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    // Core Android & Lifecycle
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    // Jetpack Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // Coroutines & WebRTC & SignalR
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.webrtc.android)
    implementation(libs.microsoft.signalr)
    implementation(libs.slf4j.android)

    // Firebase (Auth y Messaging vÃ­a BoM)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.messaging)

    // Jetpack Credential Manager moderno + Google ID
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)

    // Jetpack DataStore Preferences
    implementation(libs.androidx.datastore.preferences)

    // OkHttp (cliente HTTP moderno, sin dependencias redundantes)
    implementation(libs.okhttp)
}
