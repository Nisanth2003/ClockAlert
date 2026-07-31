package com.example.alarmtracker.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A named, saved map location. */
data class MapFavorite(val name: String, val lat: Double, val lng: Double)

/** Persists the user's favourite map places as JSON in SharedPreferences (no account, local only). */
object MapFavorites {

    private const val KEY = "map_favorites_json"

    fun all(context: Context): List<MapFavorite> {
        val arr = try {
            JSONArray(Prefs.get(context).getString(KEY, "[]"))
        } catch (_: Exception) {
            JSONArray()
        }
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            MapFavorite(o.optString("name"), o.getDouble("lat"), o.getDouble("lng"))
        }
    }

    fun add(context: Context, favorite: MapFavorite) {
        val list = all(context).toMutableList()
        list.add(0, favorite)
        persist(context, list)
    }

    fun removeAt(context: Context, index: Int) {
        val list = all(context).toMutableList()
        if (index in list.indices) {
            list.removeAt(index)
            persist(context, list)
        }
    }

    private fun persist(context: Context, list: List<MapFavorite>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("name", it.name).put("lat", it.lat).put("lng", it.lng)) }
        Prefs.get(context).edit().putString(KEY, arr.toString()).apply()
    }
}
