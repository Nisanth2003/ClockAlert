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
     * Every vendor "auto-start / background start / protected apps" screen we know of, as candidate
     * intents. Order within a vendor is most-specific-first.
     *
     * This exists so the app is not limited to the phones its author happened to own. Which screen a
     * device has is discovered by ASKING THE DEVICE ([firstResolvable]) rather than by matching a brand
     * name: a rebadged ROM, a sub-brand, a regional variant or a manufacturer nobody has heard of still
     * gets sent to the right place if it ships any of these, and nothing is offered that would throw
     * ActivityNotFoundException when tapped. A button that does nothing is worse than no button.
     */
    private val AUTOSTART_CANDIDATES: List<Pair<String, String>> = listOf(
        // Xiaomi / Redmi / POCO — MIUI, HyperOS
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        // Huawei / Honor — EMUI, MagicOS
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
        // Oppo / realme / OnePlus (post-merge) — ColorOS
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
        "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        // vivo / iQOO — Funtouch, OriginOS
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
        // OnePlus — OxygenOS
        "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
        // Samsung — no auto-start screen; its device care battery page is the equivalent
        "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
        "com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.BatteryActivity",
        // Asus — ZenUI
        "com.asus.mobilemanager" to "com.asus.mobilemanager.autostart.AutoStartActivity",
        "com.asus.mobilemanager" to "com.asus.mobilemanager.MainActivity",
        // Transsion — Tecno, Infinix, itel
        "com.transsion.phonemanager" to "com.itel.autobootmanager.activity.AutoBootMgrActivity",
        // Meizu — Flyme
        "com.meizu.safe" to "com.meizu.safe.permission.SmartBGActivity",
        // LeEco / Letv
        "com.letv.android.letvsafe" to "com.letv.android.letvsafe.AutobootManageActivity",
        // ZTE / nubia
        "com.zte.heartyservice" to "com.zte.heartyservice.autorun.AppAutoRunManager",
        // HMD / Nokia and several others ship Evenwell power saving
        "com.evenwell.powersaving.g3" to "com.evenwell.powersaving.g3.exception.PowerSaverExceptionActivity"
    )

    /** Vendor screens exposed as an ACTION rather than a component. */
    private fun actionCandidates(context: Context): List<Intent> = listOf(
        // MIUI/HyperOS permission editor: holds "Display pop-up windows while running in the background"
        // and "Show on lock screen" — the two toggles that actually let a ring screen appear. Verified on
        // a Redmi device, and it is more useful than the autostart list, so it is tried first.
        Intent("miui.intent.action.APP_PERM_EDITOR")
            .putExtra("extra_pkgname", context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        Intent("com.meizu.safe.security.SHOW_APPSEC")
            .putExtra("packageName", context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )

    /** The first of [intents] this device can actually open, or null if none of them resolve. */
    private fun firstResolvable(context: Context, intents: List<Intent>): Intent? {
        val pm = context.packageManager
        return intents.firstOrNull { pm.queryIntentActivities(it, 0).isNotEmpty() }
    }

    /**
     * The vendor background/auto-start screen this device actually has, discovered by probing — brand
     * name not consulted. Null on a phone with no such screen (a Pixel, an AOSP build), which is exactly
     * the phone that needs no extra step.
     */
    fun autostartIntent(context: Context): Intent? = firstResolvable(
        context,
        actionCandidates(context) + AUTOSTART_CANDIDATES.map { (pkg, cls) -> component(pkg, cls) }
    )

    /**
     * True when this device has a vendor screen that can silently stop alarms — i.e. when asking the user
     * to go and allow auto-start is a real instruction rather than a wild goose chase.
     *
     * Onboarding uses this to decide whether to demand the acknowledgement at all, so a phone without
     * such a screen doesn't make people tick a box about a setting that does not exist on it.
     */
    fun hasVendorRestrictions(context: Context): Boolean = autostartIntent(context) != null

    /**
     * Auto-start / background guidance for this device.
     *
     * The brand only picks the WORDING (it's worth naming the exact toggles when we know them); whether
     * there is anywhere to send the user, and where, is answered by [autostartIntent]. Any device with a
     * vendor restriction screen gets guidance even if its manufacturer isn't named here — the old code
     * returned null for anything unlisted, which meant an unknown phone silently got no help at all.
     */
    fun oemGuidance(context: Context): Oem? {
        val manufacturer = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
        val probed = autostartIntent(context)
        fun oem(nameRes: Int, bodyRes: Int) = Oem(nameRes, bodyRes, probed ?: appDetailsIntent(context))
        return when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") ||
                manufacturer.contains("poco") -> oem(R.string.oem_xiaomi_name, R.string.oem_xiaomi_body)

            manufacturer.contains("huawei") || manufacturer.contains("honor") ->
                oem(R.string.oem_huawei_name, R.string.oem_huawei_body)

            manufacturer.contains("oppo") || manufacturer.contains("realme") ->
                oem(R.string.oem_oppo_name, R.string.oem_oppo_body)

            manufacturer.contains("vivo") || manufacturer.contains("iqoo") ->
                oem(R.string.oem_vivo_name, R.string.oem_vivo_body)

            manufacturer.contains("oneplus") -> oem(R.string.oem_oneplus_name, R.string.oem_oneplus_body)

            manufacturer.contains("samsung") -> Oem(
                R.string.oem_samsung_name,
                R.string.oem_samsung_body,
                // Samsung has no auto-start screen; its battery restrictions are the equivalent.
                probed ?: Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            )

            // Named because they are known-aggressive even where the probe finds nothing.
            manufacturer.contains("tecno") || manufacturer.contains("infinix") ||
                manufacturer.contains("itel") || manufacturer.contains("transsion") ||
                manufacturer.contains("asus") || manufacturer.contains("meizu") ||
                manufacturer.contains("lenovo") || manufacturer.contains("zte") ||
                manufacturer.contains("nubia") || manufacturer.contains("letv") ||
                manufacturer.contains("leeco") || manufacturer.contains("blackview") ||
                manufacturer.contains("umidigi") || manufacturer.contains("doogee") ||
                manufacturer.contains("cubot") || manufacturer.contains("tcl") ||
                manufacturer.contains("alcatel") || manufacturer.contains("wiko") ||
                manufacturer.contains("micromax") || manufacturer.contains("lava") ||
                manufacturer.contains("gionee") || manufacturer.contains("coolpad") ->
                oem(R.string.oem_generic_name, R.string.oem_generic_body)

            // Unknown manufacturer: trust the probe. If the device ships a background-restriction
            // screen it gets generic guidance; if it doesn't, it needs no extra step and we stay quiet.
            probed != null -> oem(R.string.oem_generic_name, R.string.oem_generic_body)

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
