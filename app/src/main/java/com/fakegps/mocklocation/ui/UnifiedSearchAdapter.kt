package com.fakegps.mocklocation.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.data.db.SearchHistoryItem
import com.fakegps.mocklocation.databinding.ItemSearchSuggestionBinding

sealed class SearchEntry {
    data class LiveResult(
        val title: String,
        val snippet: String,
        val latitude: Double,
        val longitude: Double
    ) : SearchEntry()

    data class History(
        val item: SearchHistoryItem
    ) : SearchEntry()
}

class UnifiedSearchAdapter(
    private val onEntryClicked: (title: String, snippet: String, lat: Double, lon: Double) -> Unit,
    private val onDeleteHistoryClicked: (SearchHistoryItem) -> Unit
) : RecyclerView.Adapter<UnifiedSearchAdapter.ViewHolder>() {

    private val entries = mutableListOf<SearchEntry>()

    fun submitEntries(newEntries: List<SearchEntry>) {
        entries.clear()
        entries.addAll(newEntries)
        notifyDataSetChanged()
    }

    inner class ViewHolder(private val binding: ItemSearchSuggestionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: SearchEntry) {
            when (entry) {
                is SearchEntry.LiveResult -> {
                    binding.tvItemTitle.text = entry.title
                    binding.tvItemSnippet.text = entry.snippet
                    binding.ivItemTypeIcon.setImageResource(R.drawable.ic_search)
                    binding.ivItemTypeIcon.setColorFilter(ContextCompat.getColor(itemView.context, R.color.primary))
                    binding.btnItemDelete.visibility = View.GONE
                    binding.root.setOnClickListener {
                        onEntryClicked(entry.title, entry.snippet, entry.latitude, entry.longitude)
                    }
                }
                is SearchEntry.History -> {
                    binding.tvItemTitle.text = entry.item.title
                    binding.tvItemSnippet.text = entry.item.snippet
                    binding.ivItemTypeIcon.setImageResource(R.drawable.ic_teleport)
                    binding.ivItemTypeIcon.setColorFilter(ContextCompat.getColor(itemView.context, R.color.text_muted))
                    binding.btnItemDelete.visibility = View.VISIBLE
                    binding.btnItemDelete.setOnClickListener {
                        val pos = adapterPosition
                        if (pos != RecyclerView.NO_POSITION && pos < entries.size) {
                            val removedItem = (entries[pos] as? SearchEntry.History)?.item
                            entries.removeAt(pos)
                            notifyItemRemoved(pos)
                            if (removedItem != null) {
                                onDeleteHistoryClicked(removedItem)
                            }
                        }
                    }
                    binding.root.setOnClickListener {
                        onEntryClicked(entry.item.title, entry.item.snippet, entry.item.latitude, entry.item.longitude)
                    }
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSearchSuggestionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(entries[position])
    }

    override fun getItemCount(): Int = entries.size
}
