package com.fakegps.mocklocation.automation.ui

import android.app.TimePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.automation.data.ScheduleEntity
import com.fakegps.mocklocation.automation.data.ScheduleStepEntity
import com.fakegps.mocklocation.automation.engine.ScheduleExecutor
import com.fakegps.mocklocation.automation.engine.ScheduleRecurrenceCalculator
import com.fakegps.mocklocation.data.db.AppDatabase
import com.fakegps.mocklocation.data.db.FavoriteLocation
import com.fakegps.mocklocation.data.db.SavedRoute
import com.fakegps.mocklocation.databinding.LayoutDialogAddEditScheduleBinding
import com.fakegps.mocklocation.util.ThemeColorManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

class AddEditScheduleDialog : BottomSheetDialogFragment() {

    private var _binding: LayoutDialogAddEditScheduleBinding? = null
    private val binding get() = _binding!!

    private var scheduleId: Long = 0L
    private var selectedHour: Int = 9
    private var selectedMinute: Int = 0

    private var favoritesList: List<FavoriteLocation> = emptyList()
    private var routesList: List<SavedRoute> = emptyList()

    companion object {
        private const val ARG_SCHEDULE_ID = "arg_schedule_id"

        fun newInstance(scheduleId: Long = 0L): AddEditScheduleDialog {
            val dialog = AddEditScheduleDialog()
            val args = Bundle().apply {
                putLong(ARG_SCHEDULE_ID, scheduleId)
            }
            dialog.arguments = args
            return dialog
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scheduleId = arguments?.getLong(ARG_SCHEDULE_ID, 0L) ?: 0L
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = LayoutDialogAddEditScheduleBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ThemeColorManager.applyThemeRecursively(binding.root, requireContext())

        setupRecurrenceSpinner()
        setupTimePicker()
        setupTargetRadioGroup()
        loadDestinationsAndData()

        binding.btnSaveSchedule.setOnClickListener {
            saveSchedule()
        }
    }

    private fun setupRecurrenceSpinner() {
        val types = listOf("DAILY", "HOURLY", "WEEKLY", "MONTHLY", "CUSTOM_INTERVAL", "ONE_TIME")
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, types)
        binding.spinnerRecurrenceType.adapter = adapter
    }

    private fun setupTimePicker() {
        binding.btnPickScheduleTime.setOnClickListener {
            val picker = TimePickerDialog(
                requireContext(),
                { _, hourOfDay, minute ->
                    selectedHour = hourOfDay
                    selectedMinute = minute
                    updateTimeDisplay()
                },
                selectedHour,
                selectedMinute,
                false
            )
            picker.show()
        }
        updateTimeDisplay()
    }

    private fun updateTimeDisplay() {
        val amPm = if (selectedHour < 12) "AM" else "PM"
        val displayHour = if (selectedHour % 12 == 0) 12 else selectedHour % 12
        binding.btnPickScheduleTime.text = String.format(Locale.US, "%02d:%02d %s", displayHour, selectedMinute, amPm)
    }

    private fun setupTargetRadioGroup() {
        binding.rgTargetType.setOnCheckedChangeListener { _, checkedId ->
            updateTargetSpinner(checkedId == R.id.rbTargetLocation)
        }
    }

    private fun loadDestinationsAndData() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getInstance(requireContext())
            favoritesList = db.favoriteDao().getAllFavoritesList()
            // Fetch routes
            val cursor = db.openHelper.readableDatabase.query("SELECT id, name, waypointsJson, waypointsCount, totalDistanceMeters, defaultSpeedKmh, isLooping, createdAt FROM saved_routes")
            val routes = mutableListOf<SavedRoute>()
            while (cursor.moveToNext()) {
                routes.add(
                    SavedRoute(
                        id = cursor.getLong(0),
                        name = cursor.getString(1),
                        waypointsJson = cursor.getString(2),
                        waypointsCount = cursor.getInt(3),
                        totalDistanceMeters = cursor.getDouble(4),
                        defaultSpeedKmh = cursor.getFloat(5),
                        isLooping = cursor.getInt(6) == 1,
                        createdAt = cursor.getLong(7)
                    )
                )
            }
            cursor.close()
            routesList = routes

