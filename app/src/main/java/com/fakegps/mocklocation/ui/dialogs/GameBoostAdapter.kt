package com.fakegps.mocklocation.ui.dialogs

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.fakegps.mocklocation.R
import com.fakegps.mocklocation.databinding.ItemGameBoostBinding

data class GameBoostModel(
    val id: String,
    val name: String,
    val emoji: String,
    val hubInfo: String,
    val pingMs: Int
)

class GameBoostAdapter(
    private val games: List<GameBoostModel>,
    private var activeGameId: String? = null,
    private val onBoostClick: (GameBoostModel) -> Unit
) : RecyclerView.Adapter<GameBoostAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemGameBoostBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemGameBoostBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun getItemCount(): Int = games.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = games[position]
        val isSelected = item.id == activeGameId

        holder.binding.tvGameIcon.text = item.emoji
        holder.binding.tvGameName.text = item.name
        holder.binding.tvGameHub.text = item.hubInfo
        holder.binding.tvGamePing.text = "${item.pingMs} ms"

        val context = holder.itemView.context
        val primaryColor = com.fakegps.mocklocation.util.ThemeColorManager.getPrimaryColor(context)
        val card = holder.itemView as? com.google.android.material.card.MaterialCardView

        if (isSelected) {
            card?.strokeColor = primaryColor
            card?.strokeWidth = (2 * context.resources.displayMetrics.density).toInt()
            holder.binding.btnBoostGame.text = "ACTIVE"
            holder.binding.btnBoostGame.backgroundTintList = android.content.res.ColorStateList.valueOf(primaryColor)
            holder.binding.btnBoostGame.setTextColor(ContextCompat.getColor(context, R.color.white))
        } else {
            card?.strokeColor = ContextCompat.getColor(context, R.color.border_subtle)
            card?.strokeWidth = (1 * context.resources.displayMetrics.density).toInt()
            holder.binding.btnBoostGame.text = "BOOST"
            holder.binding.btnBoostGame.backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.surface_elevated))
            holder.binding.btnBoostGame.setTextColor(primaryColor)
        }

        holder.binding.btnBoostGame.setOnClickListener {
            activeGameId = item.id
            notifyDataSetChanged()
            onBoostClick(item)
        }

        holder.itemView.setOnClickListener {
            activeGameId = item.id
            notifyDataSetChanged()
            onBoostClick(item)
        }
    }

    fun setActiveGame(gameId: String?) {
        activeGameId = gameId
        notifyDataSetChanged()
    }
}
