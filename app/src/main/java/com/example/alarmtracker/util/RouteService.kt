package com.example.alarmtracker.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

/**
 * Road route between two points — the blue line on the map picker, and the "about N min" the alarm
 * editor offers to adopt as the alarm time.
 *
 * Keyless, like everything else here: OSRM's public HTTP API needs no account, key or billing, so
 * the app's zero-key posture is intact. Two hosts are tried because both are volunteer-run and
 * either can be down; a null result simply means "no road route right now" and every caller falls
 * back to the straight-line distance.
 *
 * On demand only — a request is made when the user picks a destination, never in the background.
 * (The live ETA tracker deliberately stays on straight-line maths: it wakes up while the phone is
 * idle, and network calls there would cost battery for a number that only refines an estimate.)
 */
object RouteService {

    /** One shape point of the route. */
    data class Point(val lat: Double, val lng: Double)

    data class Route(
        val points: List<Point>,
        val distanceMeters: Double,
        val durationSeconds: Double
    ) {
        val minutes: Int get() = (durationSeconds / 60.0).toInt().coerceAtLeast(1)
    }

    /** FOSSGIS runs this one for OSM-ecosystem apps; the project's own demo server is the backup. */
    private val HOSTS = listOf(
        "https://routing.openstreetmap.de/routed-car",
        "https://router.project-osrm.org"
    )

    /** Beyond this a road route is meaningless for an alarm (and slow to compute). */
    const val MAX_ROUTE_DISTANCE_M = 500_000.0

    /**
     * The driving route from ([fromLat], [fromLng]) to ([toLat], [toLng]), or null if neither host
     * answered, no road connects them, or they are further apart than [MAX_ROUTE_DISTANCE_M].
     */
    suspend fun driving(
        fromLat: Double,
        fromLng: Double,
        toLat: Double,
        toLng: Double
    ): Route? = withContext(Dispatchers.IO) {
        if (GeoResolver.distanceMeters(fromLat, fromLng, toLat, toLng) > MAX_ROUTE_DISTANCE_M) {
            return@withContext null
        }
        // OSRM takes coordinates as lon,lat.
        val path = String.format(
            Locale.US,
            "/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=polyline" +
                "&alternatives=false&steps=false",
            fromLng, fromLat, toLng, toLat
        )
        for (host in HOSTS) {
            val body = Http.get(host + path) ?: continue
            parse(body)?.let { return@withContext it }
        }
        null
    }

    private fun parse(body: String): Route? {
        return try {
            val json = JSONObject(body)
            if (json.optString("code") != "Ok") return null
            val route = json.optJSONArray("routes")?.optJSONObject(0) ?: return null
            val geometry = route.optString("geometry").takeIf { it.isNotBlank() } ?: return null
            val points = decodePolyline(geometry)
            if (points.size < 2) return null
            Route(points, route.optDouble("distance", 0.0), route.optDouble("duration", 0.0))
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Google's encoded-polyline format at the default precision of 5, which is what OSRM returns
     * for `geometries=polyline`. Each coordinate is a zig-zag-encoded delta from the previous one.
     */
    private fun decodePolyline(encoded: String): List<Point> {
        val points = ArrayList<Point>(encoded.length / 4)
        var index = 0
        var lat = 0
        var lng = 0
        while (index < encoded.length) {
            var shift = 0
            var result = 0
            var b: Int
            do {
                if (index >= encoded.length) return points
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1

            shift = 0
            result = 0
            do {
                if (index >= encoded.length) return points
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lng += if (result and 1 != 0) (result shr 1).inv() else result shr 1

            points.add(Point(lat / 1e5, lng / 1e5))
        }
        return points
    }
}
