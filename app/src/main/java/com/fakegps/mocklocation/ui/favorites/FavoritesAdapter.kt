package com.fakegps.mocklocation.ui.favorites

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.fakegps.mocklocation.data.db.FavoriteLocation
import com.fakegps.mocklocation.databinding.ItemFavoriteBinding
import com.fakegps.mocklocation.util.ThemeColorManager

class FavoritesAdapter(
    private val onItemClick: (FavoriteLocation) -> Unit,
    private val onDeleteClick: (FavoriteLocation) -> Unit
) : ListAdapter<FavoriteLocation, FavoritesAdapter.FavoriteViewHolder>(DiffCallback) {

    inner class FavoriteViewHolder(private val binding: ItemFavoriteBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: FavoriteLocation) {
            val ctx = binding.root.context
            val primaryColor = ThemeColorManager.getPrimaryColor(ctx)
            val primaryCsl = ColorStateList.valueOf(primaryColor)

            binding.tvFavoriteName.text = item.name
            binding.tvCoordinates.text = String.format(java.util.Locale.US, "%.5f, %.5f", item.latitude, item.longitude)

            val tag = item.tag.trim()
            if (tag.isNotEmpty()) {
                binding.chipTag.visibility = View.VISIBLE
                binding.chipTag.text = tag.uppercase(java.util.Locale.US)
            } else {
                binding.chipTag.visibility = View.GONE
            }

            binding.btnTeleport.backgroundTintList = primaryCsl
            binding.btnTeleport.setOnClickListener { onItemClick(item) }
            binding.root.setOnClickListener { onItemClick(item) }
            binding.btnDelete.setOnClickListener { onDeleteClick(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FavoriteViewHolder {
        val binding = ItemFavoriteBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return FavoriteViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FavoriteViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    object DiffCallback : DiffUtil.ItemCallback<FavoriteLocation>() {
        override fun areItemsTheSame(oldItem: FavoriteLocation, newItem: FavoriteLocation): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: FavoriteLocation, newItem: FavoriteLocation): Boolean =
            oldItem == newItem
    }
}
