package com.fakegps.mocklocation

import android.graphics.Color
import com.fakegps.mocklocation.util.ThemeColorManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThemeColorManagerTest {

    @Test
    fun testIsColorMatchingPrimary_detectsDefaultCrimsonAndThemePrimaries() {
        assertTrue(ThemeColorManager.isColorMatchingPrimary(Color.parseColor("#E41B1B")))
        assertTrue(ThemeColorManager.isColorMatchingPrimary(Color.parseColor("#2563EB")))
        assertTrue(ThemeColorManager.isColorMatchingPrimary(Color.parseColor("#8B5CF6")))
    }

    @Test
    fun testIsColorMatchingPrimary_doesNotRecolorSuccessWarningOrMuted() {
        assertFalse(ThemeColorManager.isColorMatchingPrimary(Color.parseColor("#30D158")))
        assertFalse(ThemeColorManager.isColorMatchingPrimary(Color.parseColor("#FFD60A")))
        assertFalse(ThemeColorManager.isColorMatchingPrimary(Color.parseColor("#8E8E93")))
        assertFalse(ThemeColorManager.isColorMatchingPrimary(Color.parseColor("#FFFFFF")))
        assertFalse(ThemeColorManager.isColorMatchingPrimary(Color.parseColor("#000000")))
    }
}
