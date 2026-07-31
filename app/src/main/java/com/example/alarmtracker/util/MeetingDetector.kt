package com.example.alarmtracker.util

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * "Are you on a call right now?" — used so an alarm doesn't blast out of the speaker, get picked up
 * by the microphone, and broadcast itself to everyone in your Teams / Meet / Zoom / WhatsApp call.
 *
 * DETECTION: the audio mode. Every conferencing and VoIP app puts the device into
 * [AudioManager.MODE_IN_COMMUNICATION] for the duration of a call (a cellular call uses
 * [AudioManager.MODE_IN_CALL]). It needs no permission, no notification access and no list of
 * "known meeting apps" to maintain, so it works for any app that handles audio properly.
 *
 * WHAT WE CAN AND CANNOT DO: an alarm coming out of the phone's speaker while the same phone's mic
 * is open will be heard by the other side — that is acoustics, not routing, and no app can stop it.
 * So the only two honest options are to send the sound somewhere only the user can hear (a headset)
 * or not to make a sound at all. [privateOutputDevice] finds the first; the ring service falls back
 * to the second, keeping vibration and the full-screen ring so the alarm is never actually missed.
 */
object MeetingDetector {

    /** True when some app currently owns the audio path for a call/meeting. */
    fun inMeeting(context: Context): Boolean {
        val am = context.getSystemService(AudioManager::class.java) ?: return false
        return when (am.mode) {
            AudioManager.MODE_IN_CALL,
            AudioManager.MODE_IN_COMMUNICATION -> true
            // Call screening / redirection also mean a live call path (API 30+ / 31+).
            AudioManager.MODE_CALL_SCREENING -> Build.VERSION.SDK_INT >= 30
            else -> false
        }
    }

    /**
     * An output the user alone can hear — a wired, USB or Bluetooth headset, or a hearing aid.
     * Null when the only way out is the phone's own speaker, i.e. the room would hear it too.
     *
     * A2DP is skipped deliberately: during a call the headset is in SCO mode and its A2DP profile
     * isn't carrying anything, so preferring it would silently drop the alarm.
     */
    fun privateOutputDevice(context: Context): AudioDeviceInfo? {
        val am = context.getSystemService(AudioManager::class.java) ?: return null
        val outputs = runCatching { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }
            .getOrNull() ?: return null
        return PRIVATE_TYPES.firstNotNullOfOrNull { type ->
            outputs.firstOrNull { it.type == type }
        }
    }

    /** True when [device] is the phone's own loudspeaker or earpiece — i.e. not private. */
    fun isBuiltIn(device: AudioDeviceInfo?): Boolean =
        device != null && (
            device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                device.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE ||
                (Build.VERSION.SDK_INT >= 31 && device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE)
            )

    /** In preference order: wired first (most reliable to route to), then USB, SCO, hearing aid. */
    private val PRIVATE_TYPES = intArrayOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_HEARING_AID
    ).toList()
}
