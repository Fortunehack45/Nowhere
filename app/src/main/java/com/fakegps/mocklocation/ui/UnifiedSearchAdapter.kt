package com.fakegps.mocklocation.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.data.db.SearchHistoryItem
import com.fakegps.mocklocation.databinding.ItemSearchSuggestionBinding
import com.fakegps.mocklocation.engine.GeoUtils
import java.util.Locale

sealed class SearchEntry {
    data class LiveResult(
        val title: String,
        val snippet: String,
        val latitude: Double,
        val longitude: Double,
        val category: String? = null
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
    private var referenceLat: Double? = null
    private var referenceLon: Double? = null

    fun setReferenceLocation(lat: Double, lon: Double) {
        referenceLat = lat
        referenceLon = lon
    }

    fun submitEntries(newEntries: List<SearchEntry>) {
        entries.clear()
        entries.addAll(newEntries)
        notifyDataSetChanged()
    }

    inner class ViewHolder(private val binding: ItemSearchSuggestionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: SearchEntry) {
            val context = itemView.context
            val primaryColor = com.fakegps.mocklocation.util.ThemeColorManager.getPrimaryColor(context)
            val lightTintCsl = com.fakegps.mocklocation.util.ThemeColorManager.getLightTintStateList(context)

            when (entry) {
                is SearchEntry.LiveResult -> {
                    binding.tvItemTitle.text = entry.title
                    binding.tvItemSnippet.text = entry.snippet
                    binding.btnItemDelete.visibility = View.GONE

                    // Pick contextual icon based on title/snippet/category
                    val lowerText = "${entry.title} ${entry.snippet} ${entry.category ?: ""}".lowercase(Locale.ROOT)
                    val iconRes = when {
                        lowerText.contains("airport") || lowerText.contains("aerodrome") || lowerText.contains("airfield") -> R.drawable.ic_airplane
                        lowerText.contains("coordinates:") || lowerText.contains("lat/lon") -> R.drawable.ic_teleport
                        lowerText.contains("street") || lowerText.contains("avenue") || lowerText.contains("road") || lowerText.contains("blvd") -> R.drawable.ic_map_street
                        lowerText.contains("mountain") || lowerText.contains("peak") || lowerText.contains("canyon") || lowerText.contains("falls") -> R.drawable.ic_terrain_mountains
                        lowerText.contains("city") || lowerText.contains("capital") || lowerText.contains("country") -> R.drawable.ic_globe
                        else -> R.drawable.ic_location_pin
                    }

                    binding.ivItemTypeIcon.setImageResource(iconRes)
                    binding.ivItemTypeIcon.setColorFilter(primaryColor)

                    // Compute distance badge if reference location available
                    val rLat = referenceLat
                    val rLon = referenceLon
                    if (rLat != null && rLon != null && (rLat != 0.0 || rLon != 0.0)) {
                        val distMeters = GeoUtils.calculateDistanceMeters(rLat, rLon, entry.latitude, entry.longitude)
                        val formattedDist = if (distMeters >= 1000.0) {
                            String.format(Locale.US, "%,.0f km", distMeters / 1000.0)
                        } else {
                            String.format(Locale.US, "%d m", distMeters.toInt())
                        }
                        binding.tvItemDistance.text = formattedDist
                        binding.tvItemDistance.setTextColor(primaryColor)
                        binding.tvItemDistance.backgroundTintList = lightTintCsl
                        binding.tvItemDistance.visibility = View.VISIBLE
                    } else {
                        binding.tvItemDistance.visibility = View.GONE
                    }

                    binding.root.setOnClickListener {
                        onEntryClicked(entry.title, entry.snippet, entry.latitude, entry.longitude)
                    }
                }
                is SearchEntry.History -> {
                    binding.tvItemTitle.text = entry.item.title
                    binding.tvItemSnippet.text = entry.item.snippet
                    binding.ivItemTypeIcon.setImageResource(R.drawable.ic_history)
                    binding.ivItemTypeIcon.setColorFilter(ContextCompat.getColor(context, R.color.text_muted))
                    binding.btnItemDelete.visibility = View.VISIBLE

                    val rLat = referenceLat
                    val rLon = referenceLon
                    if (rLat != null && rLon != null && (rLat != 0.0 || rLon != 0.0)) {
                        val distMeters = GeoUtils.calculateDistanceMeters(rLat, rLon, entry.item.latitude, entry.item.longitude)
                        val formattedDist = if (distMeters >= 1000.0) {
                            String.format(Locale.US, "%,.0f km", distMeters / 1000.0)
                        } else {
                            String.format(Locale.US, "%d m", distMeters.toInt())
                        }
                        binding.tvItemDistance.text = formattedDist
                        binding.tvItemDistance.setTextColor(ContextCompat.getColor(context, R.color.text_muted))
                        binding.tvItemDistance.visibility = View.VISIBLE
                    } else {
                        binding.tvItemDistance.visibility = View.GONE
                    }

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
