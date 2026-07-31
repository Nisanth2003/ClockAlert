package com.example.alarmtracker.notif

import com.example.alarmtracker.R
import com.example.alarmtracker.data.NotificationMatchRule

/**
 * Turns a currently-showing notification (or a well-known app) into a sensible default
 * [NotificationMatchRule], so discovery is one-tap: "just track what's there", no manual setup.
 *
 * The heuristics are intentionally simple and local — no network, no accounts:
 *  - Maps-style navigation  -> parse the ETA + fire on arrival (etaThreshold + parseEta).
 *  - Food / delivery / rideshare -> fire on an arrival keyword ("delivered", "driver is here",
 *                              "at your door"), matched by known package OR by delivery-ish text so
 *                              region-specific apps still work.
 *  - Claude / assistants    -> fire when the ongoing "working" notification is removed (= finished),
 *                              with done-keywords as a safety net.
 *  - any ongoing task/download/timer -> fire when it's removed (finished).
 *  - anything else          -> fire on a "done/complete/finished" keyword.
 */
object TrackablePresets {

    // Well-known packages we can give an extra-friendly preset for.
    const val PKG_CLAUDE = "com.anthropic.claude"

    private val MAPS_PACKAGES = setOf(
        "com.google.android.apps.maps",
        "com.waze",
        "com.google.android.apps.mapslite"
    )

    /** Food delivery, courier/parcel, and rideshare apps — "your order arrived" / "driver is here". */
    private val DELIVERY_PACKAGES = setOf(
        // Food delivery
        "com.dd.doordash", "com.ubercab.eats", "com.grubhub.android", "com.instacart.client",
        "com.postmates.android", "com.application.zomato", "in.swiggy.android",
        "com.deliveroo.orderapp", "com.justeat.app.uk", "com.global.foodpanda.android",
        "com.talabat", "com.zomato.restaurant",
        // Rideshare
        "com.ubercab", "com.lyft.android", "com.olacabs.customer", "com.careem.acma",
        // Parcel / courier
        "com.amazon.mShop.android.shopping", "in.amazon.mShop.android.shopping",
        "com.flipkart.android", "com.ups.mobile.android", "com.fedex.ites.cmpc.android",
        "com.tnt.mobileapp", "de.dhl.paket",
        // India quick-commerce / e-commerce couriers
        "com.grofers.customerapp", "com.zeptoconsumerapp", "com.myntra.android",
        "com.meesho.supply", "com.bigbasket.mobileapp", "com.dunzo.user"
    )

    /** Transit / commute apps — "your stop is next" / "train arriving". */
    private val TRANSIT_PACKAGES = setOf(
        "com.google.android.apps.maps", "com.citymapper.app.release", "com.thetransitapp.droid",
        "com.moovit.app", "com.trainline", "com.ndtv.transit", "com.google.android.apps.mapslite",
        "de.hafas.android.db", "au.gov.nsw.opal", "com.mxdata.tfl"
    )

    /** Arrival cues for transit — fire as you approach/reach your stop, not at departure. */
    private val TRANSIT_ARRIVAL_KEYWORDS = listOf(
        "arriving", "arrives", "approaching", "your stop", "next stop", "get off",
        "alight", "reaching", "final stop", "destination", "train arriving", "now arriving"
    )

    private val DONE_KEYWORDS = listOf("done", "complete", "completed", "finished", "ready", "success")

    private val ARRIVED_KEYWORDS = listOf("arrived", "you have arrived", "arriving")

    /** Arrival cues for food/delivery/rideshare — deliberately post-arrival, NOT "out for delivery". */
    private val DELIVERY_ARRIVAL_KEYWORDS = listOf(
        "arrived", "has arrived", "delivered", "has been delivered", "is here",
        "driver is here", "driver has arrived", "at your door", "at the door",
        "your order is here", "handed to you", "left at your", "waiting outside", "reached you"
    )

    /** Text seen while a delivery is still in progress — used only to classify the app as delivery. */
    private val DELIVERY_TEXT_CUES = listOf(
        "delivery", "delivered", "your order", "driver", "courier", "rider",
        "out for delivery", "on the way", "arriving", "tracking your", "your trip"
    )

