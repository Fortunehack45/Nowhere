package com.fakegps.mocklocation

import android.app.AppOpsManager
import android.app.Application
import android.content.Context
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import com.fakegps.mocklocation.data.preferences.SessionPreferences
import com.fakegps.mocklocation.service.MockLocationService
import com.fakegps.mocklocation.service.ServiceState
import com.fakegps.mocklocation.simulator.SimulationMode
import com.fakegps.mocklocation.ui.MainActivity
import com.fakegps.mocklocation.ui.MainViewModel
import com.fakegps.mocklocation.ui.SelectedModeTab
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityRegressionTest {

    private fun setupPermissionsAndSession(app: Application) {
        val appOps = app.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        Shadows.shadowOf(appOps).setMode(
            AppOpsManager.OPSTR_MOCK_LOCATION,
            Process.myUid(),
            app.packageName,
            AppOpsManager.MODE_ALLOWED
        )
        Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)

        val sessionPrefs = SessionPreferences(app)
        sessionPrefs.isSessionActive = true
        sessionPrefs.isSessionRunning = true
        sessionPrefs.sessionExpiresTimestamp = System.currentTimeMillis() + 3600_000L
    }

    private fun getViewModel(activity: MainActivity): MainViewModel {
        val method = MainActivity::class.java.getDeclaredMethod("getViewModel").apply {
            isAccessible = true
        }
        return method.invoke(activity) as MainViewModel
    }

    private fun injectBoundMockService(activity: MainActivity, service: MockLocationService) {
        val mockServiceField = MainActivity::class.java.getDeclaredField("mockService").apply {
            isAccessible = true
        }
        mockServiceField.set(activity, service)

        val isServiceBoundField = MainActivity::class.java.getDeclaredField("isServiceBound").apply {
            isAccessible = true
        }
        isServiceBoundField.set(activity, true)
    }

    @Test
    fun testStartFixedSpoofing_sendsSingleIntent_andMakesNoDirectBinderCall() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        setupPermissionsAndSession(app)

        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.create().start().resume().get()

        var directStartFixedCalled = 0
        val testService = object : MockLocationService() {
            override fun startFixed(latitude: Double, longitude: Double, altitude: Double, fromRestore: Boolean) {
                directStartFixedCalled++
            }
        }

        injectBoundMockService(activity, testService)

        // Clear started services history in shadow
        Shadows.shadowOf(app).clearStartedServices()

        // Invoke startFixedSpoofing via reflection
        val startFixedSpoofingMethod = MainActivity::class.java.getDeclaredMethod("startFixedSpoofing").apply {
            isAccessible = true
        }
        startFixedSpoofingMethod.invoke(activity)

        val allStarted = Shadows.shadowOf(app).allStartedServices
        val startFixedIntents = allStarted.filter { it.action == MockLocationService.ACTION_START_FIXED }

        assertEquals("App-side start path must send exactly one ACTION_START_FIXED intent", 1, startFixedIntents.size)
        assertEquals("Direct startFixed call on mockService binder must not be made", 0, directStartFixedCalled)

        controller.pause().stop().destroy()
    }

    @Test
    fun testSelectDestinationAndCloseSearch_sendsSingleIntent_andMakesNoDirectBinderCall() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        setupPermissionsAndSession(app)

        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.create().start().resume().get()

        var directStartFixedCalled = 0
        val testService = object : MockLocationService() {
            override fun startFixed(latitude: Double, longitude: Double, altitude: Double, fromRestore: Boolean) {
                directStartFixedCalled++
            }
        }

        injectBoundMockService(activity, testService)

        val viewModel = getViewModel(activity)
        viewModel.onServiceStateUpdated(
            ServiceState.Running(
                mode = SimulationMode.Fixed(37.7749, -122.4194, 15.0),
                latitude = 37.7749,
                longitude = -122.4194,
                altitude = 15.0,
                speedMps = 0.0f,
                bearingDegrees = 0.0f
            )
        )
        viewModel.setSelectedTab(SelectedModeTab.FIXED)

        Shadows.shadowOf(app).clearStartedServices()

        val selectDestMethod = MainActivity::class.java.getDeclaredMethod(
            "selectDestinationAndCloseSearch",
            String::class.java,
            String::class.java,
            Double::class.javaPrimitiveType,
            Double::class.javaPrimitiveType
        ).apply {
            isAccessible = true
        }
        selectDestMethod.invoke(activity, "Test Place", "Snippet", 40.7128, -74.0060)

        val allStarted = Shadows.shadowOf(app).allStartedServices
        val startFixedIntents = allStarted.filter { it.action == MockLocationService.ACTION_START_FIXED }

        assertEquals("Destination selection must send exactly one ACTION_START_FIXED intent", 1, startFixedIntents.size)
        val intent = startFixedIntents.first()
        assertEquals(40.7128, intent.getDoubleExtra(MockLocationService.EXTRA_LATITUDE, 0.0), 0.0001)
        assertEquals(-74.0060, intent.getDoubleExtra(MockLocationService.EXTRA_LONGITUDE, 0.0), 0.0001)

        assertEquals("Direct startFixed call on mockService binder must not be made", 0, directStartFixedCalled)

        controller.pause().stop().destroy()
    }
}
