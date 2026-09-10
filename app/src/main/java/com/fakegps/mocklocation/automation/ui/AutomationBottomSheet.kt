package com.fakegps.mocklocation.automation.ui

import android.Manifest
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.automation.data.AutomationSettingsEntity
import com.fakegps.mocklocation.automation.engine.ScheduleExecutor
import com.fakegps.mocklocation.data.db.AppDatabase
import com.fakegps.mocklocation.databinding.LayoutDialogAutomationBinding
import com.fakegps.mocklocation.util.ThemeColorManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class AutomationBottomSheet : BottomSheetDialogFragment() {

    private var _binding: LayoutDialogAutomationBinding? = null
    private val binding get() = _binding!!

    private lateinit var scheduleAdapter: ScheduleAdapter
    private lateinit var wifiTriggerAdapter: WifiTriggerAdapter
    private lateinit var logAdapter: AutomationLogAdapter

    private var currentSettings: AutomationSettingsEntity = AutomationSettingsEntity()

    private val requestFineLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            binding.cardFineLocationNotice.visibility = View.GONE
            Toast.makeText(requireContext(), "Fine Location granted for SSID detection", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), "Fine Location required for WiFi SSID matching on Android 8.1+", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val TAG = "AutomationBottomSheet"

        fun newInstance(): AutomationBottomSheet {
            return AutomationBottomSheet()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = LayoutDialogAutomationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ThemeColorManager.applyThemeRecursively(binding.root, requireContext())

        setupTabs()
        setupRecyclerViews()
        setupGuardrailControls()
        checkFineLocationPermission()
        observeData()

        binding.btnCloseAutomation.setOnClickListener {
            dismiss()
        }

        binding.btnAddSchedule.setOnClickListener {
            AddEditScheduleDialog.newInstance().show(parentFragmentManager, "AddScheduleDialog")
        }

        binding.btnAddWifiTrigger.setOnClickListener {
            AddEditWifiTriggerDialog.newInstance().show(parentFragmentManager, "AddWifiTriggerDialog")
        }

        binding.btnGrantFineLocation.setOnClickListener {
            requestFineLocationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun setupTabs() {
        binding.rgAutomationTabs.setOnCheckedChangeListener { _, checkedId ->
            binding.containerSchedulesTab.visibility = if (checkedId == R.id.tabBtnSchedules) View.VISIBLE else View.GONE
            binding.containerWifiTab.visibility = if (checkedId == R.id.tabBtnWifi) View.VISIBLE else View.GONE
            binding.containerGuardrailsTab.visibility = if (checkedId == R.id.tabBtnGuardrails) View.VISIBLE else View.GONE
        }
    }

    private fun setupRecyclerViews() {
        val db = AppDatabase.getInstance(requireContext())

        scheduleAdapter = ScheduleAdapter(
            onToggle = { schedule, isChecked ->
                lifecycleScope.launch(Dispatchers.IO) {
                    db.scheduleDao().setScheduleEnabled(schedule.id, isChecked)
                    if (isChecked) {
                        ScheduleExecutor.scheduleOne(requireContext(), schedule.id)
                    } else {
                        ScheduleExecutor.cancelOne(requireContext(), schedule.id)
                    }
                }
            },
            onEdit = { schedule ->
                AddEditScheduleDialog.newInstance(schedule.id).show(parentFragmentManager, "EditScheduleDialog")
            },
            onDelete = { schedule ->
                lifecycleScope.launch(Dispatchers.IO) {
                    ScheduleExecutor.cancelOne(requireContext(), schedule.id)
                    db.scheduleDao().deleteSchedule(schedule)
                }
            }
        )
        binding.rvAutomationSchedules.layoutManager = LinearLayoutManager(requireContext())
        binding.rvAutomationSchedules.adapter = scheduleAdapter

        wifiTriggerAdapter = WifiTriggerAdapter(
            onToggle = { trigger, isChecked ->
                lifecycleScope.launch(Dispatchers.IO) {
                    db.wifiTriggerDao().setWifiTriggerEnabled(trigger.id, isChecked)
                }
            },
            onDelete = { trigger ->
                lifecycleScope.launch(Dispatchers.IO) {
                    db.wifiTriggerDao().deleteWifiTrigger(trigger)
                }
            }
        )
        binding.rvAutomationWifiTriggers.layoutManager = LinearLayoutManager(requireContext())
        binding.rvAutomationWifiTriggers.adapter = wifiTriggerAdapter

        logAdapter = AutomationLogAdapter()
        binding.rvAutomationLogs.layoutManager = LinearLayoutManager(requireContext())
        binding.rvAutomationLogs.adapter = logAdapter
    }

    private fun checkFineLocationPermission() {
        val hasFine = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        binding.cardFineLocationNotice.visibility = if (hasFine) View.GONE else View.VISIBLE
    }

    private fun setupGuardrailControls() {
        val db = AppDatabase.getInstance(requireContext())

        // Master Schedule Switch
        binding.switchMasterSchedule.setOnCheckedChangeListener { buttonView, isChecked ->
            if (!buttonView.isPressed) return@setOnCheckedChangeListener
            val appContext = context?.applicationContext ?: return@setOnCheckedChangeListener
            lifecycleScope.launch(Dispatchers.IO) {
                db.automationSettingsDao().setScheduledAutomationEnabled(isChecked)
                if (isChecked) {
                    ScheduleExecutor.scheduleAllEnabled(appContext)
                } else {
                    ScheduleExecutor.cancelAll(appContext)
                }
            }
        }

        // Master WiFi Switch
        binding.switchMasterWifi.setOnCheckedChangeListener { buttonView, isChecked ->
            if (!buttonView.isPressed) return@setOnCheckedChangeListener
            lifecycleScope.launch(Dispatchers.IO) {
                db.automationSettingsDao().setWifiTriggersEnabled(isChecked)
            }
        }

        // Jitter Slider
        binding.sliderJitter.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val mins = value.toInt()
                binding.tvJitterLabel.text = "±$mins min"
                saveSettingsAsync(currentSettings.copy(jitterMinutes = mins))
            }
        }

        // Quiet Hours Switch
        binding.switchQuietHours.setOnCheckedChangeListener { buttonView, isChecked ->
            if (!buttonView.isPressed) return@setOnCheckedChangeListener
            binding.layoutQuietHoursConfig.visibility = if (isChecked) View.VISIBLE else View.GONE
            saveSettingsAsync(currentSettings.copy(quietHoursEnabled = isChecked))
        }

        // Quiet Hours Pickers
        binding.btnQuietStart.setOnClickListener {
            val startMin = currentSettings.quietHoursStartMinute
            TimePickerDialog(requireContext(), { _, h, m ->
                val newMin = h * 60 + m
                binding.btnQuietStart.text = formatMinuteOfDay(newMin)
                saveSettingsAsync(currentSettings.copy(quietHoursStartMinute = newMin))
            }, startMin / 60, startMin % 60, false).show()
        }

        binding.btnQuietEnd.setOnClickListener {
            val endMin = currentSettings.quietHoursEndMinute
            TimePickerDialog(requireContext(), { _, h, m ->
                val newMin = h * 60 + m
                binding.btnQuietEnd.text = formatMinuteOfDay(newMin)
                saveSettingsAsync(currentSettings.copy(quietHoursEndMinute = newMin))
            }, endMin / 60, endMin % 60, false).show()
        }

        binding.rgQuietMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == R.id.rbQuietDelay) "DELAY" else "SKIP"
            saveSettingsAsync(currentSettings.copy(quietHoursMode = mode))
        }

        // Battery Guardrail Switch & Sliders
        binding.switchBatteryGuard.setOnCheckedChangeListener { buttonView, isChecked ->
            if (!buttonView.isPressed) return@setOnCheckedChangeListener
            binding.layoutBatteryConfig.visibility = if (isChecked) View.VISIBLE else View.GONE
            saveSettingsAsync(currentSettings.copy(batteryGuardEnabled = isChecked))
        }

        binding.sliderBatteryCutoff.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val cutoff = value.toInt()
                binding.tvBatteryCutoffLabel.text = "$cutoff%"
                // Ensure resume is always at least cutoff + 5%
                val resume = currentSettings.batteryResumePercent.coerceAtLeast(cutoff + 5)
                binding.sliderBatteryResume.value = resume.toFloat()
                binding.tvBatteryResumeLabel.text = "$resume%"
                saveSettingsAsync(currentSettings.copy(batteryThresholdPercent = cutoff, batteryResumePercent = resume))
            }
        }

        binding.sliderBatteryResume.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val resume = value.toInt()
                binding.tvBatteryResumeLabel.text = "$resume%"
                saveSettingsAsync(currentSettings.copy(batteryResumePercent = resume))
            }
        }
    }

    private fun formatMinuteOfDay(totalMinutes: Int): String {
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        val amPm = if (h < 12) "AM" else "PM"
        val displayH = if (h % 12 == 0) 12 else h % 12
        return String.format(Locale.US, "%02d:%02d %s", displayH, m, amPm)
    }

    private fun saveSettingsAsync(settings: AutomationSettingsEntity) {
        currentSettings = settings
        val appContext = context?.applicationContext ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getInstance(appContext)
            db.automationSettingsDao().insertOrUpdateSettings(settings)
        }
    }

    private fun observeData() {
        val appContext = context?.applicationContext ?: return
        val db = AppDatabase.getInstance(appContext)

        // 1. Settings Flow
        lifecycleScope.launch {
            db.automationSettingsDao().getSettingsFlow().collectLatest { settings ->
                val ctx = context ?: return@collectLatest
                if (_binding == null) return@collectLatest
                if (settings != null) {
                    currentSettings = settings

                    val anyActive = settings.scheduledAutomationEnabled || settings.wifiTriggersEnabled || settings.motionSyncEnabled
                    if (anyActive) {
                        binding.tvEngineStatusPill.text = "ACTIVE"
                        binding.tvEngineStatusPill.setTextColor(ContextCompat.getColor(ctx, R.color.badge_active_text))
                    } else {
                        binding.tvEngineStatusPill.text = "STANDBY"
                        binding.tvEngineStatusPill.setTextColor(ContextCompat.getColor(ctx, R.color.text_muted))
                    }

                    binding.switchMasterSchedule.isChecked = settings.scheduledAutomationEnabled
                    binding.switchMasterWifi.isChecked = settings.wifiTriggersEnabled

                    binding.tvJitterLabel.text = "±${settings.jitterMinutes} min"
                    binding.sliderJitter.value = settings.jitterMinutes.toFloat()

                    binding.switchQuietHours.isChecked = settings.quietHoursEnabled
                    binding.layoutQuietHoursConfig.visibility = if (settings.quietHoursEnabled) View.VISIBLE else View.GONE
                    binding.btnQuietStart.text = formatMinuteOfDay(settings.quietHoursStartMinute)
                    binding.btnQuietEnd.text = formatMinuteOfDay(settings.quietHoursEndMinute)
                    if (settings.quietHoursMode.equals("SKIP", ignoreCase = true)) {
                        binding.rbQuietSkip.isChecked = true
                    } else {
                        binding.rbQuietDelay.isChecked = true
                    }

                    binding.switchBatteryGuard.isChecked = settings.batteryGuardEnabled
                    binding.layoutBatteryConfig.visibility = if (settings.batteryGuardEnabled) View.VISIBLE else View.GONE
                    binding.tvBatteryCutoffLabel.text = "${settings.batteryThresholdPercent}%"
                    binding.sliderBatteryCutoff.value = settings.batteryThresholdPercent.toFloat()
                    binding.tvBatteryResumeLabel.text = "${settings.batteryResumePercent}%"
                    binding.sliderBatteryResume.value = settings.batteryResumePercent.toFloat()
                }
            }
        }

        // 2. Schedules Flow
        lifecycleScope.launch {
            db.scheduleDao().getAllSchedules().collectLatest { schedules ->
                if (_binding == null) return@collectLatest
                scheduleAdapter.submitList(schedules)
                binding.layoutSchedulesEmpty.visibility = if (schedules.isEmpty()) View.VISIBLE else View.GONE
            }
        }

        // 3. WiFi Triggers Flow
        lifecycleScope.launch {
            db.wifiTriggerDao().getAllWifiTriggers().collectLatest { triggers ->
                if (_binding == null) return@collectLatest
                wifiTriggerAdapter.submitList(triggers)
                binding.layoutWifiEmpty.visibility = if (triggers.isEmpty()) View.VISIBLE else View.GONE
            }
        }

        // 4. Logs Flow
        lifecycleScope.launch {
            db.automationLogDao().getLast20Logs().collectLatest { logs ->
                if (_binding == null) return@collectLatest
                logAdapter.submitList(logs)
                binding.layoutLogsEmpty.visibility = if (logs.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
