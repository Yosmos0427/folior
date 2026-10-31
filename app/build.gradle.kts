import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 签名密钥不入库：路径与口令从 local.properties（已被 .gitignore 忽略）或环境变量读取。
// 公开仓库中不含任何密钥或口令；克隆者自己的 keystore 请写入 local.properties。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingProp(key: String, env: String, fallback: String = ""): String =
    localProps.getProperty(key) ?: System.getenv(env) ?: fallback

val releaseKeystore = rootProject.file(
    signingProp("folior.storeFile", "FOLIOR_STORE_FILE", "folior.keystore")
)
val releaseStorePassword = signingProp("folior.storePassword", "FOLIOR_STORE_PASSWORD")
val canSignRelease = releaseKeystore.exists() && releaseStorePassword.isNotEmpty()

android {
    namespace = "com.yussy.folior"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.yussy.folior"
        minSdk = 31
        targetSdk = 34
        versionCode = 71
        versionName = "0.30.0"
    }

    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseStorePassword
                keyAlias = signingProp("folior.keyAlias", "FOLIOR_KEY_ALIAS", "folior")
                keyPassword = signingProp("folior.keyPassword", "FOLIOR_KEY_PASSWORD", releaseStorePassword)
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (canSignRelease) {
                signingConfig = signingConfigs.getByName("release")
            }
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
