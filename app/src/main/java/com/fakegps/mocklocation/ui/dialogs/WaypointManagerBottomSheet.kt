package com.fakegps.mocklocation.ui.dialogs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.databinding.ItemWaypointManageBinding
import com.fakegps.mocklocation.databinding.LayoutWaypointManagerBinding
import com.fakegps.mocklocation.simulator.RoutePoint
import com.fakegps.mocklocation.ui.MainViewModel
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.Locale

class WaypointManagerBottomSheet : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "WaypointManagerBottomSheet"
    }

    private var _binding: LayoutWaypointManagerBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    private lateinit var adapter: WaypointManageAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = LayoutWaypointManagerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = WaypointManageAdapter(
            onStartDrag = { viewHolder ->
                itemTouchHelper.startDrag(viewHolder)
            },
            onDeleteClick = { index ->
                viewModel.removeWaypointAt(index)
            },
            onDwellClick = { index, currentDuration ->
                showDwellPicker(index, currentDuration)
            }
        )

        binding.rvWaypoints.layoutManager = LinearLayoutManager(requireContext())
        binding.rvWaypoints.adapter = adapter

        // Setup ItemTouchHelper for drag-to-reorder
        val touchCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            private var dragFrom = -1
            private var dragTo = -1

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPos = viewHolder.adapterPosition
                val toPos = target.adapterPosition
                if (fromPos == RecyclerView.NO_POSITION || toPos == RecyclerView.NO_POSITION) return false

                if (dragFrom == -1) dragFrom = fromPos
                dragTo = toPos

                adapter.onItemMoved(fromPos, toPos)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                if (dragFrom != -1 && dragTo != -1 && dragFrom != dragTo) {
                    viewModel.reorderWaypoints(dragFrom, dragTo)
                }
                dragFrom = -1
                dragTo = -1
            }

            override fun isLongPressDragEnabled(): Boolean = false
        }

        itemTouchHelper = ItemTouchHelper(touchCallback)
        itemTouchHelper.attachToRecyclerView(binding.rvWaypoints)

        // Button Listeners
        binding.btnCloseWaypointManager.setOnClickListener { dismiss() }

        binding.btnSheetUndo.setOnClickListener { viewModel.undoRouteWaypoint() }
        binding.btnSheetRedo.setOnClickListener { viewModel.redoRouteWaypoint() }
        binding.btnSheetReverse.setOnClickListener { viewModel.reverseRouteWaypoints() }
        binding.btnSheetClear.setOnClickListener { viewModel.clearRouteWaypoints() }

        // Observe UI State
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                val waypoints = if (state.userKeypoints.isNotEmpty()) state.userKeypoints else state.routeWaypoints
                adapter.setItems(waypoints)

                binding.tvEmptyWaypoints.visibility = if (waypoints.isEmpty()) View.VISIBLE else View.GONE
                binding.rvWaypoints.visibility = if (waypoints.isEmpty()) View.GONE else View.VISIBLE

                binding.tvWaypointManagerTitle.text = "Route Waypoints (${waypoints.size})"

                binding.btnSheetUndo.isEnabled = state.canUndoRoute
                binding.btnSheetUndo.alpha = if (state.canUndoRoute) 1.0f else 0.4f

                binding.btnSheetRedo.isEnabled = state.canRedoRoute
                binding.btnSheetRedo.alpha = if (state.canRedoRoute) 1.0f else 0.4f
            }
        }
    }

    private fun showDwellPicker(index: Int, currentDuration: Int) {
        val options = arrayOf(
            "Pass-through (0s)",
            "15 Seconds",
            "30 Seconds",
            "1 Minute (60s)",
            "2 Minutes (120s)",
            "5 Minutes (300s)",
            "Custom Seconds..."
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Waypoint #${index + 1} Stop Duration")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> viewModel.updateWaypointStopDuration(index, 0)
                    1 -> viewModel.updateWaypointStopDuration(index, 15)
                    2 -> viewModel.updateWaypointStopDuration(index, 30)
                    3 -> viewModel.updateWaypointStopDuration(index, 60)
                    4 -> viewModel.updateWaypointStopDuration(index, 120)
                    5 -> viewModel.updateWaypointStopDuration(index, 300)
                    6 -> showCustomDwellDialog(index, currentDuration)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCustomDwellDialog(index: Int, currentDuration: Int) {
        val input = EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "Seconds (e.g. 45)"
            setText(if (currentDuration > 0) currentDuration.toString() else "")
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Custom Stop Duration")
            .setMessage("Set duration (in seconds) to pause at Waypoint #${index + 1}:")
            .setView(input)
            .setPositiveButton("Set") { _, _ ->
                val seconds = input.text.toString().trim().toIntOrNull() ?: 0
                viewModel.updateWaypointStopDuration(index, seconds)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class WaypointManageAdapter(
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
    private val onDeleteClick: (Int) -> Unit,
    private val onDwellClick: (Int, Int) -> Unit
) : RecyclerView.Adapter<WaypointManageAdapter.ViewHolder>() {

    private val items = mutableListOf<RoutePoint>()

    fun setItems(newItems: List<RoutePoint>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun onItemMoved(from: Int, to: Int) {
        if (from in items.indices && to in items.indices) {
            Collections.swap(items, from, to)
            notifyItemMoved(from, to)
            notifyItemChanged(from)
            notifyItemChanged(to)
        }
    }

    inner class ViewHolder(private val binding: ItemWaypointManageBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(point: RoutePoint, position: Int) {
            binding.tvWaypointIndexBadge.text = (position + 1).toString()
            binding.tvWaypointCoords.text = String.format(Locale.US, "%.5f, %.5f", point.latitude, point.longitude)

            if (point.stopDurationSeconds > 0) {
                binding.tvDwellStatus.text = "⏱️ Stop for ${point.stopDurationSeconds}s"
                binding.btnSetDwell.text = "${point.stopDurationSeconds}s"
            } else {
                binding.tvDwellStatus.text = "Pass-through (0s)"
                binding.btnSetDwell.text = "Stop: 0s"
            }

            binding.ivDragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    onStartDrag(this)
                }
                false
            }

            binding.btnDeleteWaypoint.setOnClickListener {
                if (adapterPosition != RecyclerView.NO_POSITION) {
                    onDeleteClick(adapterPosition)
                }
            }

            binding.btnSetDwell.setOnClickListener {
                if (adapterPosition != RecyclerView.NO_POSITION) {
                    onDwellClick(adapterPosition, point.stopDurationSeconds)
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemWaypointManageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], position)
    }

    override fun getItemCount(): Int = items.size
}
