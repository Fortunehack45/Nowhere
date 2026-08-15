package com.fakegps.mocklocation.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {

    @Query("SELECT * FROM favorite_locations ORDER BY createdAt DESC")
    fun getAllFavoritesFlow(): Flow<List<FavoriteLocation>>

    @Query("SELECT * FROM favorite_locations ORDER BY createdAt DESC")
    suspend fun getAllFavoritesList(): List<FavoriteLocation>

    @Query("SELECT * FROM favorite_locations WHERE tag = :tag ORDER BY createdAt DESC")
    fun getFavoritesByTagFlow(tag: String): Flow<List<FavoriteLocation>>

    @Query("SELECT * FROM favorite_locations WHERE name LIKE '%' || :query || '%' OR tag LIKE '%' || :query || '%' ORDER BY createdAt DESC")
    fun searchFavoritesFlow(query: String): Flow<List<FavoriteLocation>>

    @Query("SELECT DISTINCT tag FROM favorite_locations ORDER BY tag ASC")
    fun getAllTagsFlow(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorite(favorite: FavoriteLocation): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(favorites: List<FavoriteLocation>)

    @Update
    suspend fun updateFavorite(favorite: FavoriteLocation)

    @Delete
    suspend fun deleteFavorite(favorite: FavoriteLocation)

    @Query("DELETE FROM favorite_locations WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM favorite_locations")
    suspend fun clearAll()
}
