package com.example.alarmtracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        Alarm::class, AlarmEvent::class, SleepSignal::class, EventTrigger::class,
        Friend::class, FriendWatch::class
    ],
    version = 11,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun alarmDao(): AlarmDao
    abstract fun alarmEventDao(): AlarmEventDao
    abstract fun sleepSignalDao(): SleepSignalDao
    abstract fun eventTriggerDao(): EventTriggerDao
    abstract fun friendDao(): FriendDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /**
         * v1 -> v2: Phase A scheduling features. Additive columns only, each with a
         * default so existing rows migrate cleanly (no destructive fallback).
         *  - shift-pattern alarms: shiftWorkDays / shiftRestDays / shiftAnchorDate
         *  - calendar-aware alarms: prepBufferMinutes / calendarSkipIfNoEvent
         * (soundUri, scheduleType, pausedFrom/Until already existed as reserved columns.)
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE alarms ADD COLUMN shiftWorkDays INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE alarms ADD COLUMN shiftRestDays INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE alarms ADD COLUMN shiftAnchorDate INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE alarms ADD COLUMN prepBufferMinutes INTEGER NOT NULL DEFAULT 30")
                db.execSQL("ALTER TABLE alarms ADD COLUMN calendarSkipIfNoEvent INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v2 -> v3: Phase B ring/mission features. Additive columns only.
         *  - QR mission target barcode (missionBarcode, nullable)
         *  - Photo mission reference perceptual hash (missionPhotoHash, nullable)
         *  - Sunrise glow lead time (gentleWakeMinutes, default 0 = off)
         *  - Opt-in snooze coaching (snoozeCoaching, default 0 = off)
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE alarms ADD COLUMN missionBarcode TEXT")
                db.execSQL("ALTER TABLE alarms ADD COLUMN missionPhotoHash TEXT")
                db.execSQL("ALTER TABLE alarms ADD COLUMN gentleWakeMinutes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE alarms ADD COLUMN snoozeCoaching INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v3 -> v4: Phase C data features. Additive only — a new table for the
         * zero-permission bedtime signal used by the honest sleep estimate. No
         * existing columns change, so existing rows migrate untouched.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sleep_signals` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`occurredAt` INTEGER NOT NULL, " +
                        "`source` TEXT NOT NULL)"
                )
            }
        }

        /**
         * v4 -> v5: v3 event-triggered alarms. Additive only — a new `event_triggers`
         * table keyed 1:1 to an alarm (unique index on alarmId). Holds the event source
         * config (geofence destination + rings) and the live estimate state (refined ETA,
         * guaranteed fallback ETA, last-signal timestamp, last distance). No existing
         * columns change, so existing rows migrate untouched. No destructive fallback.
         *
         * The CREATE statements below are kept byte-for-byte in sync with Room's generated
         * schema (schemas/…/5.json) so the identity hash validates on first open.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `event_triggers` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`alarmId` INTEGER NOT NULL, " +
                        "`sourceType` TEXT NOT NULL, " +
                        "`enabled` INTEGER NOT NULL, " +
                        "`placeName` TEXT, " +
                        "`destLat` REAL, " +
                        "`destLng` REAL, " +
                        "`arrivalRadiusM` INTEGER NOT NULL, " +
                        "`outerRadiusM` INTEGER NOT NULL, " +
                        "`assumedSpeedKmh` INTEGER NOT NULL, " +
                        "`configJson` TEXT, " +
                        "`currentEtaMillis` INTEGER, " +
                        "`fallbackEtaMillis` INTEGER, " +
                        "`lastSignalAt` INTEGER, " +
                        "`lastDistanceM` INTEGER, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_event_triggers_alarmId` ON `event_triggers` (`alarmId`)"
                )
            }
        }

        /**
         * v5 -> v6: skip-next-occurrence. Additive only — one column on `alarms`
         * (skipUntil, default 0 = not skipping). No existing columns change.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE alarms ADD COLUMN skipUntil INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v6 -> v7: recycle bin. Additive only — one column on `alarms`
         * (deletedAt, default 0 = live). No existing columns change.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE alarms ADD COLUMN deletedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v7 -> v8: pending-snooze visibility. Additive only — one column on `alarms`
         * (snoozedUntil, default 0 = not snoozed) so the list can show "Snoozed · rings 7:12"
         * instead of "off" after a one-shot alarm rings and is snoozed. No existing columns change.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE alarms ADD COLUMN snoozedUntil INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v8 -> v9: friend arrival alerts. Additive only — two new tables, nothing existing
         * changes. `friends` holds the paired channel + its wrapped secret; `friend_watches`
         * holds the "tell me when they enter/leave here" rules.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `friends` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`topic` TEXT NOT NULL, " +
                        "`sharedKeyWrapped` TEXT NOT NULL, " +
                        "`selfId` TEXT NOT NULL, " +
                        "`shareUntil` INTEGER NOT NULL, " +
                        "`lastDistanceM` INTEGER, " +
                        "`lastHeardAt` INTEGER, " +
                        "`lastStatus` TEXT, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_friends_topic` ON `friends` (`topic`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `friend_watches` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`friendId` INTEGER NOT NULL, " +
                        "`placeName` TEXT NOT NULL, " +
                        "`lat` REAL NOT NULL, " +
                        "`lng` REAL NOT NULL, " +
                        "`radiusM` INTEGER NOT NULL, " +
                        "`condition` TEXT NOT NULL, " +
                        "`enabled` INTEGER NOT NULL, " +
                        "`lastFiredAt` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_friend_watches_friendId` " +
                        "ON `friend_watches` (`friendId`)"
                )
            }
        }

        /**
         * v9 -> v10: per-alarm "open this app on dismiss". Additive only — two nullable columns
         * on `alarms`. No existing columns change.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE alarms ADD COLUMN actionPackage TEXT")
                db.execSQL("ALTER TABLE alarms ADD COLUMN actionLabel TEXT")
            }
        }

        /**
         * People: the "meeting up" / "looking after someone" split, a local phone number for the Call
         * button, and per-watch alarm-grade alerting. All additive with defaults that reproduce the
         * old behaviour exactly (everyone is a "meet" contact; no watch rings).
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE friends ADD COLUMN relationship TEXT NOT NULL DEFAULT 'meet'")
                db.execSQL("ALTER TABLE friends ADD COLUMN phoneNumber TEXT")
                db.execSQL("ALTER TABLE friend_watches ADD COLUMN alertAsAlarm INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "alarmtracker.db"
                ).addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                    MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11
                ).build().also { instance = it }
            }
    }
}
