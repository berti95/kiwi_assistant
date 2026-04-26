import java.util.Base64
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

/**
 * Builds a release signing config from env vars when they are set (CI),
 * decoding the base64 keystore into the build dir on the fly. When the
 * env vars are missing the signingConfig is null and release builds fall
 * back to the debug signing config — so local `./gradlew assembleRelease`
 * keeps working without anyone having to copy the keystore around.
 */
val releaseSigningConfig = run {
    val keystoreB64 = System.getenv("ANDROID_KEYSTORE_B64")
    val keystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
    val keyAlias = System.getenv("ANDROID_KEY_ALIAS")
    val keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
    if (keystoreB64.isNullOrBlank() || keystorePassword.isNullOrBlank() ||
        keyAlias.isNullOrBlank() || keyPassword.isNullOrBlank()
    ) {
        null
    } else {
        // Strip any whitespace the GitHub Secret may have picked up from the
        // clipboard (PowerShell `clip` notoriously appends \r\n) — the strict
        // base64 decoder rejects even a trailing newline.
        val cleaned = keystoreB64.replace("\\s".toRegex(), "")
        val decoded = Base64.getDecoder().decode(cleaned)
        val target = layout.buildDirectory.file("release-keystore.jks").get().asFile
        target.parentFile.mkdirs()
        target.writeBytes(decoded)
        mapOf(
            "storeFile" to target,
            "storePassword" to keystorePassword,
            "keyAlias" to keyAlias,
            "keyPassword" to keyPassword,
        )
    }
}

android {
    namespace = "com.kiwi.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kiwi.assistant"
        minSdk = 33
        targetSdk = 35
        versionCode = (System.getenv("KIWI_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("KIWI_VERSION_NAME") ?: "0.1.0-dev"

        buildConfigField("String", "CLOUD_RUN_URL", "\"${secret("CLOUD_RUN_URL")}\"")
        buildConfigField("String", "KIWI_API_KEY", "\"${secret("KIWI_API_KEY")}\"")
        buildConfigField(
            "String",
            "PICOVOICE_ACCESS_KEY",
            "\"${secret("PICOVOICE_ACCESS_KEY")}\"",
        )
    }

    signingConfigs {
        if (releaseSigningConfig != null) {
            create("release") {
                @Suppress("UNCHECKED_CAST")
                storeFile = releaseSigningConfig["storeFile"] as java.io.File
                storePassword = releaseSigningConfig["storePassword"] as String
                keyAlias = releaseSigningConfig["keyAlias"] as String
                keyPassword = releaseSigningConfig["keyPassword"] as String
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
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
    // Just for Icons.Default.Close on the close-conversation button.
    // Core (≈100 KB), not extended (multi-MB), keeps the APK small.
    implementation(libs.androidx.compose.material.icons.core)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.android.vad.silero)
    // The Silero VAD AAR pulls onnxruntime-android in transitively but
    // declares it as runtime scope, so the wake-word code that calls
    // OrtEnvironment / OnnxTensor directly needs it as a real
    // compile-time dependency too.
    implementation(libs.onnxruntime.android)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
