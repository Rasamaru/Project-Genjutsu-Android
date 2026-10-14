plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.projectgenjutsu.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.projectgenjutsu.android"
        minSdk = 29 // Android 10
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-android-prototype"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Встроенные OCR-модели: текст и координаты обрабатываются на устройстве.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    // Перевод на устройстве, модель языка скачивается отдельно после согласия.
    implementation("com.google.mlkit:translate:17.0.3")
}
