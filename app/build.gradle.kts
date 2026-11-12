plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.souly.parental.shield"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.souly.parental.shield"
        minSdk = 26
        targetSdk = 35
        versionCode = 42
        versionName = "4.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("soulyRelease") {
            val ksFile = file("souly-release-key.jks")
            if (ksFile.exists()) {
                storeFile = ksFile
                storePassword = System.getenv("SOULY_KEYSTORE_PASSWORD") ?: "SoulyShield2026"
                keyAlias = System.getenv("SOULY_KEY_ALIAS") ?: "souly_shield_key"
                keyPassword = System.getenv("SOULY_KEY_PASSWORD") ?: "SoulyShield2026"
            }
        }
    }

    flavorDimensions += "osTier"
    productFlavors {
        create("universalApi26To35") {
            dimension = "osTier"
            minSdk = 26
            targetSdk = 35
            versionCode = 4200
            versionNameSuffix = "-universal"
        }
        create("legacyApi26To29") {
            dimension = "osTier"
            minSdk = 26
            targetSdk = 29
            versionCode = 4201
            versionNameSuffix = "-android8to10"
        }
        create("modernApi30To33") {
            dimension = "osTier"
            minSdk = 30
            targetSdk = 33
            versionCode = 4202
            versionNameSuffix = "-android11to13"
        }
        create("nextGenApi34To35") {
            dimension = "osTier"
            minSdk = 34
            targetSdk = 35
            versionCode = 4203
            versionNameSuffix = "-android14to15"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val ksFile = file("souly-release-key.jks")
            signingConfig = if (ksFile.exists()) {
                signingConfigs.getByName("soulyRelease")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ""
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=androidx.camera.core.ExperimentalGetImage")
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-service:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.work:work-runtime-ktx:2.9.0")

    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")

    implementation("com.google.android.gms:play-services-location:21.3.0")

    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
