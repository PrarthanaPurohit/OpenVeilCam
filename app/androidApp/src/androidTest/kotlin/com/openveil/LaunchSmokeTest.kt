package com.openveil

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app starts and reaches the Home screen with the real object graph -- Keystore,
 * secp256k1 JNI, C2PA JNI, Ktor -- all loaded on an actual Android runtime. A missing or
 * mis-packaged native library shows up here as a crash on launch, which no host test can
 * see.
 */
@RunWith(AndroidJUnit4::class)
class LaunchSmokeTest {

    @Test
    fun main_activity_launches_and_resumes() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            scenario.recreate()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }
}
