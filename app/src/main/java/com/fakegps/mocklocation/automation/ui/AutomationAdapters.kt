package com.fakegps.mocklocation.automation.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PorterDuff
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.fakegps.mocklocation.automation.data.AutomationLogEntity
import com.fakegps.mocklocation.automation.data.ScheduleEntity
import com.fakegps.mocklocation.automation.data.WifiTriggerEntity
import com.fakegps.mocklocation.databinding.ItemAutomationLogBinding
import com.fakegps.mocklocation.databinding.ItemAutomationScheduleBinding
import com.fakegps.mocklocation.databinding.ItemAutomationWifiTriggerBinding
import com.fakegps.mocklocation.util.ThemeColorManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScheduleAdapter(
    private val onToggle: (ScheduleEntity, Boolean) -> Unit,
    private val onEdit: (ScheduleEntity) -> Unit,
    private val onDelete: (ScheduleEntity) -> Unit
) : ListAdapter<ScheduleEntity, ScheduleAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAutomationScheduleBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemAutomationScheduleBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ScheduleEntity) {
            val context = binding.root.context
            val primaryColor = ThemeColorManager.getPrimaryColor(context)
            val lightTintCsl = ThemeColorManager.getLightTintStateList(context)

            ThemeColorManager.applyThemeRecursively(binding.root, context)

            binding.tvScheduleName.text = item.name
            binding.tvScheduleTypeBadge.text = item.recurrenceType
            binding.tvScheduleTypeBadge.setTextColor(primaryColor)
            binding.tvScheduleTypeBadge.backgroundTintList = lightTintCsl

            binding.ivScheduleIcon.setColorFilter(primaryColor, PorterDuff.Mode.SRC_IN)
            binding.ivScheduleIcon.backgroundTintList = lightTintCsl

            val thumbStateList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf(-android.R.attr.state_checked)),
                intArrayOf(Color.WHITE, Color.parseColor("#94A3B8"))
            )
            val trackStateList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf(-android.R.attr.state_checked)),
                intArrayOf(primaryColor, Color.parseColor("#334155"))
            )
            binding.switchScheduleEnabled.thumbTintList = thumbStateList
            binding.switchScheduleEnabled.trackTintList = trackStateList

            val now = System.currentTimeMillis()
            if (item.nextTriggerAt > now) {
                val sdf = SimpleDateFormat("MMM d, h:mm a", Locale.US)
                binding.tvScheduleDetails.text = "Next: ${sdf.format(Date(item.nextTriggerAt))}"
            } else {
                binding.tvScheduleDetails.text = if (item.enabled) "Pending calculation" else "Schedule disabled"
            }

            binding.tvScheduleLoopBadge.visibility = if (item.loop) View.VISIBLE else View.GONE
            if (item.loop) {
                binding.tvScheduleLoopBadge.setTextColor(primaryColor)
                binding.tvScheduleLoopBadge.backgroundTintList = lightTintCsl
            }

            binding.switchScheduleEnabled.setOnCheckedChangeListener(null)
            binding.switchScheduleEnabled.isChecked = item.enabled
            binding.switchScheduleEnabled.setOnCheckedChangeListener { _, isChecked ->
                onToggle(item, isChecked)
            }

            binding.btnEditSchedule.setOnClickListener { onEdit(item) }
            binding.btnDeleteSchedule.setOnClickListener { onDelete(item) }
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<ScheduleEntity>() {
        override fun areItemsTheSame(oldItem: ScheduleEntity, newItem: ScheduleEntity): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: ScheduleEntity, newItem: ScheduleEntity): Boolean = oldItem == newItem
    }
}

class WifiTriggerAdapter(
    private val onToggle: (WifiTriggerEntity, Boolean) -> Unit,
    private val onDelete: (WifiTriggerEntity) -> Unit
) : ListAdapter<WifiTriggerEntity, WifiTriggerAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAutomationWifiTriggerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemAutomationWifiTriggerBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: WifiTriggerEntity) {
            val context = binding.root.context
            val primaryColor = ThemeColorManager.getPrimaryColor(context)
            val lightTintCsl = ThemeColorManager.getLightTintStateList(context)

            ThemeColorManager.applyThemeRecursively(binding.root, context)

            binding.tvWifiSsid.text = item.ssid
            binding.tvWifiTriggerTypeBadge.text = item.triggerType.replace("_", " ")
            binding.tvWifiTriggerTypeBadge.setTextColor(primaryColor)
            binding.tvWifiTriggerTypeBadge.backgroundTintList = lightTintCsl
            binding.tvWifiTargetSummary.text = "Target #${item.targetId} (${item.targetType})"

            val thumbStateList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf(-android.R.attr.state_checked)),
                intArrayOf(Color.WHITE, Color.parseColor("#94A3B8"))
            )
            val trackStateList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf(-android.R.attr.state_checked)),
                intArrayOf(primaryColor, Color.parseColor("#334155"))
            )
            binding.switchWifiTriggerEnabled.thumbTintList = thumbStateList
            binding.switchWifiTriggerEnabled.trackTintList = trackStateList

            binding.switchWifiTriggerEnabled.setOnCheckedChangeListener(null)
            binding.switchWifiTriggerEnabled.isChecked = item.enabled
            binding.switchWifiTriggerEnabled.setOnCheckedChangeListener { _, isChecked ->
                onToggle(item, isChecked)
            }

            binding.btnDeleteWifiTrigger.setOnClickListener { onDelete(item) }
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<WifiTriggerEntity>() {
        override fun areItemsTheSame(oldItem: WifiTriggerEntity, newItem: WifiTriggerEntity): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: WifiTriggerEntity, newItem: WifiTriggerEntity): Boolean = oldItem == newItem
    }
}

class AutomationLogAdapter : ListAdapter<AutomationLogEntity, AutomationLogAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAutomationLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemAutomationLogBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: AutomationLogEntity) {
            val context = binding.root.context
            val primaryColor = ThemeColorManager.getPrimaryColor(context)
            val lightTintCsl = ThemeColorManager.getLightTintStateList(context)

            ThemeColorManager.applyThemeRecursively(binding.root, context)

            binding.tvLogSourceBadge.text = item.source
            binding.tvLogSourceBadge.setTextColor(primaryColor)
            binding.tvLogSourceBadge.backgroundTintList = lightTintCsl
            binding.tvLogTargetSummary.text = item.targetSummary
            binding.tvLogDetails.text = item.details

            val sdf = SimpleDateFormat("h:mm:ss a", Locale.US)
            binding.tvLogTimestamp.text = sdf.format(Date(item.timestamp))
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<AutomationLogEntity>() {
        override fun areItemsTheSame(oldItem: AutomationLogEntity, newItem: AutomationLogEntity): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: AutomationLogEntity, newItem: AutomationLogEntity): Boolean = oldItem == newItem
    }
}
