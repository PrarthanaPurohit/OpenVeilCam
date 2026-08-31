package com.openveil.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember

/**
 * Screens in the capture -> publish flow.
 *
 * Deliberately a sealed hierarchy rather than string routes: the compiler then guarantees
 * every navigation target is handled, and arguments are typed instead of stringly-encoded.
 */
sealed interface Screen {
    data object Home : Screen
    data object Camera : Screen

    /** Review the capture. [photoId] keys into the session's in-flight photo. */
    data class Review(val photoId: String) : Screen
    data class Publishing(val photoId: String) : Screen
    data class Success(val photoId: String) : Screen
    data class PhotoDetails(val photoId: String) : Screen
}

/**
 * Minimal back-stack navigator.
 *
 * This flow is six screens and almost entirely linear, so a full navigation library would
 * add a dependency and a version-compatibility surface without buying anything. The API
 * here is intentionally the subset androidx.navigation offers, so swapping it in later --
 * when History, Discover and Profile arrive and tabs actually matter -- is mechanical.
 */
@Stable
class Navigator(initial: Screen = Screen.Home) {
    private val backStack = mutableStateListOf(initial)

    val current: Screen get() = backStack.last()

    val canGoBack: Boolean get() = backStack.size > 1

    fun navigateTo(screen: Screen) {
        backStack.add(screen)
    }

    /**
     * Replace the current screen. Used for one-way transitions -- once publishing starts
     * there is no meaningful "back" to the review screen, and once it succeeds there is no
     * going back into the progress view.
     */
    fun replaceWith(screen: Screen) {
        if (backStack.isNotEmpty()) backStack.removeAt(backStack.lastIndex)
        backStack.add(screen)
    }

    fun back(): Boolean {
        if (!canGoBack) return false
        backStack.removeAt(backStack.lastIndex)
        return true
    }

    /** Unwind to Home, discarding the whole capture flow. */
    fun popToHome() {
        while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }
}

/** Remembers a [Navigator] and its back stack across recompositions. */
@Composable
fun rememberNavigator(initial: Screen = Screen.Home): Navigator = remember { Navigator(initial) }

/**
 * Hardware/system back. On Android this wires to the predictive-back dispatcher; other
 * platforms have no equivalent gesture and supply a no-op.
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
