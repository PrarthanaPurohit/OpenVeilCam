rootProject.name = "OpenVeil"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // c2pa-android is published to JitPack, not Maven Central.
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com\\.github\\.contentauth.*") }
        }
    }
}

// Module boundaries enforce the layering the spec asks for:
//   :shared     domain + pipeline. No Compose, no Android UI types.
//   :composeApp Compose Multiplatform UI. Depends on :shared.
//   :androidApp thin Android host. Under AGP 9 the application module cannot be a KMP
//               module, so this exists purely to package :composeApp into an APK.
include(":shared")
include(":composeApp")
include(":androidApp")
