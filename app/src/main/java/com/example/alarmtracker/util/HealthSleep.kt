package com.example.alarmtracker.util

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant

/**
 * Optional bridge to Health Connect — the Android standard that lets other health apps (Fitbit,
 * Samsung Health, Google Fit, watch companions, etc.) share the sleep they measure with a watch's
 * sensors. Fully guarded: every entry point checks availability first, so a device without Health
 * Connect simply falls back to the lock-status / app-activity proxies. Read-only, sleep only.
 */
object HealthSleep {

    val PERMISSIONS = setOf(HealthPermission.getReadPermission(SleepSessionRecord::class))

    /** True only when Health Connect is installed and ready on this device. */
    fun isAvailable(context: Context): Boolean =
        try {
            HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
        } catch (_: Throwable) {
            false
        }

    /** Deep-link to install / update Health Connect when it isn't available. */
    fun providerPackage(): String = "com.google.android.apps.healthdata"

    private fun client(context: Context): HealthConnectClient =
        HealthConnectClient.getOrCreate(context)

    suspend fun hasPermission(context: Context): Boolean =
        try {
            client(context).permissionController.getGrantedPermissions().containsAll(PERMISSIONS)
        } catch (_: Throwable) {
            false
        }

    /**
     * The most recent sleep session in the last ~36h as (bedtime, wake) epoch millis, or null when
     * unavailable / not permitted / no session recorded.
     */
    suspend fun lastNight(context: Context): Pair<Long, Long>? {
        if (!isAvailable(context) || !hasPermission(context)) return null
        return try {
            val end = Instant.now()
            val start = end.minus(Duration.ofHours(36))
            val response = client(context).readRecords(
                ReadRecordsRequest(
                    SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )
            )
            val session = response.records.maxByOrNull { it.endTime } ?: return null
            session.startTime.toEpochMilli() to session.endTime.toEpochMilli()
        } catch (_: Throwable) {
            null
        }
    }
}
