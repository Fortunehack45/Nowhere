package com.fakegps.mocklocation.simulator

import com.fakegps.mocklocation.R

enum class TransportMode(
    val id: String,
    val title: String,
    val defaultSpeedKmh: Float,
    val minSpeedKmh: Float,
    val maxSpeedKmh: Float,
    val defaultAltitudeMeters: Double,
    val iconRes: Int
) {
    FOOT("FOOT", "Foot / Run", 5.0f, 1.0f, 15.0f, 2.0, R.drawable.ic_teleport),
    VEHICLE("VEHICLE", "Vehicle", 50.0f, 10.0f, 180.0f, 15.0, R.drawable.ic_route),
    AIRCRAFT("AIRCRAFT", "Aircraft", 600.0f, 100.0f, 1000.0f, 9500.0, R.drawable.ic_my_location),
    SHIP("SHIP", "Ship / Marine", 25.0f, 5.0f, 80.0f, 0.0, R.drawable.ic_favorite);

    companion object {
        fun fromId(id: String): TransportMode = values().firstOrNull { it.id.equals(id, ignoreCase = true) } ?: VEHICLE
    }
}
