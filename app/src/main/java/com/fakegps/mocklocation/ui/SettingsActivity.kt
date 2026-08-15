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
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.data.preferences.AppSettingsPreferences
import com.fakegps.mocklocation.data.preferences.SessionPreferences
import com.fakegps.mocklocation.databinding.ActivitySettingsBinding
import com.fakegps.mocklocation.ui.dialogs.SetupGuideDialog
import com.fakegps.mocklocation.util.PermissionHelper

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
    }

    override fun onResume() {
        super.onResume()
        refreshSystemStatus()
    }

    private fun refreshSystemStatus() {
        // 24-Hour Ad-Free Pass Status
        if (settingsPrefs.isAdFreeActive) {
            binding.tvAdFreeBadge.text = "Active"
            binding.tvAdFreeBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_active_text))
            binding.tvAdFreeDescription.text = "24-Hour Ad-Free pass is active! All ads are hidden."
            binding.tvAdFreeProgressText.text = settingsPrefs.getAdFreeRemainingTimeText()
            binding.pbAdFreeProgress.progress = 5
            binding.btnWatchRewardedAd.text = "Extend +24h"
        } else {
            val watched = settingsPrefs.watchedRewardAdsCount
            binding.tvAdFreeBadge.text = "Inactive"
            binding.tvAdFreeBadge.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
            binding.tvAdFreeDescription.text = "Watch 5 short rewarded videos to remove all banner, interstitial, and open ads for 24 hours."
            binding.tvAdFreeProgressText.text = "$watched / 5 Videos Watched (${5 - watched} remaining)"
            binding.pbAdFreeProgress.progress = watched
            binding.btnWatchRewardedAd.text = "Watch Video"
        }

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
        binding.sliderJitterRadius.value = settingsPrefs.jitterRadiusMeters.coerceIn(0.5f, 10.0f)
        binding.tvJitterRadiusLabel.text = String.format("Radius: %.1f m", settingsPrefs.jitterRadiusMeters)

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

        binding.btnWatchRewardedAd.setOnClickListener {
            if (com.fakegps.mocklocation.ads.AdManager.isRewardedAdReady()) {
                com.fakegps.mocklocation.ads.AdManager.showRewardedAd(
                    this,
                    onUserEarnedReward = {
                        val (newCount, unlocked) = settingsPrefs.recordRewardedAdWatched()
                        if (unlocked) {
                            Toast.makeText(this, "24-Hour Ad-Free Pass Unlocked! All ads are removed.", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this, "Video $newCount / 5 complete! Watch ${5 - newCount} more to unlock 24h Ad-Free.", Toast.LENGTH_SHORT).show()
                        }
                        refreshSystemStatus()
                    },
                    onAdClosed = {
                        refreshSystemStatus()
                    }
                )
            } else {
                com.fakegps.mocklocation.ads.AdManager.preloadRewardedAd(this)
                Toast.makeText(this, "Video ad is loading. Please tap again in a moment.", Toast.LENGTH_SHORT).show()
            }
        }

        // Permission & Integration buttons
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
            settingsPrefs.mapTileSource = when (checkedId) {
                R.id.rbTopo -> "TOPO"
                R.id.rbUsgsSat -> "USGS_SAT"
                else -> "MAPNIK"
            }
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
}
