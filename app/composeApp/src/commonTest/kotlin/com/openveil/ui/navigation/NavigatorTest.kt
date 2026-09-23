package com.openveil.ui.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigatorTest {

    @Test
    fun starts_at_home_with_nothing_to_go_back_to() {
        val nav = Navigator()

        assertEquals(Screen.Home, nav.current)
        assertFalse(nav.canGoBack)
        assertFalse(nav.back(), "back at the root is a no-op, not a crash")
        assertEquals(Screen.Home, nav.current)
    }

    @Test
    fun navigate_pushes_and_back_pops() {
        val nav = Navigator()

        nav.navigateTo(Screen.LinkAccount)
        assertEquals(Screen.LinkAccount, nav.current)
        assertTrue(nav.canGoBack)

        assertTrue(nav.back())
        assertEquals(Screen.Home, nav.current)
    }

    @Test
    fun the_capture_flow_is_one_way_once_publishing_starts() {
        // Camera -> Review -> Publishing -> Success all replace rather than push, so back
        // from Success lands on Home, never on a stale progress or review screen.
        val nav = Navigator()

        nav.navigateTo(Screen.Camera)
        nav.replaceWith(Screen.Review("p1"))
        assertEquals(Screen.Review("p1"), nav.current)

        nav.replaceWith(Screen.Publishing("p1"))
        nav.replaceWith(Screen.Success("p1"))
        assertEquals(Screen.Success("p1"), nav.current)

        assertTrue(nav.back())
        assertEquals(Screen.Home, nav.current)
        assertFalse(nav.canGoBack)
    }

    @Test
    fun pop_to_home_unwinds_everything() {
        val nav = Navigator()
        nav.navigateTo(Screen.Camera)
        nav.replaceWith(Screen.Review("p1"))
        nav.navigateTo(Screen.PhotoDetails("p1"))

        nav.popToHome()

        assertEquals(Screen.Home, nav.current)
        assertFalse(nav.canGoBack)
    }

    @Test
    fun a_custom_root_is_respected() {
        val nav = Navigator(initial = Screen.Camera)

        assertEquals(Screen.Camera, nav.current)
        nav.popToHome()
        assertEquals(Screen.Camera, nav.current, "popToHome unwinds to the root, whatever it is")
    }
}
