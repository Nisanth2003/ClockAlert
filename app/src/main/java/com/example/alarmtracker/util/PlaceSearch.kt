package com.example.alarmtracker.util

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Place lookup for the "Arrive at a place" alarm and the map picker's search bar.
 *
 * Two keyless providers, both consulted on every search:
 *  1. the framework [Geocoder] — free and often good, but backed by a vendor service that is missing
 *     or silently broken on plenty of devices (notably some MIUI/HyperOS and de-Googled ROMs, where it
 *     returns an empty list for every query), and whose bounding-box hint it may simply ignore;
 *  2. Photon (photon.komoot.io) — a keyless OpenStreetMap search endpoint with real location biasing.
 *     No API key, no billing, no account, so the app's zero-key posture is intact.
 *
 * They are queried IN PARALLEL and their results MERGED, then ranked. That structure is the fix for
 * the bug this replaced: the old code returned the first provider that answered at all, so on a
 * device with a working geocoder, "Subhash Chowk" came back as two junctions in Rajasthan (230 km and
 * 280 km away) and the provider that had the Gurugram one ranked first was never even asked.
 *
 * Ranking blends three signals — how well the text matches, how far away it is (exponential decay
 * from the bias point), and where each provider put it in its own list — so a nearby exact match wins
 * without a distant exact match being hidden. Nothing is dropped for being far; callers display the
 * distance, so "230 km away" is visible rather than silently wrong.
 *
 * Everything here is one-shot and on demand. No polling, no background work.
 */
object PlaceSearch {

    /**
     * An Open Location Code at the start of an address line: 4–8 then 2–3 characters from the code's
     * own 20-character alphabet, either side of a '+'.
     */
    private val PLUS_CODE =
        Regex("^[23456789CFGHJMPQRVWX]{4,8}\\+[23456789CFGHJMPQRVWX]{2,3}\\b,?", RegexOption.IGNORE_CASE)

    private val TOKEN_SPLIT = Regex("[^\\p{L}\\p{N}]+")

    private const val PHOTON_SEARCH = "https://photon.komoot.io/api/"
    private const val PHOTON_REVERSE = "https://photon.komoot.io/reverse"

    /**
     * Photon's own bias knob. 0.6 at zoom 12 was picked by measurement, not taste: it puts the local
     * "Subhash Chowk" first, while 1.0 at zoom 14 collapses back to worldwide ordering. Do not "turn
     * it up" — re-measure if you change it.
     */
    private const val PHOTON_BIAS_SCALE = "0.6"
    private const val PHOTON_BIAS_ZOOM = "12"

    /** Both providers run in parallel, so a hung vendor geocoder costs this much and no more. */
    private const val GEOCODER_TIMEOUT_MS = 4_500L

    /** Half-size of the box a biased [Geocoder] lookup is restricted to (~220 km). */
    private const val BIAS_BOX_DEGREES = 2.0

    // ---- Relevance weights (with a bias point) ----
    private const val W_TEXT = 0.5
    private const val W_DISTANCE = 0.4
    private const val W_PROVIDER_RANK = 0.1

    // ---- Relevance weights (no bias point at all) ----
    private const val W_TEXT_ONLY = 0.8
    private const val W_PROVIDER_RANK_ONLY = 0.2

    /**
     * Distance half-life for scoring, in metres. At 25 km a candidate keeps ~37% of its distance
     * score, at 100 km ~2%, at 250 km almost none — which is what separates "the next suburb over"
     * from "a same-named junction in the next state".
     */
    private const val DISTANCE_DECAY_M = 25_000.0

    /**
     * Two hits closer together than this are treated as the same place. Justified by the product: the
     * arrival ring itself is never smaller than 150 m, so they would fire the same alarm anyway.
     */
    private const val DUPLICATE_M = 120.0

    /** Both providers independently returning a place is real evidence, so it gets a nudge. */
    private const val AGREEMENT_BONUS = 0.08

