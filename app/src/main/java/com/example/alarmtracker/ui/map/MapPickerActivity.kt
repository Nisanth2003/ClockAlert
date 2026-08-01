package com.example.alarmtracker.ui.map

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Paint
import android.location.Location
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.doOnLayout
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.example.alarmtracker.R
import com.example.alarmtracker.databinding.ActivityMapPickerBinding
import com.example.alarmtracker.scheduling.EventAlarmCoordinator
import com.example.alarmtracker.util.GeoResolver
import com.example.alarmtracker.util.LocationState
import com.example.alarmtracker.util.MapFavorite
import com.example.alarmtracker.util.MapFavorites
import com.example.alarmtracker.util.NetworkState
import com.example.alarmtracker.util.PlaceSearch
import com.example.alarmtracker.util.RouteService
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.DelayedMapListener
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import kotlin.math.abs
import kotlin.math.cos

/**
 * OpenStreetMap pin-picker (Carto tiles, no API key). The map pans under a fixed centre pin; the
 * pin's position on confirm is returned to the caller.
 *
 * It also has to answer "is this the right place, and can I actually get there in time", so it
 * carries: a place search biased to where the user is (an unbiased search happily offers a
 * same-named street on another continent), a live "you are here + facing this way" marker with its
 * accuracy circle, two-finger rotation plus a compass mode, and a blue road route from the user to
 * the pin with its real drive time.
 */
class MapPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMapPickerBinding
    private lateinit var map: MapView

    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }

    private var searchJob: Job? = null
    private var centreLabelJob: Job? = null
    private var routeJob: Job? = null
    private var locateJob: Job? = null

    /** Last point we reverse-geocoded, so small nudges don't re-query for the same street. */
    private var lastLabelledLat = Double.NaN
    private var lastLabelledLng = Double.NaN

    /**
     * Set while we put text into the search field ourselves. Without it, picking a result re-triggers
     * the type-ahead watcher, the result list pops straight back up, and the pick reads as "these
     * options can't be selected".
     */
    private var suppressSearchWatcher = false

    // ---- Where the user is, and which way they're pointing ----

    private val locationSource = PushLocationProvider()
    private lateinit var myLocationOverlay: MyLocationNewOverlay
    private lateinit var compass: CompassHeading

    private var myFix: Location? = null
    private var heading: Float? = null
    private var lastHeadingPushMs = 0L
    private var compassMode = false
    private var orientationAnimator: ValueAnimator? = null
    private var updatesRunning = false

    // ---- Route line ----

    private lateinit var routeCasing: Polyline
    private lateinit var routeLine: Polyline
    private var routeFrom: Location? = null
    private var routeTo: GeoPoint? = null

    /** Average speed of the last route, used to turn the alert ring into "N min before arrival". */
    private var routeSpeedKmh = DEFAULT_SPEED_KMH

    // ---- Alert ring ----

    private lateinit var radiusOverlay: RadiusOverlay
    private var radiusMeters = DEFAULT_RADIUS_M

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { onFix(it) }
        }
    }

    private val selectableItemBackground: Int by lazy {
        val outValue = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        outValue.resourceId
    }

    /** True when the caller pre-centred us on an already-chosen point, which we must not move off. */
    private var openedWithStartPoint = false

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startLocationUpdates()
            if (!openedWithStartPoint) goToMyLocation()
        } else {
            toast(R.string.map_location_denied)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        configureOsmdroid()

        super.onCreate(savedInstanceState)
        binding = ActivityMapPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        map = binding.map
        map.setTileSource(basemap())
        map.setMultiTouchControls(true)
        map.setUseDataConnection(true)
        // Fewer, larger tiles per screen on a dense display — less to download for the same view.
        map.setTilesScaledToDpi(true)
        // Tiles that haven't arrived yet draw as a faint grid in the basemap's own tones instead of a
        // black void, so a still-loading map reads as loading rather than broken.
        map.overlayManager.tilesOverlay.loadingBackgroundColor =
            ContextCompat.getColor(this, R.color.map_tile_loading_background)
        map.overlayManager.tilesOverlay.loadingLineColor =
            ContextCompat.getColor(this, R.color.map_tile_loading_line)

        setupOverlays()

        val hasStart = intent.hasExtra(EXTRA_LAT) && intent.hasExtra(EXTRA_LNG)
        openedWithStartPoint = hasStart
        // No start point? Open on the last place we knew the user to be. Its tiles are usually already
        // in the disk cache, so the map is useful the instant it appears instead of showing an empty
        // ocean at continent zoom while a live fix is negotiated.
        val remembered = if (hasStart) null else LocationState.lastKnown(this)
        val startLat = if (hasStart) intent.getDoubleExtra(EXTRA_LAT, DEFAULT_LAT)
        else remembered?.first ?: DEFAULT_LAT
        val startLng = if (hasStart) intent.getDoubleExtra(EXTRA_LNG, DEFAULT_LNG)
        else remembered?.second ?: DEFAULT_LNG
        map.controller.setZoom(if (hasStart || remembered != null) 16.0 else 4.5)
        map.controller.setCenter(GeoPoint(startLat, startLng))
        // Frame the alert ring once the map has a size. Without this the ring is drawn correctly but
        // can be wider than the screen — an 800 m ring at zoom 16 is a 1048 px radius on this display,
        // so its outline sits entirely off-view and the setting looks like it does nothing.
        if (hasStart || remembered != null) {
            map.doOnLayout { frameRing(startLat, startLng, animated = false) }
        }

        setupSearch()
        setupMapSettleListener()
        setupRadius()

        binding.backButton.setOnClickListener { finish() }
        binding.detailsToggle.setOnClickListener { toggleDetails() }
        binding.fabMyLocation.setOnClickListener { onMyLocationClick() }
        binding.fabCompass.setOnClickListener { onCompassClick() }
        binding.fabFavorites.setOnClickListener { showFavorites() }
        binding.saveFavorite.setOnClickListener { showSaveFavorite() }
        binding.offlineNote.setOnClickListener { NetworkState.openConnectivitySettings(this) }
        binding.useLocation.setOnClickListener { returnPickedLocation() }

        compass = CompassHeading(this) { onHeading(it) }
        binding.fabCompass.visibility = if (compass.isAvailable) View.VISIBLE else View.GONE

        if (hasStart || remembered != null) {
            refreshCentreLabel()
        } else {
            binding.centreLabel.setText(R.string.map_pick_hint)
        }
        when {
            hasLocationPermission() -> if (!hasStart) goToMyLocation()
            // Without it the map can't show where you are, which way you're facing, or the route to
            // the pin — so ask up front rather than leaving three features silently missing.
            savedInstanceState == null ->
                locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (hasLocationPermission() && !LocationState.servicesEnabled(this)) {
            binding.locationAccuracy.visibility = View.VISIBLE
            binding.locationAccuracy.setText(R.string.map_location_services_off)
        }
        // A map with no internet is the one screen where silence is unforgivable: tiles outside the
        // cache never arrive, search can't run and no route can be drawn, and the old build just sat
        // there looking broken. Say so once, and offer the switch.
        if (savedInstanceState == null && NetworkState.blocked(this)) {
            NetworkState.promptToConnect(this, R.string.net_feature_map)
        }
        updateOfflineNote()
    }

    /**
     * A quiet line under the map when there's no connection, so the missing tiles and the dead search
     * have a visible reason. Re-checked in [onResume] because the fix is usually made in the internet
     * panel and the user comes straight back.
     */
    private fun updateOfflineNote() {
        val offline = NetworkState.blocked(this)
        binding.offlineNote.visibility = if (offline) View.VISIBLE else View.GONE
    }

    /**
     * The basemap follows the app's light/dark mode, because a bright cream map inside a dark app is
     * the single most jarring thing on this screen. Both are Carto's key-free CDN serving OpenStreetMap
     * data — OSM's own tile server blocks generic app traffic ("403 - tile usage policy").
     */
    private fun basemap(): XYTileSource {
        val night = resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val style = if (night) "dark_all" else "light_all"
        return XYTileSource(
            if (night) "CartoDarkMatter" else "CartoPositron",
            0, 20, 256, ".png",
            arrayOf("a", "b", "c", "d").map { "https://$it.basemaps.cartocdn.com/$style/" }
                .toTypedArray(),
            "© OpenStreetMap contributors, © CARTO"
        )
    }

    /**
     * osmdroid tuning for perceived speed. The three that matter: a big on-disk cache so a place
     * you've seen before draws instantly, a generous in-memory tile count plus an overshoot ring so
     * panning and zooming already hold the neighbouring tiles, and an expiry override so cached
     * tiles are reused for a month instead of being re-fetched on every visit.
     */
    private fun configureOsmdroid() {
        val cfg = Configuration.getInstance()
        cfg.load(applicationContext, PreferenceManager.getDefaultSharedPreferences(applicationContext))
        cfg.userAgentValue = "AlarmTracker/1.0 (Android)"
        cfg.tileDownloadThreads = 8.toShort()
        cfg.tileFileSystemThreads = 8.toShort()
        cfg.tileDownloadMaxQueueSize = 80.toShort()
        cfg.tileFileSystemCacheMaxBytes = 300L * 1024 * 1024
        cfg.tileFileSystemCacheTrimBytes = 260L * 1024 * 1024
        // Hold a screen's worth of tiles plus a ring around it in RAM (default is a stingy 9).
        cfg.cacheMapTileCount = 64.toShort()
        cfg.cacheMapTileOvershoot = 2.toShort()
        cfg.expirationExtendedDuration = TILE_KEEP_MS
    }

    // ---- Overlays: route line, my-location marker, rotation gesture ----

    private fun setupOverlays() {
        // A white casing under the blue line keeps it readable over dark map features.
        routeCasing = Polyline(map).apply {
            outlinePaint.color = ContextCompat.getColor(this@MapPickerActivity, R.color.map_route_casing)
            outlinePaint.strokeWidth = dp(ROUTE_WIDTH_DP + 4)
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
            outlinePaint.isAntiAlias = true
            isEnabled = false
        }
        routeLine = Polyline(map).apply {
            outlinePaint.color = ContextCompat.getColor(this@MapPickerActivity, R.color.map_route)
            outlinePaint.strokeWidth = dp(ROUTE_WIDTH_DP)
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
            outlinePaint.isAntiAlias = true
            isEnabled = false
        }

        // Driven by OUR fused fixes (see PushLocationProvider) rather than its own subscription.
        myLocationOverlay = MyLocationNewOverlay(locationSource, map).apply {
            // The deprecated pair is the only public way to set the stationary dot as well as the
            // heading arrow (there is no setPersonIcon); setDirectionIcon alone would leave the
            // default osmdroid figure for fixes without a bearing.
            @Suppress("DEPRECATION")
            setDirectionArrow(bitmapOf(R.drawable.ic_map_dot), bitmapOf(R.drawable.ic_map_heading))
            isDrawAccuracyEnabled = true
            enableMyLocation()
        }

        // Two-finger twist to rotate. Doing it by hand takes the map out of compass mode.
        val rotationOverlay = object : RotationGestureOverlay(map) {
            override fun onRotate(deltaAngle: Float) {
                super.onRotate(deltaAngle)
                if (compassMode) {
                    compassMode = false
                    orientationAnimator?.cancel()
                }
                updateCompassButton()
            }
        }
        rotationOverlay.isEnabled = true

        radiusOverlay = RadiusOverlay(map)

        // Tap anywhere to put the pin there, rather than having to drag the map precisely under it.
        // A tap also gets rid of the search results and the keyboard.
        val tapToPlace = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                hideKeyboard()
                showResults(emptyList())
                p?.let { map.controller.animateTo(it) }
                return true
            }

            override fun longPressHelper(p: GeoPoint?): Boolean = false
        })

        map.overlays.add(radiusOverlay)
        map.overlays.add(routeCasing)
        map.overlays.add(routeLine)
        map.overlays.add(myLocationOverlay)
        map.overlays.add(rotationOverlay)
        map.overlays.add(tapToPlace)
    }

    // ---- Alert ring ----

    /**
     * The ring the alarm fires on. Showing it on the map is the only way the user can judge their
     * warning time: the radius is also stated in minutes, using the real average speed of the route
     * when we have one.
     */
    private fun setupRadius() {
        radiusMeters = intent.getIntExtra(EXTRA_RADIUS, DEFAULT_RADIUS_M)
            .coerceIn(MIN_RADIUS_M, MAX_RADIUS_M)
        binding.radiusSlider.valueFrom = MIN_RADIUS_M.toFloat()
        binding.radiusSlider.valueTo = MAX_RADIUS_M.toFloat()
        binding.radiusSlider.value = radiusMeters.toFloat()
        binding.radiusSlider.addOnChangeListener { _, value, _ ->
            radiusMeters = value.toInt()
            applyRadius()
        }
        // Re-frame when the drag ENDS, not on every tick — zooming under a moving thumb is horrible.
        binding.radiusSlider.addOnSliderTouchListener(
            object : com.google.android.material.slider.Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) = Unit

                override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                    frameRingAtPin()
                }
            }
        )
        // One tap for the distances people actually pick; the slider covers everything between.
        for (metres in RADIUS_PRESETS_M) {
            binding.radiusChips.addView(
                Chip(this).apply {
                    text = plainDistance(metres)
                    isCheckable = false
                    setOnClickListener {
                        binding.radiusSlider.value = metres.toFloat()
                        frameRingAtPin()
                    }
                }
            )
        }
        applyRadius()
    }

    /**
     * Zooms and centres so the whole alert ring is comfortably on screen, with room around it for
     * context. This is what makes the radius legible: the ring is drawn in real metres, so at close
     * zooms a large one is simply bigger than the viewport.
     */
    private fun frameRing(lat: Double, lng: Double, animated: Boolean) {
        val halfLat = radiusMeters * RING_FRAME_FACTOR / METRES_PER_DEGREE_LAT
        val halfLng = halfLat / cos(Math.toRadians(lat)).coerceAtLeast(0.05)
        map.zoomToBoundingBox(
            BoundingBox(
                (lat + halfLat).coerceAtMost(89.9),
                (lng + halfLng).coerceAtMost(179.9),
                (lat - halfLat).coerceAtLeast(-89.9),
                (lng - halfLng).coerceAtLeast(-179.9)
            ),
            animated,
            RING_FRAME_BORDER_PX
        )
    }

    /** Re-frames around wherever the pin is now — used after the user changes the ring size. */
    private fun frameRingAtPin() {
        val centre = map.mapCenter
        frameRing(centre.latitude, centre.longitude, animated = true)
    }

    /** Chip labels: an exact preset needs no "≈", and the approximation sign reads as noise there. */
    private fun plainDistance(metres: Int): String =
        if (metres >= 1000) {
            getString(
                R.string.map_radius_chip_km_fmt,
                java.text.DecimalFormat("0.#").format(metres / 1000.0)
            )
        } else {
            getString(R.string.map_radius_chip_m_fmt, metres)
        }

    private fun toggleDetails() {
        val expand = binding.detailsContent.visibility != View.VISIBLE
        binding.detailsContent.visibility = if (expand) View.VISIBLE else View.GONE
        binding.detailsToggle.setIconResource(
            if (expand) R.drawable.ic_expand_less else R.drawable.ic_expand_more
        )
        // The map shrinks when the panel grows, so re-frame or the ring falls off the smaller view.
        if (expand) map.doOnLayout { frameRingAtPin() }
    }

    private fun applyRadius() {
        radiusOverlay.radiusMeters = radiusMeters.toDouble()
        val pretty = EventAlarmCoordinator.formatKm(this, radiusMeters)
        // The distance is already on the toggle, so this line answers the other half of the question:
        // how much warning that distance actually buys at the speed of this trip.
        val leadMinutes = (radiusMeters / (routeSpeedKmh * 1000.0 / 3600.0) / 60.0).toInt()
        binding.radiusValue.text = if (leadMinutes >= 1) {
            getString(R.string.map_radius_lead_fmt, leadMinutes)
        } else {
            getString(R.string.map_radius_lead_none)
        }
        // The value stays readable while the controls are folded away.
        binding.detailsToggle.text = getString(R.string.map_radius_toggle_fmt, pretty)
        map.invalidate()
    }

    private fun bitmapOf(@DrawableRes res: Int): Bitmap =
        AppCompatResources.getDrawable(this, res)!!.toBitmap()

    private fun dp(value: Int): Float = value * resources.displayMetrics.density

    // ---- Search ----

    private fun setupSearch() {
        binding.clearButton.setOnClickListener {
            suppressSearchWatcher = true
            binding.searchInput.setText("")
            suppressSearchWatcher = false
            showResults(emptyList())
            binding.clearButton.visibility = View.GONE
        }
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch(binding.searchInput.text?.toString())
                true
            } else {
                false
            }
        }
        // Type-ahead: search a beat after typing stops, so results appear without a second tap.
        binding.searchInput.doAfterTextChanged { text ->
            if (suppressSearchWatcher) {
                suppressSearchWatcher = false
                return@doAfterTextChanged
            }
            val query = text?.toString().orEmpty()
            binding.clearButton.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
            searchJob?.cancel()
            if (query.trim().length < MIN_QUERY_LENGTH) {
                showResults(emptyList())
                binding.searchProgress.visibility = View.GONE
                return@doAfterTextChanged
            }
            searchJob = lifecycleScope.launch {
                delay(SEARCH_DEBOUNCE_MS)
                performSearch(query)
            }
        }
    }

    private fun runSearch(query: String?) {
        val trimmed = query?.trim().orEmpty()
        if (trimmed.isEmpty()) return
        hideKeyboard()
        searchJob?.cancel()
        searchJob = lifecycleScope.launch { performSearch(trimmed) }
    }

    /**
     * The bias point that keeps results local, in priority order:
     *  1. wherever the map has been deliberately panned to, if that is a long way from the user —
     *     someone looking at another city and typing "railway station" means THAT city's;
     *  2. the user's own position;
     *  3. the map centre, once zoomed in enough for it to mean anything (at the opening world view it
     *     does not);
     *  4. the last position ever recorded, which is all we have when Location is switched off.
     */
    private fun searchBias(): Pair<Double, Double>? {
        val centre = map.mapCenter
        val zoomedIn = map.zoomLevelDouble >= BIAS_MIN_ZOOM
        val fix = myFix
        if (fix != null) {
            val panned = zoomedIn && GeoResolver.distanceMeters(
                fix.latitude, fix.longitude, centre.latitude, centre.longitude
            ) > PANNED_AWAY_M
            return if (panned) {
                centre.latitude to centre.longitude
            } else {
                fix.latitude to fix.longitude
            }
        }
        if (zoomedIn) return centre.latitude to centre.longitude
        return LocationState.lastKnown(this)
    }

    private suspend fun performSearch(query: String) {
        // Without this, an offline search spends two timeouts and then says "No places matched that
        // search" — which is a lie about the query rather than the truth about the connection.
        if (NetworkState.blocked(this)) {
            showResults(emptyList())
            NetworkState.promptToConnect(this, R.string.net_feature_place_search)
            return
        }
        val bias = searchBias()
        binding.searchProgress.visibility = View.VISIBLE
        val results = PlaceSearch.search(this, query, MAX_RESULTS, bias?.first, bias?.second)
        binding.searchProgress.visibility = View.GONE
        if (results.isEmpty()) {
            showResults(emptyList())
            // Both providers came back empty. That means "nothing matched" only if we were actually
            // online; otherwise it means the search never left the phone.
            if (NetworkState.status(this) == NetworkState.Status.ONLINE) {
                toast(R.string.map_search_no_results)
            } else {
                Toast.makeText(
                    this,
                    NetworkState.explain(this, R.string.net_service_place_search),
                    Toast.LENGTH_LONG
                ).show()
            }
        } else {
            showResults(results, bias)
        }
    }

    private fun showResults(
        results: List<GeoResolver.Place>,
        bias: Pair<Double, Double>? = null
    ) {
        val container = binding.searchResults
        container.removeAllViews()
        binding.searchResultsCard.visibility = if (results.isEmpty()) View.GONE else View.VISIBLE
        // Results arrive nearest-first, so the first row is the closest match to the user.
        results.forEachIndexed { index, place ->
            container.addView(buildResultRow(place, bias, closest = index == 0 && bias != null))
        }
    }

    /** Name on top, how far away underneath — the distance is what exposes a wrong-country hit. */
    private fun buildResultRow(
        place: GeoResolver.Place,
        bias: Pair<Double, Double>?,
        closest: Boolean
    ): View {
        val name = TextView(this).apply {
            text = place.label
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56).toInt()
            setPadding(dp(16).toInt(), dp(8).toInt(), dp(16).toInt(), dp(8).toInt())
            isClickable = true
            isFocusable = true
            setBackgroundResource(selectableItemBackground)
            addView(name)
            setOnClickListener { onResultPicked(place) }
        }
        if (bias != null) {
            val meters = GeoResolver.distanceMeters(bias.first, bias.second, place.lat, place.lng)
            val far = meters > FAR_RESULT_M
            val distance = getString(
                if (far) R.string.map_result_distance_far_fmt else R.string.map_result_distance_fmt,
                EventAlarmCoordinator.formatKm(this, meters.toInt())
            )
            row.addView(
                TextView(this).apply {
                    text = if (closest) getString(R.string.map_result_closest_fmt, distance) else distance
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                    setTextColor(
                        MaterialColors.getColor(
                            this,
                            when {
                                far -> androidx.appcompat.R.attr.colorError
                                closest -> androidx.appcompat.R.attr.colorPrimary
                                else -> com.google.android.material.R.attr.colorOnSurfaceVariant
                            }
                        )
                    )
                }
            )
        }
        return row
    }

    private fun onResultPicked(place: GeoResolver.Place) {
        hideKeyboard()
        showResults(emptyList())
        // Fill the field without waking the type-ahead watcher, or the list reappears immediately.
        suppressSearchWatcher = true
        binding.searchInput.setText(place.label)
        suppressSearchWatcher = false
        binding.centreLabel.text = place.label
        centreOn(place.lat, place.lng, reverseGeocode = false)
        confirmPick(place.label)
    }

    /**
     * Makes a pick impossible to miss. Tapping a result used to do nothing visible beyond the map
     * moving, so users could not tell they had selected anything: now the pin drops in with a sonar
     * pulse, the name is spelled out, and the button they need next draws attention to itself.
     */
    private fun confirmPick(label: String) {
        Toast.makeText(this, getString(R.string.map_pick_selected_fmt, label), Toast.LENGTH_LONG)
            .show()
        if (animationsDisabled()) return
        animatePinDrop()
        pulsePin()
        pulseUseButton()
    }

    private fun animatePinDrop() {
        val pin = binding.pin
        pin.animate().cancel()
        pin.translationY = -dp(56)
        pin.alpha = 0.4f
        pin.scaleX = 0.7f
        pin.scaleY = 0.7f
        pin.animate()
            .translationY(0f)
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setInterpolator(OvershootInterpolator(2.2f))
            .setDuration(PIN_DROP_MS)
            .start()
    }

    private fun pulsePin() {
        val pulse = binding.pinPulse
        pulse.animate().cancel()
        pulse.scaleX = 0.4f
        pulse.scaleY = 0.4f
        pulse.alpha = 0.9f
        pulse.animate()
            .scaleX(3.2f)
            .scaleY(3.2f)
            .alpha(0f)
            .setStartDelay(PIN_DROP_MS / 2)
            .setInterpolator(DecelerateInterpolator())
            .setDuration(PULSE_MS)
            .start()
    }

    private fun pulseUseButton() {
        val button = binding.useLocation
        button.animate().cancel()
        button.scaleX = 1f
        button.scaleY = 1f
        button.animate()
            .scaleX(1.08f)
            .scaleY(1.08f)
            .setStartDelay(PIN_DROP_MS)
            .setDuration(180)
            .withEndAction {
                button.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
            }
            .start()
    }

    private fun animationsDisabled(): Boolean =
        Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    private fun hideKeyboard() {
        binding.searchInput.clearFocus()
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
    }

    // ---- Centre label + route, refreshed when the map settles ----

    private fun setupMapSettleListener() {
        // DelayedMapListener coalesces the flood of scroll events into one call when the map settles.
        map.addMapListener(
            DelayedMapListener(
                object : MapListener {
                    override fun onScroll(event: ScrollEvent?): Boolean {
                        onMapSettled()
                        return false
                    }

                    override fun onZoom(event: ZoomEvent?): Boolean {
                        onMapSettled()
                        return false
                    }
                },
                CENTRE_LABEL_DEBOUNCE_MS
            )
        )
    }

    private fun onMapSettled() {
        refreshCentreLabel()
        maybeRefreshRoute()
    }

    private fun refreshCentreLabel() {
        val center = map.mapCenter
        val lat = center.latitude
        val lng = center.longitude
        if (!lastLabelledLat.isNaN() &&
            GeoResolver.distanceMeters(lastLabelledLat, lastLabelledLng, lat, lng) < LABEL_MIN_MOVE_M
        ) return
        lastLabelledLat = lat
        lastLabelledLng = lng
        centreLabelJob?.cancel()
        centreLabelJob = lifecycleScope.launch {
            val name = PlaceSearch.reverse(this@MapPickerActivity, lat, lng)
            binding.centreLabel.text = name ?: getString(R.string.map_pinned_fmt, lat, lng)
        }
    }

    // ---- Route line ----

    /**
     * Draws the road route from the user to the pin. Re-run when the pin settles somewhere new or the
     * user has genuinely moved — not on every fix, since each refresh is a network request.
     */
    private fun maybeRefreshRoute() {
        val me = myFix ?: return
        val centre = map.mapCenter
        val to = GeoPoint(centre.latitude, centre.longitude)
        val meMoved = routeFrom?.let {
            GeoResolver.distanceMeters(it.latitude, it.longitude, me.latitude, me.longitude)
        } ?: Double.MAX_VALUE
        val pinMoved = routeTo?.let {
            GeoResolver.distanceMeters(it.latitude, it.longitude, to.latitude, to.longitude)
        } ?: Double.MAX_VALUE
        if (meMoved < ROUTE_ME_MOVE_M && pinMoved < LABEL_MIN_MOVE_M) return

        val straight = GeoResolver.distanceMeters(me.latitude, me.longitude, to.latitude, to.longitude)
        if (straight < ROUTE_MIN_DISTANCE_M) {
            // The pin is essentially on top of the user — a route would be noise.
            clearRoute()
            return
        }
        routeFrom = me
        routeTo = to
        routeJob?.cancel()
        routeJob = lifecycleScope.launch {
            binding.routeInfo.visibility = View.VISIBLE
            binding.routeInfo.setText(R.string.map_route_loading)
            val route = if (straight <= RouteService.MAX_ROUTE_DISTANCE_M) {
                RouteService.driving(me.latitude, me.longitude, to.latitude, to.longitude)
            } else {
                null
            }
            if (route == null) {
                // No road route (offline, both hosts down, or another continent) — be honest about
                // what the number then means instead of silently showing nothing.
                routeCasing.isEnabled = false
                routeLine.isEnabled = false
                val distance = getString(
                    R.string.map_route_straight_fmt,
                    EventAlarmCoordinator.formatKm(this@MapPickerActivity, straight.toInt())
                )
                // "Straight line" on its own reads as an app limitation; with no connection it is
                // simply the best that can be known, and saying so is the difference.
                binding.routeInfo.text = if (NetworkState.blocked(this@MapPickerActivity)) {
                    "$distance · ${getString(R.string.net_offline_short)}"
                } else {
                    distance
                }
            } else {
                val points = route.points.map { GeoPoint(it.lat, it.lng) }
                routeCasing.setPoints(points)
                routeLine.setPoints(points)
                routeCasing.isEnabled = true
                routeLine.isEnabled = true
                // The route's own average speed makes the alert ring's lead time real rather than
                // a guess — city traffic and open highway give very different warning times.
                if (route.durationSeconds > 0) {
                    routeSpeedKmh = (route.distanceMeters / route.durationSeconds * 3.6)
                        .coerceIn(MIN_SPEED_KMH, MAX_SPEED_KMH)
                    applyRadius()
                }
                binding.routeInfo.text = getString(
                    R.string.map_route_fmt,
                    EventAlarmCoordinator.formatKm(
                        this@MapPickerActivity,
                        route.distanceMeters.toInt()
                    ),
                    route.minutes
                )
            }
            map.invalidate()
        }
    }

    private fun clearRoute() {
        routeJob?.cancel()
        routeFrom = null
        routeTo = null
        routeCasing.isEnabled = false
        routeLine.isEnabled = false
        binding.routeInfo.visibility = View.GONE
        map.invalidate()
    }

    // ---- My location ----

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun onMyLocationClick() {
        when {
            !hasLocationPermission() ->
                locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            // Granted but the phone's Location toggle is off: no app can get a fix, so say so and
            // offer the one screen that fixes it instead of spinning and failing.
            !LocationState.servicesEnabled(this) -> promptEnableLocation()
            else -> goToMyLocation()
        }
    }

    private fun promptEnableLocation() {
        binding.locationAccuracy.visibility = View.VISIBLE
        binding.locationAccuracy.setText(R.string.map_location_services_off)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.map_location_services_off_title)
            .setMessage(R.string.map_location_services_off)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.map_location_services_open) { _, _ ->
                try {
                    startActivity(LocationState.settingsIntent())
                } catch (_: Exception) {
                    toast(R.string.map_location_unavailable)
                }
            }
            .show()
    }

    /**
     * Live high-accuracy updates while this screen is in front. A map that shows where you are is
     * exactly the case foreground updates are for; everything battery-sensitive (geofences, the live
     * ETA checks) stays sparse and untouched by this.
     */
    @SuppressLint("MissingPermission") // guarded by hasLocationPermission()
    private fun startLocationUpdates() {
        if (updatesRunning || !hasLocationPermission()) return
        updatesRunning = true
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
            .setMinUpdateDistanceMeters(2f)
            .setWaitForAccurateLocation(true)
            .build()
        try {
            fused.requestLocationUpdates(request, locationCallback, mainLooper)
            // A cached fix draws the dot instantly; whether it's good enough to CENTRE on is a
            // separate question, decided by usableForCentring().
            fused.lastLocation.addOnSuccessListener { it?.let(::onFix) }
        } catch (_: SecurityException) {
            updatesRunning = false
        }
    }

    private fun stopLocationUpdates() {
        if (!updatesRunning) return
        updatesRunning = false
        fused.removeLocationUpdates(locationCallback)
    }

    private fun onFix(location: Location) {
        val previous = myFix
        // Continuous updates are always fresher than what they replace; a one-shot/cached fix must
        // not overwrite a better live one.
        if (previous != null && !isFresh(location) && isFresh(previous)) return
        myFix = location
        LocationState.remember(this, location)
        compass.onLocation(location)
        pushToOverlay(location)
        updateAccuracyText(location)
        maybeRefreshRoute()
    }

    /** Hands the fix to the map marker, stamped with the compass heading so it draws the arrow. */
    private fun pushToOverlay(location: Location) {
        val stamped = Location(location)
        heading?.let { stamped.bearing = it }
        locationSource.push(stamped)
    }

    private fun updateAccuracyText(location: Location) {
        // A faked fix outranks everything else this line could say: the pin, the route and any alarm
        // built on it are all wrong by however far the fake is from reality, and no amount of accuracy
        // in metres means anything. Warned once per screen, loudly, because nothing else can detect it.
        if (LocationState.isMock(location)) {
            binding.locationAccuracy.visibility = View.VISIBLE
            binding.locationAccuracy.setText(R.string.loc_mock_short)
            if (!mockWarned) {
                mockWarned = true
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.loc_mock_title)
                    .setMessage(R.string.loc_mock_body)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            return
        }
        if (!location.hasAccuracy()) {
            binding.locationAccuracy.visibility = View.GONE
            return
        }
        val metres = location.accuracy.toInt()
        val pretty = EventAlarmCoordinator.formatKm(this, metres)
        binding.locationAccuracy.visibility = View.VISIBLE
        val accuracy = if (location.accuracy > WEAK_ACCURACY_M) {
            getString(R.string.map_accuracy_weak_fmt, pretty)
        } else {
            getString(R.string.map_accuracy_fmt, pretty)
        }
        // A VPN does not move this fix, so it is a footnote here rather than a warning — it only explains
        // search results that look like they came from another country.
        binding.locationAccuracy.text = if (NetworkState.vpnActive(this)) {
            "$accuracy · ${getString(R.string.loc_vpn_short)}"
        } else {
            accuracy
        }
    }

    /** One mock-location warning per visit; repeating it on every fix would be unusable. */
    private var mockWarned = false

    /**
     * Recentres on the user. The old version centred on `lastLocation`, which is whatever fix any app
     * on the phone happened to take last — often minutes old and network-derived, which is what made
     * "my location" land in the wrong place. Now a cached fix is only used when it is both recent and
     * tight; otherwise we wait for a real GPS fix to converge.
     */
    private fun goToMyLocation() {
        if (!hasLocationPermission()) return
        startLocationUpdates()
        myFix?.takeIf { usableForCentring(it) }?.let {
            centreOn(it.latitude, it.longitude)
            return
        }
        locateJob?.cancel()
        locateJob = lifecycleScope.launch {
            toast(R.string.map_locating_precise)
            val fix = awaitAccurateFix()
            if (fix == null) {
                toast(R.string.map_location_unavailable)
            } else {
                centreOn(fix.latitude, fix.longitude)
                if (!usableForCentring(fix)) toast(R.string.map_located_rough)
            }
        }
    }

    /**
     * Waits for the live updates to produce a fix good enough to trust, returning the best one seen
     * if they never get there. Also kicks a one-shot high-accuracy request, which is what wakes GPS
     * on a phone that has only been using cell/wi-fi positioning.
     */
    @SuppressLint("MissingPermission") // caller checked hasLocationPermission()
    private suspend fun awaitAccurateFix(): Location? {
        try {
            fused.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                CancellationTokenSource().token
            ).addOnSuccessListener { it?.let(::onFix) }
        } catch (_: SecurityException) {
            return null
        }
        val deadline = SystemClock.elapsedRealtime() + LOCATE_TIMEOUT_MS
        var best: Location? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            val fix = myFix?.takeIf { isFresh(it) }
            if (fix != null) {
                if (best == null || fix.accuracy < best.accuracy) best = fix
                if (usableForCentring(fix)) return fix
            }
            delay(LOCATE_POLL_MS)
        }
        return best ?: myFix
    }

    private fun isFresh(location: Location): Boolean =
        SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos < FIX_MAX_AGE_NANOS

    private fun usableForCentring(location: Location): Boolean =
        isFresh(location) && location.hasAccuracy() && location.accuracy <= GOOD_ACCURACY_M

    private fun centreOn(lat: Double, lng: Double, reverseGeocode: Boolean = true) {
        frameRing(lat, lng, animated = true)
        if (reverseGeocode) {
            refreshCentreLabel()
        } else {
            // A searched/favourite place already has a better name than reverse geocoding gives —
            // claim this point as labelled so the settle listener doesn't overwrite it.
            centreLabelJob?.cancel()
            lastLabelledLat = lat
            lastLabelledLng = lng
        }
        maybeRefreshRoute()
    }

    // ---- Compass / rotation ----

    private fun onHeading(degrees: Float) {
        heading = degrees
        val now = SystemClock.uptimeMillis()
        if (now - lastHeadingPushMs >= HEADING_PUSH_INTERVAL_MS) {
            lastHeadingPushMs = now
            myFix?.let { pushToOverlay(it) }
        }
        if (compassMode) {
            // Turn the map so the way the user is facing points up the screen.
            val target = CompassHeading.normalize(-degrees)
            if (abs(CompassHeading.delta(map.mapOrientation, target)) >= ORIENTATION_STEP_DEG) {
                map.mapOrientation = target
                updateCompassButton()
            }
        }
    }

    /**
     * One button, three states: in compass mode a tap goes back to north-up; rotated by hand a tap
     * straightens the map; already north-up a tap turns compass mode on. The icon is a needle rotated
     * by the map's orientation, so it always shows where north is.
     */
    private fun onCompassClick() {
        when {
            compassMode -> {
                compassMode = false
                animateToNorth()
                toast(R.string.map_compass_off)
            }
            abs(CompassHeading.delta(map.mapOrientation, 0f)) > 0.5f -> animateToNorth()
            !compass.isAvailable -> toast(R.string.map_compass_unavailable)
            else -> {
                compassMode = true
                toast(R.string.map_compass_on)
                heading?.let { onHeading(it) }
            }
        }
        updateCompassButton()
    }

    private fun animateToNorth() {
        orientationAnimator?.cancel()
        val from = map.mapOrientation
        val delta = CompassHeading.delta(from, 0f)
        if (abs(delta) < 0.5f) {
            map.mapOrientation = 0f
            updateCompassButton()
            return
        }
        orientationAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ORIENTATION_ANIM_MS
            addUpdateListener {
                map.mapOrientation = CompassHeading.normalize(from + delta * it.animatedFraction)
                updateCompassButton()
            }
            start()
        }
    }

    private fun updateCompassButton() {
        // Rotating the needle by the map's orientation keeps it pointing at real north on screen.
        binding.fabCompass.rotation = map.mapOrientation
    }

    // ---- Favourites ----

    private fun showSaveFavorite() {
        val inputLayout = TextInputLayout(this).apply {
            setPadding(dp(24).toInt(), dp(8).toInt(), dp(24).toInt(), 0)
            hint = getString(R.string.map_favorite_name_hint)
        }
        val input = TextInputEditText(inputLayout.context)
        // Pre-fill with the pin's resolved name so saving is one tap.
        input.setText(binding.centreLabel.text?.toString().orEmpty())
        inputLayout.addView(input)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.map_save_favorite)
            .setView(inputLayout)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val center = map.mapCenter
                val name = input.text?.toString()?.trim().orEmpty()
                    .ifBlank { getString(R.string.map_favorites) }
                MapFavorites.add(this, MapFavorite(name, center.latitude, center.longitude))
                toast(R.string.map_favorite_saved)
            }
            .show()
    }

    private fun showFavorites() {
        val favorites = MapFavorites.all(this)
        if (favorites.isEmpty()) {
            toast(R.string.map_no_favorites)
            return
        }
        val names = favorites.map { it.name }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.map_favorites)
            .setItems(names) { _, which -> onFavoritePicked(favorites[which], which) }
            .show()
    }

    private fun onFavoritePicked(favorite: MapFavorite, index: Int) {
        MaterialAlertDialogBuilder(this)
            .setTitle(favorite.name)
            .setNeutralButton(R.string.map_favorite_delete) { _, _ ->
                MapFavorites.removeAt(this, index)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.map_favorite_go) { _, _ ->
                binding.centreLabel.text = favorite.name
                centreOn(favorite.lat, favorite.lng, reverseGeocode = false)
                confirmPick(favorite.name)
            }
            .show()
    }

    private fun returnPickedLocation() {
        val center = map.mapCenter
        setResult(
            Activity.RESULT_OK,
            Intent()
                .putExtra(EXTRA_LAT, center.latitude)
                .putExtra(EXTRA_LNG, center.longitude)
                .putExtra(EXTRA_RADIUS, radiusMeters)
                // Only a real resolved name — otherwise the label is still the "drag the map"
                // hint, which must never end up as the alarm's place name.
                .putExtra(
                    EXTRA_NAME,
                    if (lastLabelledLat.isNaN()) null else binding.centreLabel.text?.toString()
                )
        )
        finish()
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    override fun onResume() {
        super.onResume()
        map.onResume()
        compass.start()
        startLocationUpdates()
        // They may have just turned Wi-Fi on from our own prompt; if so the tiles will start arriving
        // and the note should go.
        updateOfflineNote()
        if (!NetworkState.blocked(this)) map.invalidate()
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
        compass.stop()
        stopLocationUpdates()
        orientationAnimator?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        locationSource.destroy()
    }

    companion object {
        const val EXTRA_LAT = "extra_lat"
        const val EXTRA_LNG = "extra_lng"

        /** Readable name of the picked point, when the pin could be reverse-geocoded. */
        const val EXTRA_NAME = "extra_name"

        /** Alert-ring radius in metres, in on the way in and back out with the user's choice. */
        const val EXTRA_RADIUS = "extra_radius"

        const val MIN_RADIUS_M = 150
        const val MAX_RADIUS_M = 5_000
        const val DEFAULT_RADIUS_M = 200

        private const val DEFAULT_LAT = 20.0
        private const val DEFAULT_LNG = 0.0
        private const val MIN_QUERY_LENGTH = 3
        private const val MAX_RESULTS = 6
        private const val SEARCH_DEBOUNCE_MS = 450L
        private const val CENTRE_LABEL_DEBOUNCE_MS = 600L
        private const val LABEL_MIN_MOVE_M = 40.0

        /** Below this zoom the map centre is a whole continent — too vague to bias a search with. */
        private const val BIAS_MIN_ZOOM = 8.0

        /** Past this from the user, the map view is clearly deliberate and becomes the search bias. */
        private const val PANNED_AWAY_M = 50_000.0

        /** A hit this far from the user is called out in the result row. */
        private const val FAR_RESULT_M = 200_000.0

        private const val ROUTE_WIDTH_DP = 6
        private const val ROUTE_MIN_DISTANCE_M = 60.0
        private const val ROUTE_ME_MOVE_M = 150.0

        private const val UPDATE_INTERVAL_MS = 2_000L
        private const val LOCATE_TIMEOUT_MS = 15_000L
        private const val LOCATE_POLL_MS = 400L
        private const val FIX_MAX_AGE_NANOS = 30_000_000_000L
        private const val GOOD_ACCURACY_M = 50f
        private const val WEAK_ACCURACY_M = 100f

        private const val HEADING_PUSH_INTERVAL_MS = 120L
        private const val ORIENTATION_STEP_DEG = 2f
        private const val ORIENTATION_ANIM_MS = 250L

        /** How much wider than the ring the framed view is, so there is context around it. */
        private const val RING_FRAME_FACTOR = 1.7
        private const val RING_FRAME_BORDER_PX = 48
        private const val METRES_PER_DEGREE_LAT = 111_320.0

        /** One-tap ring sizes, in metres. */
        private val RADIUS_PRESETS_M = intArrayOf(200, 500, 1000, 2000)

        private const val PIN_DROP_MS = 380L
        private const val PULSE_MS = 620L

        /** Fallback travel speed for the ring's lead time until a real route supplies one. */
        private const val DEFAULT_SPEED_KMH = 40.0
        private const val MIN_SPEED_KMH = 4.0
        private const val MAX_SPEED_KMH = 120.0

        /** Reuse cached tiles for a month before re-fetching them. */
        private const val TILE_KEEP_MS = 30L * 24 * 60 * 60 * 1000

        /**
         * [lat]/[lng] pre-centre the map on an already-chosen point; pass null to locate the user.
         * [radiusM] pre-sets the alert ring so the map shows the alarm's current setting.
         */
        fun intent(context: Context, lat: Double?, lng: Double?, radiusM: Int? = null): Intent =
            Intent(context, MapPickerActivity::class.java).apply {
                if (lat != null && lng != null) {
                    putExtra(EXTRA_LAT, lat)
                    putExtra(EXTRA_LNG, lng)
                }
                if (radiusM != null) putExtra(EXTRA_RADIUS, radiusM)
            }
    }
}
