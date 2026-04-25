package com.kiwi.assistant.util

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.ln

/**
 * Ajusta el brillo de la ventana en función del sensor de luz ambiente.
 *
 * El mapeo lux → brillo es logarítmico para que la transición se sienta
 * natural en penumbra (donde el ojo es más sensible a cambios pequeños).
 * Si el dispositivo no tiene sensor de luz, el componente queda inactivo
 * y la ventana usa el brillo del sistema.
 */
class BrightnessManager(private val activity: Activity) {

    private val sensorManager =
        activity.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val lightSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val lux = event.values.firstOrNull() ?: return
            applyBrightness(luxToBrightness(lux))
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun start() {
        val sensor = lightSensor
        if (sensor == null) {
            Log.i(TAG, "No ambient light sensor; leaving brightness to the system.")
            return
        }
        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stop() {
        sensorManager.unregisterListener(listener)
        // Restore system-managed brightness when we're not driving it.
        applyBrightness(SYSTEM_BRIGHTNESS)
    }

    private fun applyBrightness(value: Float) {
        activity.runOnUiThread {
            val params = activity.window.attributes
            params.screenBrightness = value
            activity.window.attributes = params
        }
    }

    private fun luxToBrightness(lux: Float): Float {
        if (lux <= 0f) return MIN_BRIGHTNESS
        // ln(1+lux) / ln(1+REF_LUX) maps 0..REF_LUX onto 0..1 with a soft curve.
        val normalized = ln(1f + lux) / ln(1f + REF_LUX)
        return normalized.coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS)
    }

    private companion object {
        const val TAG = "BrightnessManager"
        const val MIN_BRIGHTNESS = 0.02f
        const val MAX_BRIGHTNESS = 1.0f
        const val REF_LUX = 600f
        const val SYSTEM_BRIGHTNESS = -1f
    }
}
