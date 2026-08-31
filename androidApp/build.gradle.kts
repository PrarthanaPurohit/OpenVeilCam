import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Thin Android host. Under AGP 9 an application module cannot also be a Kotlin
// Multiplatform module, so all shared UI lives in :composeApp and this module only
// packages it into an APK. Keep it empty of logic.
plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
}

kotlin {
    compilerOptions { jvmTarget = JvmTarget.JVM_17 }
}

dependencies {
    implementation(project(":composeApp"))
    implementation(libs.androidx.activity.compose)
}

android {
    namespace = "com.openveil"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.openveil"
        minSdk = libs.versions.androidMinSdk.get().toInt()
        targetSdk = libs.versions.androidTargetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    // One APK per ABI instead of one carrying all four.
    //
    // The C2PA native library is 13-26 MB *per architecture*, so a universal APK comes to
    // roughly 150 MB -- above the Play Store's 100 MB APK ceiling, and an unreasonable
    // download for someone on a slow or metered connection, which is exactly the
    // situation this app is meant for. Splitting brings an arm64 build down to a third of
    // that, and every phone shipped in the last several years is arm64-v8a.
    //
    // x86 variants are kept because the emulator needs them during development; they cost
    // nothing now that each ABI is packaged separately.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            // No universal APK: its only purpose would be to be the large file above.
            isUniversalApk = false
        }
    }
}

// Distinct versionCode per ABI, which Google Play requires when several APKs serve the
// same release, and which also stops a 32-bit build being offered to a 64-bit device.
val abiVersionOffsets = mapOf("armeabi-v7a" to 1, "x86" to 2, "arm64-v8a" to 3, "x86_64" to 4)

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters.find { it.filterType.name == "ABI" }?.identifier
            val offset = abiVersionOffsets[abi] ?: 0
            output.versionCode.set(
                (android.defaultConfig.versionCode ?: 1) * 10 + offset
            )
        }
    }
}
