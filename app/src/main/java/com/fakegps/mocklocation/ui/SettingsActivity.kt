package com.fakegps.mocklocation.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.lifecycleScope
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.data.preferences.AppSettingsPreferences
import com.fakegps.mocklocation.data.preferences.SessionPreferences
import com.fakegps.mocklocation.databinding.ActivitySettingsBinding
import com.fakegps.mocklocation.ui.dialogs.SetupGuideDialog
import com.fakegps.mocklocation.util.PermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settingsPrefs: AppSettingsPreferences
    private lateinit var sessionPrefs: SessionPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsPrefs = AppSettingsPreferences(this)
        sessionPrefs = SessionPreferences(this)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadInitialValues()
        setupListeners()
        observeSessionTimer()

        com.fakegps.mocklocation.ads.AdManager.loadBanner(this, binding.adBannerContainer, isHomeBanner = false)
    }

    override fun onResume() {
        super.onResume()
        if (sessionPrefs.hasValidActiveSession()) {
            com.fakegps.mocklocation.service.SessionTimerManager.resumeExistingTimer(this)
        }
        if (binding.adBannerContainer.childCount == 0) {
            com.fakegps.mocklocation.ads.AdManager.loadBanner(this, binding.adBannerContainer, isHomeBanner = false)
        }
        refreshSystemStatus()
    }

    private fun observeSessionTimer() {
        lifecycleScope.launch {
            com.fakegps.mocklocation.service.SessionTimerManager.timerState.collect { timerState ->
                if (!isFinishing && !isDestroyed) {
                    renderSessionTimerUI(timerState)
                }
            }
        }
    }

    private fun renderSessionTimerUI(timerState: com.fakegps.mocklocation.service.SessionTimerManager.SessionTimerState) {
        val remaining = sessionPrefs.getTimeRemainingMillis()
        val formattedRemaining = sessionPrefs.formatRemainingTime()

        if (timerState.isRunning || (sessionPrefs.isSessionActive && remaining > 0)) {
            binding.tvSettingsSessionBadge.text = formattedRemaining
            binding.tvSettingsSessionBadge.setTextColor(ContextCompat.getColor(this, R.color.primary_bright))
            binding.tvSettingsSessionBadge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.badge_active_bg)
            binding.ivSettingsSessionIcon.imageTintList = ContextCompat.getColorStateList(this, R.color.primary_bright)

            binding.tvSettingsSessionTime.text = formattedRemaining
            binding.tvSettingsSessionTotal.text = "Total Allocated: ${sessionPrefs.formatAllocatedDuration()}"
            binding.pbSettingsSessionProgress.progress = timerState.progressPercent
        } else if (timerState.isExpired || sessionPrefs.isSessionExpired) {
            binding.tvSettingsSessionBadge.text = "EXPIRED"
            binding.tvSettingsSessionBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_error_text))
            binding.tvSettingsSessionBadge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.badge_error_bg)
            binding.ivSettingsSessionIcon.imageTintList = ContextCompat.getColorStateList(this, R.color.badge_error_text)

            binding.tvSettingsSessionTime.text = "00:00:00"
            binding.tvSettingsSessionTotal.text = "Session expired. Tap +1 Hour to extend."
            binding.pbSettingsSessionProgress.progress = 0
        } else {
            binding.tvSettingsSessionBadge.text = "STANDBY"
            binding.tvSettingsSessionBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_standby_text))
            binding.tvSettingsSessionBadge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.badge_standby_bg)
            binding.ivSettingsSessionIcon.imageTintList = ContextCompat.getColorStateList(this, R.color.badge_standby_text)

            binding.tvSettingsSessionTime.text = formattedRemaining
            binding.tvSettingsSessionTotal.text = "Default Duration: 2h 00m (Ready)"
            binding.pbSettingsSessionProgress.progress = 100
        }
    }

    private fun refreshSystemStatus() {
        renderSessionTimerUI(com.fakegps.mocklocation.service.SessionTimerManager.timerState.value)

        // Mock Location in Developer Options
        val isMockEnabled = PermissionHelper.isMockLocationEnabled(this)
        if (isMockEnabled) {
            binding.tvSettingsMockStatus.text = "Nowhere is selected as mock location app"
            binding.tvSettingsMockStatus.setTextColor(ContextCompat.getColor(this, R.color.badge_active_text))
            binding.btnSettingsDevOptions.text = "Configured"
        } else {
            binding.tvSettingsMockStatus.text = "Not selected as mock app in Developer Options"
            binding.tvSettingsMockStatus.setTextColor(ContextCompat.getColor(this, R.color.badge_error_text))
            binding.btnSettingsDevOptions.text = "Select Nowhere"
        }

        // Battery Optimization
        val isBatteryExempt = PermissionHelper.isIgnoringBatteryOptimizations(this)
        if (isBatteryExempt) {
            binding.tvSettingsBatteryStatus.text = "Unrestricted background running enabled"
            binding.tvSettingsBatteryStatus.setTextColor(ContextCompat.getColor(this, R.color.badge_active_text))
            binding.btnSettingsBattery.text = "Active"
            binding.btnSettingsBattery.isEnabled = false
        } else {
            binding.tvSettingsBatteryStatus.text = "Battery optimizer may sleep background GPS"
            binding.tvSettingsBatteryStatus.setTextColor(ContextCompat.getColor(this, R.color.badge_warning_text))
            binding.btnSettingsBattery.text = "Allow Unrestricted"
            binding.btnSettingsBattery.isEnabled = true
        }

        // Floating Window Overlay
        val hasOverlay = PermissionHelper.canDrawOverlays(this)
        if (hasOverlay) {
            binding.tvSettingsOverlayStatus.text = "Overlay permission granted for floating joystick"
            binding.tvSettingsOverlayStatus.setTextColor(ContextCompat.getColor(this, R.color.badge_active_text))
            binding.btnSettingsOverlay.text = "Granted"
            binding.btnSettingsOverlay.isEnabled = false
        } else {
            binding.tvSettingsOverlayStatus.text = "Permission needed for floating joystick overlay"
            binding.tvSettingsOverlayStatus.setTextColor(ContextCompat.getColor(this, R.color.badge_warning_text))
            binding.btnSettingsOverlay.text = "Grant"
            binding.btnSettingsOverlay.isEnabled = true
        }
    }

    private fun loadInitialValues() {
        // Advanced Simulation
        binding.switchFusedProvider.isChecked = settingsPrefs.useFusedProvider
        binding.switchJitter.isChecked = settingsPrefs.randomizeJitter
        val jitterRadius = settingsPrefs.jitterRadiusMeters.coerceIn(0.5f, 10.0f)
        binding.sliderJitterRadius.value = jitterRadius
        binding.tvJitterRadiusLabel.text = String.format("Radius: %.1f m", jitterRadius)
        binding.sliderJitterRadius.isEnabled = settingsPrefs.randomizeJitter
        binding.sliderJitterRadius.alpha = if (settingsPrefs.randomizeJitter) 1.0f else 0.4f
        binding.tvJitterRadiusLabel.alpha = if (settingsPrefs.randomizeJitter) 1.0f else 0.4f

        when (settingsPrefs.truncateDecimals) {
            6 -> binding.rbTruncate6.isChecked = true
            4 -> binding.rbTruncate4.isChecked = true
            else -> binding.rbTruncateFull.isChecked = true
        }

        // Map Tiles & Visuals
        when (settingsPrefs.mapTileSource) {
            "TOPO" -> binding.rbTopo.isChecked = true
            "USGS_SAT" -> binding.rbUsgsSat.isChecked = true
            else -> binding.rbMapnik.isChecked = true
        }

        // Theme & Units
        when (settingsPrefs.appTheme) {
            "LIGHT" -> binding.rbThemeLight.isChecked = true
            "SYSTEM" -> binding.rbThemeSystem.isChecked = true
            else -> binding.rbThemeDark.isChecked = true
        }

        when (settingsPrefs.distanceUnit) {
            "IMPERIAL" -> binding.rbUnitImperial.isChecked = true
            else -> binding.rbUnitMetric.isChecked = true
        }

        binding.switchSettingsBootInjection.isChecked = sessionPrefs.isPersistentBootInjectionEnabled
        refreshWidgetSlotsUI()
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener { finish() }

        binding.btnResetDefaults.setOnClickListener {
            resetToDefaults()
            Toast.makeText(this, "Settings reset to defaults", Toast.LENGTH_SHORT).show()
        }

        binding.btnSettingsExtendOneHour.setOnClickListener {
            com.fakegps.mocklocation.ads.AdManager.showRewardVideoWithProgress(
                this,
                onUserEarnedReward = {
                    com.fakegps.mocklocation.service.SessionTimerManager.extendSession(this, SessionPreferences.REWARD_EXTENSION_DURATION_MILLIS)
                    Toast.makeText(this, "✅ +1 Hour Added! Simulation time extended.", Toast.LENGTH_SHORT).show()
                    refreshSystemStatus()
                },
                onAdClosed = {
                    refreshSystemStatus()
                }
            )
        }

        binding.btnSettingsExtendManage.setOnClickListener {
            val dialog = com.fakegps.mocklocation.ui.dialogs.SessionExtendDialog(this)
            dialog.show()
        }

        // Permission & Integration buttons
        binding.btnSettingsAutoGrantRoot.setOnClickListener {
            val granted = PermissionHelper.tryAutoGrantRootMockPermission(this)
            if (granted) {
                Toast.makeText(this, "Root Mock Location Granted Successfully! 🎉", Toast.LENGTH_LONG).show()
                refreshSystemStatus()
            } else {
                Toast.makeText(this, "Root auto-grant failed or device is not rooted. Use Configure to set manually.", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnSettingsDevOptions.setOnClickListener {
            SetupGuideDialog(this) {
                PermissionHelper.openDeveloperSettings(this)
            }.show()
        }

        binding.btnSettingsBattery.setOnClickListener {
            PermissionHelper.requestIgnoreBatteryOptimizations(this)
        }

        binding.btnSettingsOverlay.setOnClickListener {
            PermissionHelper.requestOverlayPermission(this)
        }

        binding.btnSettingsHotspotManage.setOnClickListener {
            com.fakegps.mocklocation.ui.dialogs.HotspotTetheringBottomSheet.newInstance()
                .show(supportFragmentManager, com.fakegps.mocklocation.ui.dialogs.HotspotTetheringBottomSheet.TAG)
        }

        // Developer & Social Links
        binding.btnDeveloperPortfolio.setOnClickListener {
            openBrowser("https://fortuneadebayo.space")
        }

        binding.btnDeveloperTwitter.setOnClickListener {
            openBrowser("https://x.com/OnNerd_eth")
        }

        binding.btnTelegramChannel.setOnClickListener {
            openBrowser("https://t.me/nowhere_proxy")
        }

        binding.btnTelegramGroup.setOnClickListener {
            openBrowser("https://t.me/+vcmA7kOtLEw3ZjM0")
        }

        binding.btnDeveloperWhatsApp1.setOnClickListener {
            openBrowser("https://wa.me/2347067860584")
        }

        binding.btnDeveloperWhatsApp2.setOnClickListener {
            openBrowser("https://wa.me/2349167689200")
        }

        binding.btnOpenAppTutorial.setOnClickListener {
            com.fakegps.mocklocation.ui.dialogs.AppTutorialDialog(this).show()
        }

        binding.btnOpenDisclaimer.setOnClickListener {
            showDisclaimerDialog()
        }

        binding.tvAppVersionTitle.text = "Nowhere Version v${com.fakegps.mocklocation.BuildConfig.VERSION_NAME}"
        binding.tvSettingsFooterVersion.text = "Version ${com.fakegps.mocklocation.BuildConfig.VERSION_NAME} (Build ${com.fakegps.mocklocation.BuildConfig.VERSION_CODE}) • Release"
        binding.btnCheckAppUpdates.setOnClickListener {
            binding.tvCheckUpdateStatus.text = "Checking..."
            binding.btnCheckAppUpdates.isEnabled = false
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val updateInfo = com.fakegps.mocklocation.util.AppUpdateManager.checkForUpdates(this@SettingsActivity, forceCheck = true)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    binding.btnCheckAppUpdates.isEnabled = true
                    if (updateInfo.isUpdateAvailable) {
                        binding.tvCheckUpdateStatus.text = "Update Available!"
                        val bottomSheet = com.fakegps.mocklocation.ui.dialogs.AppUpdateBottomSheet.newInstance(updateInfo)
                        bottomSheet.show(supportFragmentManager, com.fakegps.mocklocation.ui.dialogs.AppUpdateBottomSheet.TAG)
                    } else {
                        binding.tvCheckUpdateStatus.text = "Up to date"
                        Toast.makeText(this@SettingsActivity, "🎉 You're running the latest version (v${com.fakegps.mocklocation.BuildConfig.VERSION_NAME})", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // Persistent Location Injector
        binding.switchSettingsBootInjection.setOnCheckedChangeListener { _, isChecked ->
            sessionPrefs.isPersistentBootInjectionEnabled = isChecked
            val msg = if (isChecked) "Auto-Inject on Boot: Enabled" else "Auto-Inject on Boot: Disabled"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // Switches & Sliders
        binding.switchFusedProvider.setOnCheckedChangeListener { _, isChecked ->
            settingsPrefs.useFusedProvider = isChecked
        }

        binding.switchJitter.setOnCheckedChangeListener { _, isChecked ->
            settingsPrefs.randomizeJitter = isChecked
            binding.sliderJitterRadius.isEnabled = isChecked
            binding.sliderJitterRadius.alpha = if (isChecked) 1.0f else 0.4f
            binding.tvJitterRadiusLabel.alpha = if (isChecked) 1.0f else 0.4f
        }

        binding.sliderJitterRadius.addOnChangeListener { _, value, _ ->
            settingsPrefs.jitterRadiusMeters = value
            binding.tvJitterRadiusLabel.text = String.format("Radius: %.1f m", value)
        }

        binding.rgTruncate.setOnCheckedChangeListener { _, checkedId ->
            settingsPrefs.truncateDecimals = when (checkedId) {
                R.id.rbTruncate6 -> 6
                R.id.rbTruncate4 -> 4
                else -> -1
            }
        }

        binding.rgMapSource.setOnCheckedChangeListener { _, checkedId ->
            val source = when (checkedId) {
                R.id.rbTopo -> "TOPO"
                R.id.rbUsgsSat -> "USGS_SAT"
                else -> "MAPNIK"
            }
            settingsPrefs.mapTileSource = source
            val label = when (source) {
                "TOPO" -> "Terrain Topographic (OpenTopoMap)"
                "USGS_SAT" -> "High-Resolution Satellite Imagery"
                else -> "Standard OpenStreetMap"
            }
            Toast.makeText(this, "Map layer: $label", Toast.LENGTH_SHORT).show()
        }

        binding.rgTheme.setOnCheckedChangeListener { _, checkedId ->
            val theme = when (checkedId) {
                R.id.rbThemeLight -> "LIGHT"
                R.id.rbThemeSystem -> "SYSTEM"
                else -> "DARK"
            }
            if (settingsPrefs.appTheme != theme) {
                settingsPrefs.appTheme = theme
                settingsPrefs.applyTheme(theme)
                Toast.makeText(this, "Theme set to $theme", Toast.LENGTH_SHORT).show()
            }
        }

        binding.rgUnits.setOnCheckedChangeListener { _, checkedId ->
            settingsPrefs.distanceUnit = when (checkedId) {
                R.id.rbUnitImperial -> "IMPERIAL"
                else -> "METRIC"
            }
        }

        binding.btnPinShortcut.setOnClickListener {
            pinNowhereShortcut()
        }

        // Widget Slot Customization Buttons
        binding.btnEditWidgetSlot1.setOnClickListener { showEditSlotDialog(1) }
        binding.btnEditWidgetSlot2.setOnClickListener { showEditSlotDialog(2) }
        binding.btnEditWidgetSlot3.setOnClickListener { showEditSlotDialog(3) }
    }

    private fun refreshWidgetSlotsUI() {
        binding.tvWidgetSlot1Title.text = "Slot 1: ${settingsPrefs.widgetSlot1Name}"
        binding.tvWidgetSlot1Coords.text = String.format("%.4f°, %.4f°", settingsPrefs.widgetSlot1Lat, settingsPrefs.widgetSlot1Lon)

        binding.tvWidgetSlot2Title.text = "Slot 2: ${settingsPrefs.widgetSlot2Name}"
        binding.tvWidgetSlot2Coords.text = String.format("%.4f°, %.4f°", settingsPrefs.widgetSlot2Lat, settingsPrefs.widgetSlot2Lon)

        binding.tvWidgetSlot3Title.text = "Slot 3: ${settingsPrefs.widgetSlot3Name}"
        binding.tvWidgetSlot3Coords.text = String.format("%.4f°, %.4f°", settingsPrefs.widgetSlot3Lat, settingsPrefs.widgetSlot3Lon)
    }

    private fun showEditSlotDialog(slotIndex: Int) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_widget_slot, null)
        val tvTitle = dialogView.findViewById<android.widget.TextView>(R.id.tvDialogSlotTitle)
        val etName = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etSlotName)
        val etLat = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etSlotLat)
        val etLon = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etSlotLon)
        val btnCancel = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancelSlot)
        val btnSave = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSaveSlot)

        tvTitle.text = "EDIT WIDGET DESTINATION (SLOT $slotIndex)"

        when (slotIndex) {
            1 -> {
                etName.setText(settingsPrefs.widgetSlot1Name)
                etLat.setText(settingsPrefs.widgetSlot1Lat.toString())
                etLon.setText(settingsPrefs.widgetSlot1Lon.toString())
            }
            2 -> {
                etName.setText(settingsPrefs.widgetSlot2Name)
                etLat.setText(settingsPrefs.widgetSlot2Lat.toString())
                etLon.setText(settingsPrefs.widgetSlot2Lon.toString())
            }
            3 -> {
                etName.setText(settingsPrefs.widgetSlot3Name)
                etLat.setText(settingsPrefs.widgetSlot3Lat.toString())
                etLon.setText(settingsPrefs.widgetSlot3Lon.toString())
            }
        }

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val name = etName.text.toString().trim().ifBlank { "Destination $slotIndex" }
            val lat = etLat.text.toString().toDoubleOrNull() ?: 0.0
            val lon = etLon.text.toString().toDoubleOrNull() ?: 0.0

            when (slotIndex) {
                1 -> {
                    settingsPrefs.widgetSlot1Name = name
                    settingsPrefs.widgetSlot1Lat = lat
                    settingsPrefs.widgetSlot1Lon = lon
                }
                2 -> {
                    settingsPrefs.widgetSlot2Name = name
                    settingsPrefs.widgetSlot2Lat = lat
                    settingsPrefs.widgetSlot2Lon = lon
                }
                3 -> {
                    settingsPrefs.widgetSlot3Name = name
                    settingsPrefs.widgetSlot3Lat = lat
                    settingsPrefs.widgetSlot3Lon = lon
                }
            }

            refreshWidgetSlotsUI()
            com.fakegps.mocklocation.ui.widget.NowhereFavoritesWidgetProvider.updateAllFavoritesWidgets(this)
            Toast.makeText(this, "Slot $slotIndex updated to $name", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun openBrowser(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open link: $url", Toast.LENGTH_SHORT).show()
        }
    }

    private fun resetToDefaults() {
        settingsPrefs.useFusedProvider = true
        settingsPrefs.randomizeJitter = true
        settingsPrefs.jitterRadiusMeters = 2.0f
        settingsPrefs.truncateDecimals = -1
        settingsPrefs.baseAccuracy = 2.5f
        settingsPrefs.defaultAltitude = 15.0f
        settingsPrefs.randomizeAltitude = true
        settingsPrefs.updateIntervalMovingMs = 1000L
        settingsPrefs.mapTileSource = "MAPNIK"
        settingsPrefs.enableMapAnimations = true
        settingsPrefs.appTheme = "DARK"
        settingsPrefs.distanceUnit = "METRIC"
        settingsPrefs.enableHapticFeedback = true

        settingsPrefs.widgetSlot1Name = "Paris"
        settingsPrefs.widgetSlot1Lat = 48.8566
        settingsPrefs.widgetSlot1Lon = 2.3522

        settingsPrefs.widgetSlot2Name = "Tokyo"
        settingsPrefs.widgetSlot2Lat = 35.6762
        settingsPrefs.widgetSlot2Lon = 139.6503

        settingsPrefs.widgetSlot3Name = "New York"
        settingsPrefs.widgetSlot3Lat = 40.7128
        settingsPrefs.widgetSlot3Lon = -74.0060

        loadInitialValues()
        refreshWidgetSlotsUI()
        com.fakegps.mocklocation.ui.widget.NowhereFavoritesWidgetProvider.updateAllFavoritesWidgets(this)
    }

    private fun pinNowhereShortcut() {
        if (ShortcutManagerCompat.isRequestPinShortcutSupported(this)) {
            val shortcutIntent = Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pinShortcutInfo = ShortcutInfoCompat.Builder(this, "nowhere_main_launcher_shortcut")
                .setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher))
                .setShortLabel("Nowhere")
                .setLongLabel("Nowhere Location Simulator")
                .setIntent(shortcutIntent)
                .build()

            ShortcutManagerCompat.requestPinShortcut(this, pinShortcutInfo, null)
            Toast.makeText(this, "Nowhere icon added to home screen!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Home screen shortcut pinning not supported on this launcher", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDisclaimerDialog() {
        val disclaimerSheet = com.fakegps.mocklocation.ui.dialogs.DisclaimerBottomSheet()
        disclaimerSheet.show(supportFragmentManager, "DisclaimerBottomSheet")
    }
}
