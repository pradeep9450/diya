import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.diya.assistant"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.diya.assistant"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "2.0"
    }

    // One fixed key for every build (Android Studio or GitHub), so new versions
    // install over the old one and keep your settings. Fine for a personal app.
    signingConfigs {
        create("diya") {
            storeFile = file("diya-release.jks")
            storePassword = "diyaapp123"
            keyAlias = "diya"
            keyPassword = "diyaapp123"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("diya")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("diya")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }

    // Personal app: don't let style warnings block the build.
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Offline speech engine used only for the "Hey Diya" wake word (free, open source)
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
}
