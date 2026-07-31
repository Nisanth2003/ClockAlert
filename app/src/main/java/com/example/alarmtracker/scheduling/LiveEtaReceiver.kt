package com.example.alarmtracker.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Wake-up for the sparse "how far away am I?" re-check that keeps an arrival alarm's time honest.
 * [LiveEtaTracker.runCheck] re-arms the next one, so this receiver is the whole loop.
 */
class LiveEtaReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CHECK) return
        val appContext = context.applicationContext
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                LiveEtaTracker.runCheck(appContext)
            } finally {
                result.finish()
            }
        }
    }

    companion object {
        const val ACTION_CHECK = "com.example.alarmtracker.ACTION_LIVE_ETA_CHECK"
    }
}
