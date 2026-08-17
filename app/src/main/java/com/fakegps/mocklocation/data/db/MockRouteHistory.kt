package com.fakegps.mocklocation.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "mock_route_history")
data class MockRouteHistory(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val routeName: String,
    val waypointsJson: String,
    val waypointsCount: Int,
    val totalDistanceMeters: Double,
    val speedKmh: Float = 20.0f,
    val isLooping: Boolean = true,
    val transportMode: String = "CAR",
    val timestamp: Long = System.currentTimeMillis()
)
