package com.fakegps.mocklocation.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favorite_locations")
data class FavoriteLocation(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val tag: String = "Default", // e.g. "Work", "Home", "Test", "Travel"
    val createdAt: Long = System.currentTimeMillis()
)
