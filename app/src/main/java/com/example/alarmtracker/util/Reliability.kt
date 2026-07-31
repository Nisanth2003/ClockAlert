package com.example.alarmtracker.util

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.example.alarmtracker.R

/**
 * Single source of truth for the app's reliability conditions — the things that
 * silently stop an alarm from ringing. Reused by the alarm-list warning banner,
 * the full health-check screen, the missed-alarm postmortem's one-tap fixes and
 * the nightly pre-flight worker so every surface agrees on status and deep-links.
 *
 * Battery optimization uses the Play-safe SETTINGS list deep-link, never the
 * REQUEST_IGNORE_BATTERY_OPTIMIZATIONS dialog.
 */
object Reliability {

    enum class Id { EXACT_ALARM, NOTIFICATIONS, FULL_SCREEN_INTENT, OVERLAY, ALARM_VOLUME, BATTERY_OPT, DND }

    data class Check(
        val id: Id,
        val titleRes: Int,
        val okSummaryRes: Int,
        val problemSummaryRes: Int,
        val actionLabelRes: Int,
        val ok: Boolean,
        /** Deep-link that lets the user fix the problem; null if none is known. */
        val intent: Intent?
    ) {
        val summaryRes: Int get() = if (ok) okSummaryRes else problemSummaryRes
    }

    /** Every applicable reliability check for this device, in priority order. */
    fun checks(context: Context): List<Check> {
        val pkg = context.packageName
        val list = mutableListOf<Check>()

        // Exact alarms (API 31+).
        val am = context.getSystemService(AlarmManager::class.java)
        val exactOk = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        list += Check(
            Id.EXACT_ALARM,
            R.string.health_exact_title,
            R.string.health_exact_ok,
            R.string.warn_exact_alarm_body,
            R.string.warn_action_allow,
            exactOk,
            if (Build.VERSION.SDK_INT >= 31) {
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$pkg"))
            } else null
        )

        // Notifications enabled.
        val notifOk = NotificationManagerCompat.from(context).areNotificationsEnabled()
        list += Check(
            Id.NOTIFICATIONS,
            R.string.health_notifications_title,
            R.string.health_notifications_ok,
            R.string.warn_notifications_body,
            R.string.warn_action_allow,
            notifOk,
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
        )

        // Full-screen intent (API 34+).
        val nm = context.getSystemService(NotificationManager::class.java)
        val fsiOk = Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()
        list += Check(
            Id.FULL_SCREEN_INTENT,
            R.string.health_fsi_title,
            R.string.health_fsi_ok,
            R.string.warn_fsi_body,
            R.string.warn_action_allow,
            fsiOk,
            if (Build.VERSION.SDK_INT >= 34) {
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$pkg"))
            } else null
        )

        // Display over other apps — lets the ring screen show full-screen even when the phone is
        // unlocked/in use (background activity launch). Without it, an unlocked alarm is a heads-up
        // notification and only the lock screen gets the full page.
        val overlayOk = Settings.canDrawOverlays(context)
        list += Check(
            Id.OVERLAY,
            R.string.health_overlay_title,
            R.string.health_overlay_ok,
            R.string.health_overlay_problem,
            R.string.warn_action_allow,
            overlayOk,
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$pkg"))
        )

        // Alarm stream volume.
        val audio = context.getSystemService(AudioManager::class.java)
        val volumeOk = audio.getStreamVolume(AudioManager.STREAM_ALARM) > 0
        list += Check(
            Id.ALARM_VOLUME,
            R.string.health_volume_title,
            R.string.health_volume_ok,
            R.string.warn_volume_body,
            R.string.warn_action_sound_settings,
            volumeOk,
            Intent(Settings.ACTION_SOUND_SETTINGS)
        )

        // Battery-optimization exemption (Play-safe SETTINGS list deep-link).
        val power = context.getSystemService(PowerManager::class.java)
        val batteryOk = power.isIgnoringBatteryOptimizations(pkg)
        list += Check(
            Id.BATTERY_OPT,
            R.string.health_battery_title,
            R.string.health_battery_ok,
            R.string.health_battery_problem,
            R.string.health_action_battery,
            batteryOk,
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        )

        // Do Not Disturb total silence (alarm channel bypasses everything except this).
        val dndOk = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_NONE
        list += Check(
            Id.DND,
            R.string.health_dnd_title,
            R.string.health_dnd_ok,
            R.string.health_dnd_problem,
            R.string.health_action_dnd,
            dndOk,
            Intent(ACTION_ZEN_MODE_SETTINGS)
        )

        return list
    }

