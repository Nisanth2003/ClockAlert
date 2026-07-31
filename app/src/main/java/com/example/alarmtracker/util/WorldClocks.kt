package com.example.alarmtracker.util

import android.content.Context
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale
import org.json.JSONArray

/**
 * The user's saved world-clock cities, stored as IANA zone ids in SharedPreferences.
 *
 * No database and no city database of our own: [ZoneId] already ships every zone the platform
 * knows about, and the last segment of an id ("Asia/Kolkata" → "Kolkata") is the city name.
 */
object WorldClocks {

    private const val KEY = "world_clock_zones"

    fun all(context: Context): List<String> = try {
        val raw = Prefs.get(context).getString(KEY, "[]")
        val array = JSONArray(raw ?: "[]")
        (0 until array.length()).mapNotNull { array.optString(it).takeIf { id -> id.isNotBlank() } }
            .filter { runCatching { ZoneId.of(it) }.isSuccess } // drop ids this platform dropped
    } catch (_: Exception) {
        emptyList()
    }

    fun add(context: Context, zoneId: String) {
        val current = all(context)
        if (zoneId in current) return
        persist(context, current + zoneId)
    }

    fun remove(context: Context, zoneId: String) {
        persist(context, all(context).filterNot { it == zoneId })
    }

    private fun persist(context: Context, zones: List<String>) {
        val array = JSONArray()
        zones.forEach { array.put(it) }
        Prefs.get(context).edit().putString(KEY, array.toString()).apply()
    }

    /** Every selectable zone, city-name first so the picker reads naturally. */
    fun selectableZones(): List<Zone> = ZoneId.getAvailableZoneIds()
        .asSequence()
        .filter { it.contains('/') && !it.startsWith("Etc/") && !it.startsWith("SystemV/") }
        .map { Zone(it, cityOf(it), regionOf(it)) }
        .sortedBy { it.city.lowercase() }
        .toList()

    data class Zone(val id: String, val city: String, val region: String)

    /** "Asia/Kolkata" → "Kolkata"; "America/Argentina/Buenos_Aires" → "Buenos Aires". */
    fun cityOf(zoneId: String): String =
        zoneId.substringAfterLast('/').replace('_', ' ')

    /** "America/Argentina/Buenos_Aires" → "America · Argentina". */
    fun regionOf(zoneId: String): String =
        zoneId.substringBeforeLast('/').replace('_', ' ').replace("/", " · ")

    /**
     * Human offset of [zoneId] from the phone's own zone right now, e.g. "+9:30" or "same time".
     * Compares actual offsets so DST on either side is already accounted for.
     */
    fun offsetLabel(zoneId: String, now: ZonedDateTime = ZonedDateTime.now()): String {
        val here = now.offset.totalSeconds
        val there = now.withZoneSameInstant(ZoneId.of(zoneId)).offset.totalSeconds
        val diffMinutes = (there - here) / 60
        if (diffMinutes == 0) return "0"
        val sign = if (diffMinutes > 0) "+" else "−"
        val abs = kotlin.math.abs(diffMinutes)
        val hours = abs / 60
        val minutes = abs % 60
        return if (minutes == 0) {
            String.format(Locale.getDefault(), "%s%d h", sign, hours)
        } else {
            String.format(Locale.getDefault(), "%s%d:%02d", sign, hours, minutes)
        }
    }

    /** Short weekday for a zone ("Tue"), used to flag when a city is on a different day. */
    fun dayLabel(zoneId: String, now: ZonedDateTime = ZonedDateTime.now()): String =
        now.withZoneSameInstant(ZoneId.of(zoneId))
            .dayOfWeek
            .getDisplayName(TextStyle.SHORT, Locale.getDefault())
}
