package com.example.alarmtracker.util

import android.util.Log

/**
 * Debug logging that stays quiet in a shipped build.
 *
 * The traces this replaces carried things worth not broadcasting to logcat — which apps a user
 * tracks (their AI service, their delivery apps), alarm ids, parsed reset times. `Log.isLoggable`
 * defaults to false for DEBUG, so nothing is emitted unless a developer explicitly turns a tag on:
 *
 *     adb shell setprop log.tag.EventAlarmCoordinator DEBUG
 *
 * Warnings and errors are left alone — those are genuine diagnostics and carry no user data.
 */
object Dbg {

    fun d(tag: String, message: () -> String) {
        if (Log.isLoggable(tag, Log.DEBUG)) Log.d(tag, message())
    }
}