            val existingSchedule = if (scheduleId > 0L) db.scheduleDao().getScheduleById(scheduleId) else null

            withContext(Dispatchers.Main) {
                updateTargetSpinner(binding.rbTargetLocation.isChecked)

                if (existingSchedule != null) {
                    binding.tvAddEditScheduleTitle.text = "Edit Automation Schedule"
                    binding.etScheduleName.setText(existingSchedule.name)
                    binding.switchScheduleLoop.isChecked = existingSchedule.loop

                    val typeIndex = listOf("DAILY", "HOURLY", "WEEKLY", "MONTHLY", "CUSTOM_INTERVAL", "ONE_TIME")
                        .indexOf(existingSchedule.recurrenceType).coerceAtLeast(0)
                    binding.spinnerRecurrenceType.setSelection(typeIndex)

                    try {
                        val config = JSONObject(existingSchedule.recurrenceConfig)
                        selectedHour = config.optInt("hour", 9)
                        selectedMinute = config.optInt("minute", 0)
                        updateTimeDisplay()
                    } catch (ignored: Exception) {}
                }
            }
        }
    }

    private fun updateTargetSpinner(isLocation: Boolean) {
        val names = if (isLocation) {
            if (favoritesList.isEmpty()) listOf("No favorites (add favorites on map)") else favoritesList.map { "${it.name} (${it.tag})" }
        } else {
            if (routesList.isEmpty()) listOf("No saved routes (create a route first)") else routesList.map { "${it.name} (${it.waypointsCount} pts)" }
        }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, names)
        binding.spinnerTargetItem.adapter = adapter
    }

    private fun saveSchedule() {
        val name = binding.etScheduleName.text?.toString()?.trim()
        if (name.isNullOrEmpty()) {
            binding.tilScheduleName.error = "Name required"
            return
        }

        val recurrenceType = binding.spinnerRecurrenceType.selectedItem.toString()
        val isLocation = binding.rbTargetLocation.isChecked

        val targetId = if (isLocation) {
            val selectedIdx = binding.spinnerTargetItem.selectedItemPosition
            favoritesList.getOrNull(selectedIdx)?.id ?: 0L
        } else {
            val selectedIdx = binding.spinnerTargetItem.selectedItemPosition
            routesList.getOrNull(selectedIdx)?.id ?: 0L
        }

        if (targetId <= 0L) {
            Toast.makeText(requireContext(), "Please select a valid destination", Toast.LENGTH_SHORT).show()
            return
        }

        val targetType = if (isLocation) ScheduleRecurrenceCalculator.TYPE_DAILY.let { "SINGLE_LOCATION" } else "ROUTE"

        val configJson = JSONObject().apply {
            put("hour", selectedHour)
            put("minute", selectedMinute)
            put("intervalMinutes", 60)
        }.toString()

        val isLoop = binding.switchScheduleLoop.isChecked
        val appContext = context?.applicationContext ?: return

        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getInstance(appContext)
            val schedule = ScheduleEntity(
                id = scheduleId,
                name = name,
                enabled = true,
                recurrenceType = recurrenceType,
                recurrenceConfig = configJson,
                loop = isLoop,
                nextTriggerAt = System.currentTimeMillis() + 60_000L
            )

            val step = ScheduleStepEntity(
                id = 0L,
                scheduleId = scheduleId,
                orderIndex = 0,
                targetType = targetType,
                targetId = targetId,
                triggerOffsetMinutes = 0
            )

            val savedId = db.scheduleDao().insertOrUpdateScheduleWithSteps(schedule, listOf(step))
            ScheduleExecutor.scheduleOne(appContext, savedId)

            withContext(Dispatchers.Main) {
                if (isAdded) {
                    dismiss()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
