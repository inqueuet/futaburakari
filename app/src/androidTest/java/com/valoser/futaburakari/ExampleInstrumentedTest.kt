package com.valoser.futaburakari

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

/**
 * Instrumented test that confirms the application context is correctly configured on device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // Verify that the target context resolves to the expected package name.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.valoser.futaburakari", appContext.packageName)
    }
}
