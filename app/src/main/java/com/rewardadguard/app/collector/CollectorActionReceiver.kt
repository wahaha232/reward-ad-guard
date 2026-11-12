package com.rewardadguard.app.collector

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Notification buttons for a running collection (pause / resume / stop). */
class CollectorActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            CollectorNotification.ACTION_PAUSE -> CollectorController.pause()
            CollectorNotification.ACTION_RESUME -> CollectorController.resume()
            CollectorNotification.ACTION_STOP -> CollectorController.stop()
        }
    }
}
