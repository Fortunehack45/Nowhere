package com.fakegps.mocklocation.ui.favorites

import com.fakegps.mocklocation.data.db.FavoriteLocation
import org.json.JSONArray
import org.json.JSONObject

object JsonBackupHelper {

    fun exportToJson(favorites: List<FavoriteLocation>): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("exportedAt", System.currentTimeMillis())

        val array = JSONArray()
        for (item in favorites) {
            val obj = JSONObject().apply {
                put("name", item.name)
                put("latitude", item.latitude)
                put("longitude", item.longitude)
                put("tag", item.tag)
                put("createdAt", item.createdAt)
            }
            array.put(obj)
        }
        root.put("favorites", array)
        return root.toString(2)
    }

    fun importFromJson(jsonString: String): List<FavoriteLocation> {
        val list = mutableListOf<FavoriteLocation>()
        val root = JSONObject(jsonString)
        val array = root.optJSONArray("favorites") ?: return list

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val name = obj.getString("name")
            val lat = obj.getDouble("latitude")
            val lon = obj.getDouble("longitude")
            val tag = obj.optString("tag", "Default")
            val createdAt = obj.optLong("createdAt", System.currentTimeMillis())

            list.add(
                FavoriteLocation(
                    name = name,
                    latitude = lat,
                    longitude = lon,
                    tag = tag,
                    createdAt = createdAt
                )
            )
        }
        return list
    }
}
