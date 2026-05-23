package com.kiwi.assistant.updater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/**
 * PackageInstaller.commit() necesita un IntentSender al que reportar el
 * resultado de la instalación. Este receiver solo loguea — con Device
 * Owner el sistema instala sin pedir nada, así que normalmente
 * STATUS_SUCCESS aparece y nada más.
 *
 * Si STATUS_PENDING_USER_ACTION llega, significa que la app no es Device
 * Owner; en producción no debería pasar, pero en dev sirve para que la
 * instalación entre por el flujo normal del sistema.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        when (status) {
            PackageInstaller.STATUS_SUCCESS ->
                Log.i(TAG, "install succeeded")
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                Log.i(TAG, "install requires user confirmation (not Device Owner?)")
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm != null) {
                    confirm.flags = confirm.flags or Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(confirm)
                }
            }
            else -> Log.w(TAG, "install failed status=$status message=$message")
        }
    }

    companion object {
        const val ACTION = "com.kiwi.assistant.UPDATE_INSTALL_RESULT"
        private const val TAG = "InstallResultReceiver"
    }
}
