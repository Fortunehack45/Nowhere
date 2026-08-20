package com.fakegps.mocklocation.ui.routes

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.fakegps.mocklocation.data.db.SavedRoute
import com.fakegps.mocklocation.databinding.BottomSheetSavedRoutesBinding
import com.fakegps.mocklocation.databinding.ItemSavedRouteBinding
import com.fakegps.mocklocation.ui.MainViewModel
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SavedRoutesBottomSheet @JvmOverloads constructor(
    private var onRouteSelected: ((SavedRoute) -> Unit)? = null
) : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "SavedRoutesBottomSheet"
    }

    private var _binding: BottomSheetSavedRoutesBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: SavedRoutesAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetSavedRoutesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = SavedRoutesAdapter(
            onItemClick = { route ->
                onRouteSelected?.invoke(route)
                dismiss()
            },
            onDeleteClick = { route ->
                viewModel.deleteSavedRoute(route)
            },
            onExportClick = { route ->
                exportSavedRoute(route)
            }
        )

        binding.rvSavedRoutes.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSavedRoutes.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.allSavedRoutes.collectLatest { routes ->
                adapter.submitList(routes)
                binding.tvEmptySavedRoutes.visibility = if (routes.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun exportSavedRoute(route: SavedRoute) {
        val points = mutableListOf<com.fakegps.mocklocation.simulator.RoutePoint>()
        try {
            val arr = org.json.JSONArray(route.waypointsJson)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                points.add(
                    com.fakegps.mocklocation.simulator.RoutePoint(
                        latitude = obj.getDouble("lat"),
                        longitude = obj.getDouble("lon"),
                        altitude = obj.optDouble("alt", 0.0),
                        stopDurationSeconds = obj.optInt("stopSec", 0)
                    )
                )
            }

            if (points.isEmpty()) return

            val gpxContent = com.fakegps.mocklocation.simulator.GpxExporter.exportToGpx(points, route.name)
            val exportFile = java.io.File(requireContext().cacheDir, "${route.name.replace("[^a-zA-Z0-9_-]".toRegex(), "_")}.gpx")
            exportFile.writeText(gpxContent)

            val uri = androidx.core.content.FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                exportFile
            )

            val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/gpx+xml"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                putExtra(android.content.Intent.EXTRA_SUBJECT, "GPX: ${route.name}")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(sendIntent, "Export GPX: ${route.name}"))
        } catch (e: Exception) {
            android.widget.Toast.makeText(requireContext(), "Failed to export: ${e.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class SavedRoutesAdapter(
    private val onItemClick: (SavedRoute) -> Unit,
    private val onDeleteClick: (SavedRoute) -> Unit,
    private val onExportClick: (SavedRoute) -> Unit
) : ListAdapter<SavedRoute, SavedRoutesAdapter.ViewHolder>(DiffCallback) {

    inner class ViewHolder(private val binding: ItemSavedRouteBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(route: SavedRoute) {
            binding.tvRouteName.text = route.name
            binding.tvRouteMetrics.text = String.format(
                "%d Waypoints • %.2f km • %.0f km/h",
                route.waypointsCount,
                route.totalDistanceMeters / 1000.0,
                route.defaultSpeedKmh
            )

            binding.root.setOnClickListener { onItemClick(route) }
            binding.btnDeleteRoute.setOnClickListener { onDeleteClick(route) }
            binding.btnExportRoute.setOnClickListener { onExportClick(route) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSavedRouteBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    object DiffCallback : DiffUtil.ItemCallback<SavedRoute>() {
        override fun areItemsTheSame(oldItem: SavedRoute, newItem: SavedRoute): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: SavedRoute, newItem: SavedRoute): Boolean =
            oldItem == newItem
    }
}
