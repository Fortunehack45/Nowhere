package com.fakegps.mocklocation.ui.dialogs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.data.db.AppDatabase
import com.fakegps.mocklocation.data.db.MockLocationHistory
import com.fakegps.mocklocation.data.db.MockRouteHistory
import com.fakegps.mocklocation.data.preferences.AppSettingsPreferences
import com.fakegps.mocklocation.databinding.ItemLocationHistoryBinding
import com.fakegps.mocklocation.databinding.ItemRouteHistoryBinding
import com.fakegps.mocklocation.databinding.LayoutDialogHistoryBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryBottomSheet @JvmOverloads constructor(
    private var onReuseLocation: ((Double, Double, String) -> Unit)? = null,
    private var onReuseRoute: ((MockRouteHistory) -> Unit)? = null
) : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "HistoryBottomSheet"
    }

    private var _binding: LayoutDialogHistoryBinding? = null
    private val binding get() = _binding!!

    private lateinit var locationAdapter: LocationHistoryAdapter
    private lateinit var routeAdapter: RouteHistoryAdapter
    private lateinit var settingsPrefs: AppSettingsPreferences
    private val db by lazy { AppDatabase.getInstance(requireContext()) }

    private var isLocationTabActive = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = LayoutDialogHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settingsPrefs = AppSettingsPreferences(requireContext())

        setupAdapters()
        setupListeners()
        observeHistory()
        com.fakegps.mocklocation.util.ThemeColorManager.applyThemeRecursively(binding.root, requireContext())
    }

    private fun setupAdapters() {
        locationAdapter = LocationHistoryAdapter(
            onReuse = { item ->
                onReuseLocation?.invoke(item.latitude, item.longitude, item.locationName)
                dismiss()
            },
            onDelete = { item ->
                lifecycleScope.launch(Dispatchers.IO) {
                    db.mockHistoryDao().deleteLocationHistoryById(item.id)
                }
            }
        )
        binding.rvLocationHistory.layoutManager = LinearLayoutManager(requireContext())
        binding.rvLocationHistory.adapter = locationAdapter

        routeAdapter = RouteHistoryAdapter(
            formatDistance = { meters -> settingsPrefs.formatDistance(meters) },
            formatSpeed = { speed -> settingsPrefs.formatSpeed(speed) },
            onReuse = { item ->
                onReuseRoute?.invoke(item)
                dismiss()
            },
            onDelete = { item ->
                lifecycleScope.launch(Dispatchers.IO) {
                    db.mockHistoryDao().deleteRouteHistoryById(item.id)
                }
            }
        )
        binding.rvRouteHistory.layoutManager = LinearLayoutManager(requireContext())
        binding.rvRouteHistory.adapter = routeAdapter
    }

    private fun setupListeners() {
        binding.btnHistoryClose.setOnClickListener {
            dismiss()
        }

        binding.tabLocationHistory.setOnClickListener {
            switchTab(isLocation = true)
        }

        binding.tabRouteHistory.setOnClickListener {
            switchTab(isLocation = false)
        }

        binding.btnHistoryClearAll.setOnClickListener {
            val title = if (isLocationTabActive) "Clear Location History?" else "Clear Route History?"
            val msg = if (isLocationTabActive) {
                "Are you sure you want to delete all recorded mock location destinations?"
            } else {
                "Are you sure you want to delete all recorded route simulation paths?"
            }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("Clear All") { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        if (isLocationTabActive) {
                            db.mockHistoryDao().clearAllLocationHistory()
                        } else {
                            db.mockHistoryDao().clearAllRouteHistory()
                        }
                    }
                    Toast.makeText(requireContext(), "History cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun switchTab(isLocation: Boolean) {
        isLocationTabActive = isLocation
        val white = ContextCompat.getColor(requireContext(), R.color.white)
        val muted = ContextCompat.getColor(requireContext(), R.color.text_muted)
        val whiteSoft = ContextCompat.getColor(requireContext(), R.color.white_soft)

        if (isLocation) {
            binding.tabLocationHistory.setBackgroundResource(R.drawable.bg_pill_active)
            binding.tvLocationTabText.setTextColor(white)
            binding.ivLocationIcon.imageTintList = android.content.res.ColorStateList.valueOf(white)
            binding.tvLocationHistoryCount.setTextColor(whiteSoft)

            binding.tabRouteHistory.background = null
            binding.tvRouteTabText.setTextColor(muted)
            binding.ivRouteIcon.imageTintList = android.content.res.ColorStateList.valueOf(muted)
            binding.tvRouteHistoryCount.setTextColor(muted)

            binding.containerLocationHistory.visibility = View.VISIBLE
            binding.containerRouteHistory.visibility = View.GONE
        } else {
            binding.tabRouteHistory.setBackgroundResource(R.drawable.bg_pill_active)
            binding.tvRouteTabText.setTextColor(white)
            binding.ivRouteIcon.imageTintList = android.content.res.ColorStateList.valueOf(white)
            binding.tvRouteHistoryCount.setTextColor(whiteSoft)

            binding.tabLocationHistory.background = null
            binding.tvLocationTabText.setTextColor(muted)
            binding.ivLocationIcon.imageTintList = android.content.res.ColorStateList.valueOf(muted)
            binding.tvLocationHistoryCount.setTextColor(muted)

            binding.containerLocationHistory.visibility = View.GONE
            binding.containerRouteHistory.visibility = View.VISIBLE
        }
    }

    private fun observeHistory() {
        viewLifecycleOwner.lifecycleScope.launch {
            db.mockHistoryDao().getAllLocationHistoryFlow().collectLatest { list ->
                locationAdapter.submitList(list)
                binding.tvLocationHistoryCount.text = if (list.isNotEmpty()) "(${list.size})" else ""
                binding.tvEmptyLocationHistory.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                binding.rvLocationHistory.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            db.mockHistoryDao().getAllRouteHistoryFlow().collectLatest { list ->
                routeAdapter.submitList(list)
                binding.tvRouteHistoryCount.text = if (list.isNotEmpty()) "(${list.size})" else ""
                binding.tvEmptyRouteHistory.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                binding.rvRouteHistory.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class LocationHistoryAdapter(
        private val onReuse: (MockLocationHistory) -> Unit,
        private val onDelete: (MockLocationHistory) -> Unit
    ) : RecyclerView.Adapter<LocationHistoryAdapter.ViewHolder>() {

        private val items = mutableListOf<MockLocationHistory>()
        private val timeFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())

        fun submitList(newItems: List<MockLocationHistory>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemLocationHistoryBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(private val binding: ItemLocationHistoryBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(item: MockLocationHistory) {
                binding.tvHistoryName.text = item.locationName
                binding.tvHistoryModeBadge.text = item.mode
                binding.tvHistoryCoords.text = String.format(Locale.US, "%.5f, %.5f", item.latitude, item.longitude)
                binding.tvHistoryTime.text = timeFormat.format(Date(item.timestamp))

                binding.btnHistoryReuse.setOnClickListener { onReuse(item) }
                binding.btnHistoryDelete.setOnClickListener { onDelete(item) }
            }
        }
    }

    private class RouteHistoryAdapter(
        private val formatDistance: (Double) -> String,
        private val formatSpeed: (Float) -> String,
        private val onReuse: (MockRouteHistory) -> Unit,
        private val onDelete: (MockRouteHistory) -> Unit
    ) : RecyclerView.Adapter<RouteHistoryAdapter.ViewHolder>() {

        private val items = mutableListOf<MockRouteHistory>()
        private val timeFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())

        fun submitList(newItems: List<MockRouteHistory>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemRouteHistoryBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(private val binding: ItemRouteHistoryBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(item: MockRouteHistory) {
                binding.tvRouteHistoryName.text = item.routeName
                binding.tvRouteHistoryWaypoints.text = item.waypointsCount.toString() + " pts"
                binding.tvRouteHistoryDistance.text = formatDistance(item.totalDistanceMeters)
                binding.tvRouteHistoryTransport.text = item.transportMode + " - " + formatSpeed(item.speedKmh)
                binding.tvRouteHistoryTime.text = timeFormat.format(Date(item.timestamp))

                binding.btnRouteHistoryReuse.setOnClickListener { onReuse(item) }
                binding.btnRouteHistoryDelete.setOnClickListener { onDelete(item) }
            }
        }
    }
}