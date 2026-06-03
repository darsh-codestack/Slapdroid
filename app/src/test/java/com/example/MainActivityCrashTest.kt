package com.example

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Assert.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityCrashTest {
    @Test
    fun testMainActivityLaunch() {
        try {
            val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
            assertNotNull(activity)
            println("MainActivity launched successfully without crash")
        } catch (e: Exception) {
            e.printStackTrace()
            fail("MainActivity crashed on launch: ${e.message}")
        }
    }
}
