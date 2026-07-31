package com.example.alarmtracker.util

/**
 * Geometry shared by every destination feature: the [Place] a lookup resolves to, and the
 * haversine distance used to turn "remaining metres" into an ETA. Looking places up lives in
 * [PlaceSearch]; nothing here touches the network or does any background work.
 */
object GeoResolver {

    /**
     * A resolved destination. [label] is a readable one-line address for confirmation; [name] is just
     * the primary name ("Subhash Chowk") when the provider gave one, which is what relevance ranking
     * matches against — a query token hit in the name means far more than one buried in the region.
     */
    data class Place(
        val lat: Double,
        val lng: Double,
        val label: String,
        val name: String? = null
    )

    /** Great-circle distance in metres between two lat/lng points (haversine). */
    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLng / 2) * Math.sin(dLng / 2)
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }
}
