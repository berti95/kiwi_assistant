package com.kiwi.assistant.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import com.kiwi.assistant.log.KLog
import com.kiwi.assistant.ui.AlarmItem

/**
 * Reconciles the backend's authoritative alarm list with Android's
 * AlarmManager.
 *
 * setAlarmClock is the right API here: it's the user-facing alarm
 * clock variant, exempt from Doze and SCHEDULE_EXACT_ALARM, and shows
 * the system "next alarm" indicator so the user knows something is
 * armed. Using a stable PendingIntent per alarm id (request code =
 * id.hashCode()) means re-scheduling the same id transparently
 * replaces the previous one — handles snooze and edits.
 *
 * Reconcile policy: keep a Set<id> in SharedPreferences so we know
 * what was scheduled before the process restarted. Each [sync] call
 * cancels alarms not in the new list, schedules new ones, and
 * re-schedules existing ones (in case their fires_at_ms changed).
 *
 * On boot, MainActivity is launched (HOME + LAUNCHER intent
 * filters), the home poller fires immediately, and the first
 * snapshot reaches [sync] within seconds — so we don't need a
 * BOOT_COMPLETED receiver of our own.
 */
class AlarmScheduler(private val context: Context) {

    private val alarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val prefs =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun sync(items: List<AlarmItem>) {
        val newIds = items.map { it.id }.toSet()
        val previouslyScheduled = prefs.getStringSet(KEY_SCHEDULED_IDS, emptySet())
            ?: emptySet()

        for (id in previouslyScheduled - newIds) {
            runCatching { cancelById(id) }.onFailure { e ->
                KLog.w(TAG, "cancel $id failed: ${e::class.simpleName}: ${e.message}")
            }
        }
        for (item in items) {
            runCatching { schedule(item) }.onFailure { e ->
                // Lo más típico aquí es SecurityException en Android
                // 14+ cuando el SO restringe setAlarmClock para apps
                // sin la categoría correcta. No queremos que una
                // alarma rota tire la app entera; logueamos y
                // seguimos con el resto.
                KLog.w(
                    TAG,
                    "schedule ${item.id} failed: " +
                        "${e::class.simpleName}: ${e.message}",
                )
            }
        }

        prefs.edit { putStringSet(KEY_SCHEDULED_IDS, newIds) }
        KLog.i(
            TAG,
            "sync: ${items.size} active, " +
                "${(previouslyScheduled - newIds).size} cancelled",
        )
    }

    private fun schedule(item: AlarmItem) {
        val intent = Intent(context, AlarmRingReceiver::class.java).apply {
            action = AlarmRingReceiver.ACTION
            putExtra(AlarmRingReceiver.EXTRA_ID, item.id)
            putExtra(AlarmRingReceiver.EXTRA_LABEL, item.label)
            putExtra(AlarmRingReceiver.EXTRA_FIRES_AT, item.firesAtMs)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            item.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // showIntent (the icon-tap target) is a no-op intent that just
        // reopens the launcher activity — the user's already there
        // most of the time anyway. Required field but we don't depend
        // on it for our own UX.
        val show = PendingIntent.getActivity(
            context,
            item.id.hashCode(),
            context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(item.firesAtMs, show),
            pi,
        )
    }

    private fun cancelById(id: String) {
        val intent = Intent(context, AlarmRingReceiver::class.java).apply {
            action = AlarmRingReceiver.ACTION
        }
        val pi = PendingIntent.getBroadcast(
            context,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.cancel(pi)
    }

    private companion object {
        const val TAG = "AlarmScheduler"
        const val PREFS_NAME = "kiwi_alarms"
        const val KEY_SCHEDULED_IDS = "scheduled_ids"
    }
}
