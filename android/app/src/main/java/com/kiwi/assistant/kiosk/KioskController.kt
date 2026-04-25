package com.kiwi.assistant.kiosk

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.BatteryManager
import android.provider.Settings
import android.util.Log
import com.kiwi.assistant.receiver.KiwiDeviceAdminReceiver

/**
 * Controla el modo kiosk basado en Device Owner + Lock Task Mode.
 *
 * Para que esto funcione la app debe haberse establecido como Device Owner
 * con `adb shell dpm set-device-owner com.kiwi.assistant/.receiver.KiwiDeviceAdminReceiver`
 * sobre una tablet en estado de fábrica. Sin Device Owner, las llamadas a
 * `enableKiosk()` se ignoran silenciosamente y la app sigue funcionando como
 * una app normal (útil para desarrollo).
 */
class KioskController(private val activity: Activity) {

    private val dpm =
        activity.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val adminComponent = KiwiDeviceAdminReceiver.componentName(activity)

    val isDeviceOwner: Boolean
        get() = dpm.isDeviceOwnerApp(activity.packageName)

    fun enableKiosk() {
        if (!isDeviceOwner) {
            Log.i(TAG, "Not Device Owner; skipping kiosk activation.")
            return
        }

        dpm.setLockTaskPackages(adminComponent, arrayOf(activity.packageName))
        dpm.setLockTaskFeatures(adminComponent, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)

        val pluggedFlags = (BatteryManager.BATTERY_PLUGGED_AC
            or BatteryManager.BATTERY_PLUGGED_USB
            or BatteryManager.BATTERY_PLUGGED_WIRELESS).toString()
        dpm.setGlobalSetting(
            adminComponent,
            Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
            pluggedFlags,
        )

        runCatching { activity.startLockTask() }
            .onFailure { Log.w(TAG, "startLockTask failed", it) }
    }

    fun disableKiosk() {
        if (!isDeviceOwner) return
        runCatching { activity.stopLockTask() }
    }

    private companion object {
        const val TAG = "KioskController"
    }
}
