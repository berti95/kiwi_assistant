import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Resolves a build-time secret. Lookup order:
 *   1. Environment variable (CI uses this).
 *   2. local.properties at the Android project root (developer machines).
 *   3. The provided default (empty string), so the project still builds
 *      without secrets — calls into the backend / wake word will simply
 *      fail at runtime with a clear error.
 */
val localProperties: Properties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun secret(name: String, default: String = ""): String =
    System.getenv(name)
        ?: localProperties.getProperty(name)
        ?: default

android {
    namespace = "com.kiwi.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kiwi.assistant"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "CLOUD_RUN_URL", "\"${secret("CLOUD_RUN_URL")}\"")
        buildConfigField("String", "KIWI_API_KEY", "\"${secret("KIWI_API_KEY")}\"")
        buildConfigField(
            "String",
            "PICOVOICE_ACCESS_KEY",
            "\"${secret("PICOVOICE_ACCESS_KEY")}\"",
        )
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
