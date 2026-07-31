package com.example.alarmtracker.util

import android.content.Context

/**
 * Central free/Pro gating. Core alarming stays free forever; external-service connectors and
 * advanced analytics are Pro. Real billing (Google Play) isn't wired yet, so Pro is backed by a
 * local flag that a dev/testing toggle (or, later, [com.example.alarmtracker] BillingManager) sets.
 *
 * TO ACTIVATE REAL BILLING you must, in Google Play Console: create a subscription product,
 * add a base plan + offer, enable license testing, then add the Play Billing library and set
 * [setProUnlocked] from the purchase callback. Until then the dev toggle unlocks it locally.
 */
object Features {

    const val KEY_PRO_UNLOCKED = "pref_pro_unlocked"

    enum class Feature(val isPro: Boolean) {
        CORE_ALARMS(false),
        NOTIFICATION_TRIGGERS(false),
        STATS(false),
        CONNECTORS(true),          // Jira, Google Calendar, … integrations
        ADVANCED_ANALYTICS(true)
    }

    fun isProUnlocked(context: Context): Boolean =
        Prefs.get(context).getBoolean(KEY_PRO_UNLOCKED, false)

    fun setProUnlocked(context: Context, unlocked: Boolean) {
        Prefs.get(context).edit().putBoolean(KEY_PRO_UNLOCKED, unlocked).apply()
    }

    /** A free feature is always on; a Pro feature is on only while Pro is unlocked. */
    fun isEnabled(context: Context, feature: Feature): Boolean =
        !feature.isPro || isProUnlocked(context)
}
