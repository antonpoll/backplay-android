plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.dualspike"
    compileSdk = 33

    defaultConfig {
        applicationId = "com.example.dualspike"
        minSdk = 26
        targetSdk = 33
        versionCode = 3
        versionName = "0.3"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
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
    implementation("androidx.core:core-ktx:1.7.0")
    implementation("androidx.appcompat:appcompat:1.4.1")
    implementation("com.google.android.material:material:1.5.0")
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Скачивание YouTube-видео по ссылке (для локального трека) —
    // вне официального API, серая зона ToS YouTube, осознанно для личного использования.
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.4")
    implementation("com.squareup.okhttp3:okhttp:4.10.0")
}

// rhino-1.8.1 (транзитивная зависимость NewPipeExtractor) содержит module-info.class,
// на котором D8 в AGP 7.3.1 падает с NullPointerException. 1.7.15 без него, API совместим.
configurations.all {
    resolutionStrategy {
        force("org.mozilla:rhino:1.7.15")
    }
}