    fun problems(context: Context): List<Check> = checks(context).filter { !it.ok }

    /** Highest-priority unmet condition, or null when everything is armed. */
    fun firstProblem(context: Context): Check? = checks(context).firstOrNull { !it.ok }

    fun allClear(context: Context): Boolean = problems(context).isEmpty()

    /**
     * True if full-screen intents can actually launch the ring activity. When false (Android 14+
     * with the access not granted), the OS silently demotes the alarm to a heads-up notification —
     * it still sounds, but no lock-screen dismiss screen appears.
     */
    fun fullScreenIntentOk(context: Context): Boolean =
        Build.VERSION.SDK_INT < 34 ||
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    /** Settings deep-link to grant full-screen-intent access (API 34+), or null below it. */
    fun fullScreenIntentSettings(context: Context): Intent? =
        if (Build.VERSION.SDK_INT >= 34) {
            Intent(
                Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                Uri.parse("package:${context.packageName}")
            )
        } else null

    /** True when the OS is still battery-optimizing us (may delay/kill alarms). */
    fun isBatteryOptimized(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java)
        return !power.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Direct "let this app ignore battery optimizations" system dialog. Allowed here because
     * AlarmTracker is an alarm-clock app and exposes it as a visible, user-controlled toggle.
     */
    fun directBatteryExemptionIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        )

    /** Play-safe fallback: the full battery-optimization list (used if the direct dialog is blocked). */
    fun batteryListIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    // ---- OEM battery-killer heuristics ----

    data class Oem(
        val nameRes: Int,
        val guidanceRes: Int,
        /** Best-effort deep-link to the OEM auto-start / battery screen; may fail to resolve. */
        val intent: Intent?
    )

    /**
     * Manufacturer-specific auto-start / background guidance for OEMs known to kill
     * background alarms aggressively. Returns null on stock-like devices. The intent
     * is best-effort — callers must catch ActivityNotFoundException and fall back.
     */
    fun oemGuidance(context: Context): Oem? {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") ||
                manufacturer.contains("poco") -> Oem(
                R.string.oem_xiaomi_name,
                R.string.oem_xiaomi_body,
                // MIUI/HyperOS permission editor — holds "Display pop-up windows while running in
                // the background" + "Show on lock screen", the toggles that actually let the ring
                // screen appear (verified on a Redmi device). Autostart is a separate screen, called
                // out in the guidance text.
                Intent("miui.intent.action.APP_PERM_EDITOR")
                    .putExtra("extra_pkgname", context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> Oem(
                R.string.oem_huawei_name,
                R.string.oem_huawei_body,
                component("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
            )
            manufacturer.contains("oppo") || manufacturer.contains("realme") -> Oem(
                R.string.oem_oppo_name,
                R.string.oem_oppo_body,
                component("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
            )
            manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> Oem(
                R.string.oem_vivo_name,
                R.string.oem_vivo_body,
                component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
            )
            manufacturer.contains("oneplus") -> Oem(
                R.string.oem_oneplus_name,
                R.string.oem_oneplus_body,
                component("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")
            )
            manufacturer.contains("samsung") -> Oem(
                R.string.oem_samsung_name,
                R.string.oem_samsung_body,
                // Samsung has no stable auto-start intent; route to the battery-opt list.
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            )
            // Other aggressive skins (Transsion Tecno/Infinix/itel, Asus, Meizu, Lenovo) — no
            // stable deep-link, so route to this app's details where auto-start / background /
            // lock-screen toggles live, with generic guidance.
            manufacturer.contains("tecno") || manufacturer.contains("infinix") ||
                manufacturer.contains("itel") || manufacturer.contains("transsion") ||
                manufacturer.contains("asus") || manufacturer.contains("meizu") ||
                manufacturer.contains("lenovo") -> Oem(
                R.string.oem_generic_name,
                R.string.oem_generic_body,
                appDetailsIntent(context)
            )
            else -> null
        }
    }

    /** Fallback intent for this app's system settings page. */
    fun appDetailsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )

    private fun component(pkg: String, cls: String): Intent =
        Intent().setComponent(ComponentName(pkg, cls))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Public ACTION_ZEN_MODE_SETTINGS string (the constant itself is @hide). */
    private const val ACTION_ZEN_MODE_SETTINGS = "android.settings.ZEN_MODE_SETTINGS"
}
