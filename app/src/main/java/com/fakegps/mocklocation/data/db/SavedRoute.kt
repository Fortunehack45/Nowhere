package com.fakegps.mocklocation.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "saved_routes")
data class SavedRoute(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val waypointsJson: String,
    val waypointsCount: Int,
    val totalDistanceMeters: Double,
    val defaultSpeedKmh: Float = 20.0f,
    val isLooping: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)
