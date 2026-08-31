import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.serialization)
}

// iOS targets only exist on a macOS host. Declaring them unconditionally breaks every
// Gradle invocation on Windows/Linux. iosMain sources are committed regardless -- off a
// Mac they simply belong to no active source set.
val isMacHost = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)

kotlin {
    android {
        namespace = "com.openveil.shared"
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()

        // Test compilations are opt-in under the AGP KMP library plugin. Without this,
        // commonTest is silently never compiled -- Gradle only warns that the source set
        // "was configured but not added to any Kotlin compilation".
        withHostTest { }

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
            implementation(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.secp256k1)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.ktor.client.websockets)
        }
        androidMain.dependencies {
            implementation(libs.secp256k1.jni.android)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.c2pa.android)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.core)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.secp256k1.jni.jvm)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// The live integration test talks to real Blossom servers and relays, so it is opt-in.
// Gradle does not forward -D properties to the test JVM automatically.
tasks.withType<Test>().configureEach {
    systemProperty(
        "openveil.liveIntegration",
        providers.systemProperty("openveil.liveIntegration").getOrElse("false"),
    )
}
