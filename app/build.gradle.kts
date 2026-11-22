plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.acme.privacyshield"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.acme.privacyshield"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        viewBinding = true
        // Used only to gate the QA-only shield simulator below (BuildConfig.DEBUG).
        buildConfig = true
    }

    buildTypes {
        debug {
            // No debug-only behaviours that could leak frames: everything is in-memory in all builds.
        }
        release {
            // Left off deliberately: the release runtime has not been exercised on a device in CI.
            // Turn it on once consumer-proguard works for ML Kit / CameraX in your app.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // CameraX: ImageAnalysis only. No Preview use case is ever bound (privacy by design).
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")

    // Bundled ML Kit face detection: model ships inside the APK,
    // works on devices without Google Play Services, and needs no network.
    implementation("com.google.mlkit:face-detection:16.1.7")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
}
