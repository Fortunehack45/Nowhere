package com.fakegps.mocklocation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fakegps.mocklocation.data.preferences.SessionPreferences
import com.fakegps.mocklocation.util.OEMDetector
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OEMDetectorTest {

    private lateinit var context: Context
    private lateinit var sessionPrefs: SessionPreferences

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        sessionPrefs = SessionPreferences(context)
    }

    @Test
    fun testOEMDetection_Xiaomi() {
        ShadowBuild.setManufacturer("Xiaomi")
        assertEquals(OEMDetector.OEM.XIAOMI, OEMDetector.getDeviceOEM())
        assertTrue(OEMDetector.getOEMGuidanceMessage().contains("Xiaomi"))
    }

    @Test
    fun testOEMDetection_Huawei() {
        ShadowBuild.setManufacturer("Huawei")
        assertEquals(OEMDetector.OEM.HUAWEI, OEMDetector.getDeviceOEM())
        assertTrue(OEMDetector.getOEMGuidanceMessage().contains("Huawei"))
    }

    @Test
    fun testOEMDetection_OnePlus() {
        ShadowBuild.setManufacturer("OnePlus")
        assertEquals(OEMDetector.OEM.OPPO_ONEPLUS, OEMDetector.getDeviceOEM())
        assertTrue(OEMDetector.getOEMGuidanceMessage().contains("ColorOS") || OEMDetector.getOEMGuidanceMessage().contains("OxygenOS"))
    }

    @Test
    fun testOEMDetection_Vivo() {
        ShadowBuild.setManufacturer("vivo")
        assertEquals(OEMDetector.OEM.VIVO, OEMDetector.getDeviceOEM())
        assertTrue(OEMDetector.getOEMGuidanceMessage().contains("Vivo"))
    }

    @Test
    fun testOEMDetection_Samsung() {
        ShadowBuild.setManufacturer("samsung")
        assertEquals(OEMDetector.OEM.SAMSUNG, OEMDetector.getDeviceOEM())
        assertTrue(OEMDetector.getOEMGuidanceMessage().contains("Samsung"))
    }

    @Test
    fun testOEMDetection_GooglePixel_IsOther() {
        ShadowBuild.setManufacturer("Google")
        assertEquals(OEMDetector.OEM.OTHER, OEMDetector.getDeviceOEM())
    }

    @Test
    fun testSessionPreferences_hasPromptedOemWidgetNudge() {
        sessionPrefs.hasPromptedOemWidgetNudge = false
        assertFalse(sessionPrefs.hasPromptedOemWidgetNudge)

        sessionPrefs.hasPromptedOemWidgetNudge = true
        assertTrue(sessionPrefs.hasPromptedOemWidgetNudge)
    }
}