    /** One candidate on its way through ranking. */
    private class Candidate(
        val place: GeoResolver.Place,
        /** Position in its own provider's list — that provider's own relevance opinion. */
        val providerRank: Int
    ) {
        var score = 0.0
        var agreed = false
    }

    /**
     * Up to [limit] candidate places for [query], most relevant first. Pass [biasLat]/[biasLng] —
     * the user's position, or wherever the map is looking — or the search is worldwide and a local
     * junction competes on equal terms with its namesake two states away.
     *
     * Empty when nothing matched or neither provider is reachable; callers should say "not found"
     * rather than assume an error.
     */
    suspend fun search(
        context: Context,
        query: String,
        limit: Int = 6,
        biasLat: Double? = null,
        biasLng: Double? = null
    ): List<GeoResolver.Place> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val bias = if (biasLat != null && biasLng != null && !biasLat.isNaN() && !biasLng.isNaN()) {
            biasLat to biasLng
        } else {
            null
        }
        val candidates = coroutineScope {
            // All in parallel: the whole search costs the slowest source, not the sum. It also means a
            // silently-empty geocoder no longer delays the source that has the answer.
            val geocoded = async { geocoderSearch(context, trimmed, limit, bias) }
            val wide = async { photonSearch(trimmed, limit, bias, restrictToLocal = false) }
            // A HARD-restricted pass around the user. Soft biasing cannot rescue a misspelling: for
            // "subash chowk" the fuzzy engine prefers literal "Subash Chowk" matches 200-1300 km away
            // and never surfaces the local "Subhash Chowk" at all. Restricted to the local box, that
            // same engine returns it first. This is what makes typos and alternate spellings - very
            // common in transliterated names - resolve to the place next door.
            val local = if (bias == null) null else {
                async { photonSearch(trimmed, limit, bias, restrictToLocal = true) }
            }
            val merged = ArrayList<Candidate>()
            local?.await()?.forEachIndexed { index, place -> merged += Candidate(place, index) }
            geocoded.await().forEachIndexed { index, place -> merged += Candidate(place, index) }
            wide.await().forEachIndexed { index, place -> merged += Candidate(place, index) }
            merged
        }
        return rank(candidates, trimmed, bias, limit)
    }

    /** A readable name for a pinned point ("MG Road, Bengaluru"), or null if neither provider knows. */
    suspend fun reverse(context: Context, lat: Double, lng: Double): String? =
        geocoderReverse(context, lat, lng) ?: photonReverse(lat, lng)

    // ---- Ranking ----

    private fun rank(
        candidates: List<Candidate>,
        query: String,
        bias: Pair<Double, Double>?,
        limit: Int
    ): List<GeoResolver.Place> {
        if (candidates.isEmpty()) return emptyList()
        val tokens = tokenize(query)
        val phrase = normalize(query)
        for (candidate in candidates) {
            val text = textScore(candidate.place, tokens, phrase)
            val providerRank = 1.0 / (1.0 + candidate.providerRank)
            candidate.score = if (bias == null) {
                W_TEXT_ONLY * text + W_PROVIDER_RANK_ONLY * providerRank
            } else {
                val metres = GeoResolver.distanceMeters(
                    bias.first, bias.second, candidate.place.lat, candidate.place.lng
                )
                W_TEXT * text +
                    W_DISTANCE * exp(-metres / DISTANCE_DECAY_M) +
                    W_PROVIDER_RANK * providerRank
            }
        }
        // Highest score first, then greedily keep one candidate per real-world place. Because the
        // list is already sorted, the survivor of a duplicate pair is the better-scoring one.
        val kept = ArrayList<Candidate>(candidates.size)
        for (candidate in candidates.sortedByDescending { it.score }) {
            val twin = kept.firstOrNull {
                GeoResolver.distanceMeters(
                    it.place.lat, it.place.lng, candidate.place.lat, candidate.place.lng
                ) < DUPLICATE_M
            }
            if (twin == null) kept += candidate else twin.agreed = true
        }
        return kept
            .sortedByDescending { it.score + if (it.agreed) AGREEMENT_BONUS else 0.0 }
            .take(limit)
            .map { it.place }
    }

    /**
     * How well a candidate matches what was typed. Tokens found in the place's NAME count for far
     * more than tokens found anywhere in the address, so "Subhash Chowk" beats "Netaji Subhash Marg"
     * and "Labour Chowk"; squaring the matched fraction makes a partial match markedly weaker than a
     * complete one instead of merely slightly worse.
     */
    private fun textScore(
        place: GeoResolver.Place,
        tokens: List<String>,
        phrase: String
    ): Double {
        val name = normalize(place.name ?: place.label.substringBefore(','))
        val label = normalize(place.label)
        if (tokens.isEmpty()) return 0.5
        val nameWords = words(name)
        val labelWords = words(label)
        val inName = tokens.sumOf { tokenScore(it, name, nameWords) } / tokens.size
        val inLabel = tokens.sumOf { tokenScore(it, label, labelWords) } / tokens.size
        val base = max(inName * inName, inLabel * inLabel * LABEL_MATCH_WEIGHT)
        val bonus = when {
            name == phrase || withinEdits(name, phrase, editBudget(phrase.length)) -> 0.35
            name.startsWith(phrase) -> 0.20
            name.contains(phrase) -> 0.12
            else -> 0.0
        }
        return min(1.0, base + bonus)
    }

    /**
     * How well one query token matches a candidate's text: a plain substring hit is worth full marks,
     * a near-miss slightly less. The near-miss is the point - "subash" has to match "Subhash", and
     * transliterated names get spelt several ways by different people.
     */
    private fun tokenScore(token: String, full: String, fullWords: List<String>): Double {
        if (full.contains(token)) return 1.0
        val budget = editBudget(token.length)
        if (budget > 0 && fullWords.any { withinEdits(token, it, budget) }) return FUZZY_TOKEN_SCORE
        return 0.0
    }

    /**
     * Edits allowed before two words count as different. Nothing is allowed for very short tokens,
     * where a single edit changes the word entirely ("bank" vs "tank").
     */
    private fun editBudget(length: Int): Int = when {
        length >= 8 -> 2
        length >= 4 -> 1
        else -> 0
    }

    /** Levenshtein distance, abandoned as soon as it cannot come in under [budget]. */
    private fun withinEdits(a: String, b: String, budget: Int): Boolean {
        if (a == b) return true
        if (budget <= 0) return false
        if (abs(a.length - b.length) > budget) return false
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            var rowMin = i
            for (j in 1..b.length) {
                val substitution = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = min(
                    min(current[j - 1] + 1, previous[j] + 1),
                    previous[j - 1] + substitution
                )
                if (current[j] < rowMin) rowMin = current[j]
            }
            // Every path through this row already costs more than we can afford.
            if (rowMin > budget) return false
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length] <= budget
    }

    private fun words(text: String): List<String> =
        text.split(TOKEN_SPLIT).filter { it.isNotEmpty() }

    private fun tokenize(query: String): List<String> =
        normalize(query).split(TOKEN_SPLIT).filter { it.length >= 2 }

    private fun normalize(text: String): String = text.lowercase(Locale.ROOT).trim()

    // ---- Framework Geocoder ----

    private suspend fun geocoderSearch(
        context: Context,
        query: String,
        limit: Int,
        bias: Pair<Double, Double>?
    ): List<GeoResolver.Place> {
        if (!Geocoder.isPresent()) return emptyList()
        val geocoder = Geocoder(context, Locale.getDefault())
        // A box around the bias point. The framework treats it as a hint and does return results
        // outside it, which is exactly why ranking — not the box — is what enforces "nearby wins".
        val box = bias?.let { (lat, lng) ->
            val dLng = BIAS_BOX_DEGREES / cos(Math.toRadians(lat)).coerceAtLeast(0.05)
            doubleArrayOf(
                (lat - BIAS_BOX_DEGREES).coerceAtLeast(-89.9),
                (lng - dLng).coerceAtLeast(-179.9),
                (lat + BIAS_BOX_DEGREES).coerceAtMost(89.9),
                (lng + dLng).coerceAtMost(179.9)
            )
        }
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                // A broken vendor geocoder can accept the request and simply never call back, which
                // would hang the caller (and its spinner) forever. Time out and let Photon answer.
                withTimeoutOrNull(GEOCODER_TIMEOUT_MS) {
                    suspendCancellableCoroutine { cont ->
                        val listener = object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<Address>) {
                                cont.resume(addresses.mapNotNull { it.toPlace() })
                            }

                            override fun onError(errorMessage: String?) = cont.resume(emptyList())
                        }
                        if (box != null) {
                            geocoder.getFromLocationName(
                                query, limit, box[0], box[1], box[2], box[3], listener
                            )
                        } else {
                            geocoder.getFromLocationName(query, limit, listener)
                        }
                    }
                }.orEmpty()
            } else {
                withTimeoutOrNull(GEOCODER_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) {
                        @Suppress("DEPRECATION")
                        val addresses = if (box != null) {
                            geocoder.getFromLocationName(query, limit, box[0], box[1], box[2], box[3])
                        } else {
                            geocoder.getFromLocationName(query, limit)
                        }
                        addresses?.mapNotNull { it.toPlace() }.orEmpty()
                    }
                }.orEmpty()
            }
        } catch (_: IOException) {
            emptyList()
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
    }

    private suspend fun geocoderReverse(context: Context, lat: Double, lng: Double): String? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.getDefault())
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                withTimeoutOrNull(GEOCODER_TIMEOUT_MS) {
                    suspendCancellableCoroutine { cont ->
                        geocoder.getFromLocation(lat, lng, 1, object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<Address>) {
                                cont.resume(addresses.firstOrNull()?.readableLabel())
                            }

                            override fun onError(errorMessage: String?) = cont.resume(null)
                        })
                    }
                }
            } else {
                withContext(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocation(lat, lng, 1)?.firstOrNull()?.readableLabel()
                }
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun Address.toPlace(): GeoResolver.Place? {
        if (!hasLatitude() || !hasLongitude()) return null
        return GeoResolver.Place(latitude, longitude, readableLabel(), primaryName())
    }

    /** The bit a user would call the place, for relevance matching. */
    private fun Address.primaryName(): String? =
        listOfNotNull(featureName, thoroughfare, subLocality, locality)
            .map { it.stripPlusCode() }
            .firstOrNull { it.isNotBlank() && PLUS_CODE.find(it) == null }

    private fun Address.readableLabel(): String =
        getAddressLine(0)?.stripPlusCode()
            ?: listOfNotNull(featureName, locality, adminArea)
                .joinToString(", ")
                .ifBlank { null }
            ?: String.format(Locale.US, "%.4f, %.4f", latitude, longitude)

    /**
     * Drops a leading Open Location Code ("73X6+2V3, Badshahpur Sohna Rd, …"). The framework geocoder
     * prefixes one wherever the road has no street number — which is most of India — and it is pure
     * noise to a user trying to confirm they picked the right place. Only stripped when something
     * readable is left behind.
     */
    private fun String.stripPlusCode(): String {
        val stripped = PLUS_CODE.replace(this, "").trim().trimStart(',').trim()
        return stripped.ifBlank { this }
    }

    // ---- Photon (keyless OpenStreetMap search) ----

    /**
     * [restrictToLocal] turns the soft bias into a hard bbox around it - nothing outside the box can
     * come back. Used for the local pass; see the note in [search].
     */
    private suspend fun photonSearch(
        query: String,
        limit: Int,
        bias: Pair<Double, Double>?,
        restrictToLocal: Boolean
    ): List<GeoResolver.Place> = withContext(Dispatchers.IO) {
        val url = StringBuilder(PHOTON_SEARCH)
            .append("?q=").append(query.urlEncoded())
            .append("&limit=").append(limit)
            .append("&lang=").append(photonLang())
        if (bias != null) {
            url.append(String.format(Locale.US, "&lat=%.6f&lon=%.6f", bias.first, bias.second))
                .append("&zoom=").append(PHOTON_BIAS_ZOOM)
                .append("&location_bias_scale=").append(PHOTON_BIAS_SCALE)
            if (restrictToLocal) {
                val dLat = LOCAL_RADIUS_M / METRES_PER_DEGREE
                val dLng = dLat / cos(Math.toRadians(bias.first)).coerceAtLeast(0.05)
                url.append(
                    String.format(
                        Locale.US,
                        "&bbox=%.6f,%.6f,%.6f,%.6f",
                        (bias.second - dLng).coerceAtLeast(-179.9),
                        (bias.first - dLat).coerceAtLeast(-89.9),
                        (bias.second + dLng).coerceAtMost(179.9),
                        (bias.first + dLat).coerceAtMost(89.9)
                    )
                )
            }
        }
        val body = Http.get(url.toString()) ?: return@withContext emptyList()
        parseFeatures(body)
    }

    private suspend fun photonReverse(lat: Double, lng: Double): String? =
        withContext(Dispatchers.IO) {
            val url = String.format(
                Locale.US,
                "%s?lat=%.6f&lon=%.6f&lang=%s",
                PHOTON_REVERSE, lat, lng, photonLang()
            )
            val body = Http.get(url) ?: return@withContext null
            parseFeatures(body).firstOrNull()?.label
        }

    /** Photon only serves a few UI languages; anything else falls back to English names. */
    private fun photonLang(): String =
        when (Locale.getDefault().language) {
            "de" -> "de"
            "fr" -> "fr"
            "it" -> "it"
            else -> "en"
        }

    private fun parseFeatures(body: String): List<GeoResolver.Place> = try {
        val features = JSONObject(body).optJSONArray("features") ?: return emptyList()
        (0 until features.length()).mapNotNull { i ->
            val feature = features.optJSONObject(i) ?: return@mapNotNull null
            val coords = feature.optJSONObject("geometry")?.optJSONArray("coordinates")
                ?: return@mapNotNull null
            // GeoJSON order is [longitude, latitude].
            val lng = coords.optDouble(0, Double.NaN)
            val lat = coords.optDouble(1, Double.NaN)
            if (lat.isNaN() || lng.isNaN() || abs(lat) > 90 || abs(lng) > 180) return@mapNotNull null
            val properties = feature.optJSONObject("properties")
            GeoResolver.Place(
                lat,
                lng,
                properties.photonLabel(lat, lng),
                properties?.optString("name")?.takeIf { it.isNotBlank() }
                    ?: properties?.optString("street")?.takeIf { it.isNotBlank() }
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    /** Builds "Name, street housenumber, city, country" from whichever Photon fields are present. */
    private fun JSONObject?.photonLabel(lat: Double, lng: Double): String {
        if (this == null) return String.format(Locale.US, "%.4f, %.4f", lat, lng)
        fun field(key: String): String? = optString(key).takeIf { it.isNotBlank() }
        val street = listOfNotNull(field("street"), field("housenumber")).joinToString(" ")
            .takeIf { it.isNotBlank() }
        val parts = listOfNotNull(
            field("name"),
            street,
            field("district"),
            field("city") ?: field("county"),
            field("state"),
            field("country")
        ).distinct()
        return parts.joinToString(", ").ifBlank {
            String.format(Locale.US, "%.4f, %.4f", lat, lng)
        }
    }

    private fun String.urlEncoded(): String = URLEncoder.encode(this, "UTF-8")

    /** A token hit deep in the address is worth much less than one in the name. */
    private const val LABEL_MATCH_WEIGHT = 0.55

    /** A near-miss token still counts, just below an exact one. */
    private const val FUZZY_TOKEN_SCORE = 0.85

    /** Radius of the hard-restricted local pass - city scale, where alarm destinations live. */
    private const val LOCAL_RADIUS_M = 60_000.0
    private const val METRES_PER_DEGREE = 111_320.0
}
