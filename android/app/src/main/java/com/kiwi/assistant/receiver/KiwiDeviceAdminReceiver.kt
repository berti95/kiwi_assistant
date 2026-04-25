package com.kiwi.assistant.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context

class KiwiDeviceAdminReceiver : DeviceAdminReceiver() {
    companion object {
        fun componentName(context: Context): ComponentName =
            ComponentName(context, KiwiDeviceAdminReceiver::class.java)
    }
}
