package com.kiwi.assistant.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kiwi.assistant.log.KLog
import com.kiwi.assistant.ui.MainActivity

/**
 * Fires when AlarmManager's user-facing alarm clock triggers.
 *
 * Launches MainActivity with extras describing which alarm went off
 * so the activity flips into the AlarmRinging scene. Going via a
 * direct activity intent (instead of, say, posting a notification
 * the user has to tap) keeps the despertador appliance-like — the
 * pantalla se enciende y muestra "¡Tiempo!" sin pasos manuales.
 */
class AlarmRingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val label = intent.getStringExtra(EXTRA_LABEL).orEmpty()
        val firesAt = intent.getLongExtra(EXTRA_FIRES_AT, 0L)
        KLog.i(TAG, "alarm fired: id=$id label=$label firesAt=$firesAt")

        val launch = Intent(context, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP,
            )
            putExtra(EXTRA_ID, id)
            putExtra(EXTRA_LABEL, label)
            putExtra(EXTRA_FIRES_AT, firesAt)
        }
        context.startActivity(launch)
    }

    companion object {
        const val ACTION = "com.kiwi.assistant.ALARM_RING"
        const val EXTRA_ID = "alarm_id"
        const val EXTRA_LABEL = "alarm_label"
        const val EXTRA_FIRES_AT = "alarm_fires_at"
        private const val TAG = "AlarmRingReceiver"
    }
}
