package com.fakegps.mocklocation.ui.dialogs

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.databinding.ItemIpNodeBinding
import com.fakegps.mocklocation.vpn.IpNode

class IpNodeAdapter(
    private val allNodes: List<IpNode>,
    private var selectedNodeId: String,
    private val onNodeSelected: (IpNode) -> Unit
) : RecyclerView.Adapter<IpNodeAdapter.ViewHolder>() {

    private val displayedNodes = mutableListOf<IpNode>().apply { addAll(allNodes) }

    fun setSelectedNodeId(nodeId: String) {
        selectedNodeId = nodeId
        notifyDataSetChanged()
    }

    fun filter(query: String): Int {
        val trimmed = query.trim().lowercase()
        displayedNodes.clear()
        if (trimmed.isEmpty()) {
            displayedNodes.addAll(allNodes)
        } else {
            val matches = allNodes.filter { node ->
                node.country.lowercase().contains(trimmed) ||
                node.city.lowercase().contains(trimmed) ||
                node.countryCode.lowercase().contains(trimmed) ||
                node.name.lowercase().contains(trimmed) ||
                node.virtualIp.contains(trimmed)
            }
            displayedNodes.addAll(matches)
        }
        notifyDataSetChanged()
        return displayedNodes.size
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemIpNodeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(displayedNodes[position])
    }

    override fun getItemCount(): Int = displayedNodes.size

    inner class ViewHolder(private val binding: ItemIpNodeBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(node: IpNode) {
            val isSelected = node.id == selectedNodeId
            val context = binding.root.context

            binding.tvNodeFlag.text = node.flagEmoji
            binding.tvNodeName.text = "${node.country} (${node.city})"
            binding.tvNodeIp.text = "${node.virtualIp} • 10 Gbps"
            binding.tvNodePing.text = "${node.pingMs}ms"

            if (isSelected) {
                binding.cardIpNode.strokeColor = ContextCompat.getColor(context, R.color.primary)
                binding.cardIpNode.strokeWidth = (2 * context.resources.displayMetrics.density).toInt()
                binding.cardIpNode.setCardBackgroundColor(ContextCompat.getColor(context, R.color.surface_elevated))
                binding.ivSelectedCheck.visibility = View.VISIBLE
                binding.tvNodeName.setTextColor(ContextCompat.getColor(context, R.color.primary))
            } else {
                binding.cardIpNode.strokeColor = ContextCompat.getColor(context, R.color.border_subtle)
                binding.cardIpNode.strokeWidth = (1 * context.resources.displayMetrics.density).toInt()
                binding.cardIpNode.setCardBackgroundColor(ContextCompat.getColor(context, R.color.surface_elevated))
                binding.ivSelectedCheck.visibility = View.GONE
                binding.tvNodeName.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            }

            binding.cardIpNode.setOnClickListener {
                selectedNodeId = node.id
                notifyDataSetChanged()
                onNodeSelected(node)
            }
        }
    }
}
