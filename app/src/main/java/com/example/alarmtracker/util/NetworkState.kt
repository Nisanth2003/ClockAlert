package com.example.alarmtracker.util

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import androidx.annotation.StringRes
import com.example.alarmtracker.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Whether this phone can reach the internet right now, and how to tell the user when it can't.
 *
 * Every network call in this app is a keyless public endpoint behind [Http] (place search, routing,
 * map tiles) or a user-supplied host (Jira, the ntfy relay), and every one of them degrades to
 * "returned nothing". That is correct behaviour for the plumbing, but on its own it produced actively
 * misleading UI with no internet: a place search said "No places matched that search" (the search
 * never happened), and connecting Jira said "Check the URL, email, and token" (they were fine).
 *
 * So callers use this in two places:
 *  - BEFORE a request, [blocked] tells them there is no point trying — show [promptToConnect], which
 *    offers to take the user straight to the switch that fixes it, rather than burning two 8-second
 *    timeouts to arrive at a wrong error message;
 *  - AFTER a failure, [explain] turns the state into an honest sentence — including the case that
 *    catches people out, being connected to a Wi-Fi that has no internet on it.
 *
 * Deliberately NOT a live-monitoring class: there is no callback registration, no observer to leak
 * and no background cost. It reads [ConnectivityManager] on demand at the moment of a request, which
 * is the only moment the answer matters.
 */
object NetworkState {

    enum class Status {
        /** A network with internet capability, and the system has confirmed it actually works. */
        ONLINE,

        /**
         * Connected, but the system has not validated internet access — a captive portal (hotel /
         * café / campus Wi-Fi awaiting sign-in), or a link that is up but dead. Requests are still
         * worth attempting, because validation also lags on some networks and VPNs.
         */
        UNVALIDATED,

        /** Airplane mode is on, and nothing will work until it is off. */
        AIRPLANE,

        /** No network at all: Wi-Fi and mobile data both off, or out of range. */
        OFFLINE
    }

    fun status(context: Context): Status {
        // Airplane mode is checked LAST, not first: Wi-Fi can legitimately be switched on while
        // airplane mode is on, and that phone is online. The flag only explains an absent network.
        val cm = context.getSystemService(ConnectivityManager::class.java)
            ?: return Status.ONLINE // no ConnectivityManager to ask — don't block on a guess
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                Status.ONLINE
            } else {
                Status.UNVALIDATED
            }
        }
        return if (airplaneModeOn(context)) Status.AIRPLANE else Status.OFFLINE
    }


    /**
     * True when traffic is going through a VPN.
     *
     * Worth surfacing, but NOT for the reason people expect: a VPN does **not** move your location. An
     * Android fix comes from GPS satellites, nearby Wi-Fi and cell towers — none of which a VPN touches —
     * so `getCurrentLocation()` returns where the phone physically is whether a VPN is on or off, and
     * arrival alarms are unaffected. Telling users to disable their VPN to "fix" their location would be
     * wrong advice, and it would cost them their privacy for nothing.
     *
     * What a VPN genuinely does affect is anything a SERVER decides from the IP address it sees: place
     * search can come back weighted to the exit country, so a query can look like it landed abroad. That
     * is the specific, honest thing to warn about.
     */
    fun vpnActive(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    /**
     * True when a network request cannot possibly succeed, so the caller should explain instead of
     * trying. [Status.UNVALIDATED] deliberately does NOT count: never block a request that might
     * work — a wrongly-blocked feature is worse than a request that fails and says why.
     */
    fun blocked(context: Context): Boolean = when (status(context)) {
        Status.AIRPLANE, Status.OFFLINE -> true
        Status.ONLINE, Status.UNVALIDATED -> false
    }

    /**
     * An honest sentence for a network call that just failed, given the current state.
     *
     * [serviceFailureRes] is the caller's own "we reached the internet but not this service" string —
     * only that case is specific to the feature; the rest is about the phone.
     */
    fun explain(context: Context, @StringRes serviceFailureRes: Int): String =
        when (status(context)) {
            Status.AIRPLANE -> context.getString(R.string.net_airplane_body)
            Status.OFFLINE -> context.getString(R.string.net_offline_body)
            Status.UNVALIDATED -> context.getString(R.string.net_unvalidated_body)
            Status.ONLINE -> context.getString(serviceFailureRes)
        }

    /**
     * Tells the user they are offline and offers to open the switch that fixes it — the Android 10+
     * internet panel drops down over the app, so turning Wi-Fi on takes one tap and they come back
     * to what they were doing.
     *
     * [featureRes] names what needed the connection ("Searching for a place needs internet"), because
     * a bare "You're offline" leaves them guessing which part of the screen just failed.
     */
    fun promptToConnect(context: Context, @StringRes featureRes: Int) {
        val airplane = status(context) == Status.AIRPLANE
        val body = context.getString(
            if (airplane) R.string.net_airplane_body else R.string.net_offline_body
        )
        MaterialAlertDialogBuilder(context)
            .setTitle(if (airplane) R.string.net_airplane_title else R.string.net_offline_title)
            .setMessage(context.getString(R.string.net_prompt_fmt, context.getString(featureRes), body))
            .setNegativeButton(R.string.net_not_now, null)
            .setPositiveButton(R.string.net_turn_on) { _, _ -> openConnectivitySettings(context) }
            .show()
    }

    /**
     * The internet panel (API 29+) if it exists, else the Wi-Fi settings screen, else top-level
     * Settings. Each step is guarded because panels are optional for an OEM to implement and MIUI in
     * particular has been known to omit them.
     */
    fun openConnectivitySettings(context: Context) {
        if (status(context) == Status.AIRPLANE) {
            if (start(context, Settings.ACTION_AIRPLANE_MODE_SETTINGS)) return
        }
        if (Build.VERSION.SDK_INT >= 29 && start(context, Settings.Panel.ACTION_INTERNET_CONNECTIVITY)) return
        if (start(context, Settings.ACTION_WIRELESS_SETTINGS)) return
        start(context, Settings.ACTION_SETTINGS)
    }

    private fun start(context: Context, action: String): Boolean = try {
        context.startActivity(
            Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (_: Exception) {
        false
    }

    @Suppress("DEPRECATION") // Settings.Global.AIRPLANE_MODE_ON has no non-deprecated replacement
    private fun airplaneModeOn(context: Context): Boolean = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
    } catch (_: Exception) {
        false
    }
}
