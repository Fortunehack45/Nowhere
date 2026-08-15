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

class SavedRoutesBottomSheet(
    private val onRouteSelected: (SavedRoute) -> Unit
) : BottomSheetDialogFragment() {

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
                onRouteSelected(route)
                dismiss()
            },
            onDeleteClick = { route ->
                viewModel.deleteSavedRoute(route)
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "SavedRoutesBottomSheet"
    }
}

class SavedRoutesAdapter(
    private val onItemClick: (SavedRoute) -> Unit,
    private val onDeleteClick: (SavedRoute) -> Unit
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
