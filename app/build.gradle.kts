plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.yussy.folior"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.yussy.folior"
        minSdk = 31
        targetSdk = 34
        versionCode = 54
        versionName = "0.20.3"
    }

    signingConfigs {
        create("release") {
            storeFile = file("../folior.keystore")
            storePassword = "folior2026"
            keyAlias = "folior"
            keyPassword = "folior2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
}