    /** Suggests a rule for a live notification, using its package + whether it is ongoing. */
    fun forActive(active: AlarmNotificationListener.ActiveNotif): NotificationMatchRule = when {
        MAPS_PACKAGES.contains(active.packageName) -> mapsRule(active.appLabel)
        isDelivery(active) -> deliveryRule(active.packageName, active.appLabel)
        active.packageName == PKG_CLAUDE -> claudeRule(active.appLabel)
        active.isOngoing -> ongoingRule(active.packageName, active.appLabel)
        else -> keywordRule(active.packageName, active.appLabel)
    }

    /** The one-line suggestion shown on a discovery row. Kept here so classification lives in one place. */
    fun suggestionRes(active: AlarmNotificationListener.ActiveNotif): Int = when {
        MAPS_PACKAGES.contains(active.packageName) -> R.string.notif_suggest_arrival
        isDelivery(active) -> R.string.notif_suggest_delivery
        else -> R.string.notif_suggest_done
    }

    /** True when the notification is from a known delivery/rideshare app, or clearly looks like one. */
    private fun isDelivery(active: AlarmNotificationListener.ActiveNotif): Boolean {
        if (DELIVERY_PACKAGES.contains(active.packageName)) return true
        val text = (active.appLabel + " " + active.snippet).lowercase()
        return DELIVERY_TEXT_CUES.any { text.contains(it) }
    }

    /** The "Alarm when Claude finishes" preset — works whether or not a Claude notification is live. */
    fun claudeRule(label: String = "Claude") = NotificationMatchRule(
        packages = listOf(PKG_CLAUDE),
        condition = NotificationMatchRule.CONDITION_REMOVED,
        keywords = DONE_KEYWORDS,
        parseEta = false,
        label = label
    )

    /** The "Alarm when a delivery arrives" preset — watches known delivery/rideshare apps for arrival. */
    fun deliveryPresetRule(label: String = "Delivery") = NotificationMatchRule(
        packages = DELIVERY_PACKAGES.toList(),
        condition = NotificationMatchRule.CONDITION_KEYWORD,
        keywords = DELIVERY_ARRIVAL_KEYWORDS,
        parseEta = false,
        label = label
    )

    /** The "Alarm when my train/bus arrives" preset — watches transit apps for an arrival cue. */
    fun transitPresetRule(label: String = "Transit") = NotificationMatchRule(
        packages = TRANSIT_PACKAGES.toList(),
        condition = NotificationMatchRule.CONDITION_KEYWORD,
        keywords = TRANSIT_ARRIVAL_KEYWORDS,
        parseEta = false,
        label = label
    )

    private fun deliveryRule(pkg: String, label: String) = NotificationMatchRule(
        packages = listOf(pkg),
        condition = NotificationMatchRule.CONDITION_KEYWORD,
        keywords = DELIVERY_ARRIVAL_KEYWORDS,
        parseEta = false,
        label = label
    )

    private fun mapsRule(label: String) = NotificationMatchRule(
        packages = MAPS_PACKAGES.toList(),
        condition = NotificationMatchRule.CONDITION_ETA,
        keywords = ARRIVED_KEYWORDS,
        etaThresholdMinutes = 1,
        parseEta = true,
        label = label
    )

    private fun ongoingRule(pkg: String, label: String) = NotificationMatchRule(
        packages = listOf(pkg),
        condition = NotificationMatchRule.CONDITION_REMOVED,
        keywords = DONE_KEYWORDS,
        parseEta = false,
        label = label
    )

    /** Manual / fallback rule: watch a package for a set of keywords (user-entered or default done-words). */
    fun keywordRule(pkg: String, label: String, keywords: List<String> = DONE_KEYWORDS) =
        NotificationMatchRule(
            packages = listOf(pkg),
            condition = NotificationMatchRule.CONDITION_KEYWORD,
            keywords = keywords.ifEmpty { DONE_KEYWORDS },
            parseEta = false,
            label = label
        )
}
