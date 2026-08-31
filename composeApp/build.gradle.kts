import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

val isMacHost = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)

kotlin {
    android {
        namespace = "com.openveil.ui"
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()

        // Required for Compose Multiplatform resources (the bundled fonts) to be packaged
        // out of this KMP library and into the APK. Without it the assets are silently
        // dropped and every Font() call falls back to the system face -- which renders
        // icon codepoints as tofu.
        androidResources {
            enable = true
        }

        compilerOptions { jvmTarget = JvmTarget.JVM_17 }
    }

    if (isMacHost) {
        iosX64()
        iosArm64()
        iosSimulatorArm64()
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.camera.camera2)
            implementation(libs.androidx.camera.lifecycle)
            implementation(libs.androidx.camera.view)
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "com.openveil.ui.resources"
}
