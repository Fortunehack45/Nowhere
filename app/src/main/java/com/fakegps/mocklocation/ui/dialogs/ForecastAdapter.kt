package com.fakegps.mocklocation.ui.dialogs

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.fakegps.mocklocation.databinding.ItemForecastDayBinding
import com.fakegps.mocklocation.weather.DailyForecast

class ForecastAdapter(
    private val items: List<DailyForecast>,
    private val useFahrenheit: Boolean = false
) : RecyclerView.Adapter<ForecastAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemForecastDayBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemForecastDayBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(forecast: DailyForecast) {
            binding.tvDayName.text = forecast.dayOfWeek
            binding.tvDayEmoji.text = forecast.conditionEmoji
            if (useFahrenheit) {
                binding.tvMaxTemp.text = String.format("%.0f°F", forecast.maxTempF)
                binding.tvMinTemp.text = String.format("%.0f°F", forecast.minTempF)
            } else {
                binding.tvMaxTemp.text = String.format("%.0f°C", forecast.maxTempC)
                binding.tvMinTemp.text = String.format("%.0f°C", forecast.minTempC)
            }
        }
    }
}
