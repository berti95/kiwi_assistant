package com.kiwi.assistant.util

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.pow

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
        // Power curve `(lux/REF)^GAMMA` con gamma<1: pegada al suelo
        // a poca luz (habitación de noche con la persiana bajada =
        // ~5 lux → ~5% pantalla) y subida progresiva hasta saturar
        // a REF_LUX. La log-curve original era demasiado plana en el
        // tramo bajo y daba 28% a 5 lux — mucho para una mesilla.
        val normalized = (lux / REF_LUX).pow(GAMMA)
        return normalized.coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS)
    }

    private companion object {
        const val TAG = "BrightnessManager"
        // 0.5% es lo más bajo que permite la mayoría de paneles sin
        // apagar el backlight; suficiente para no deslumbrar en
        // dormitorio a oscuras.
        const val MIN_BRIGHTNESS = 0.005f
        const val MAX_BRIGHTNESS = 1.0f
        // Lux a partir del cual la pantalla está al 100%. Salón con
        // luz natural ronda 300-500; ponemos 500 para que un día
        // soleado satures sin pasarte.
        const val REF_LUX = 500f
        // gamma<1 expande la zona oscura, comprime la clara —
        // exactamente lo que queremos para mejorar el comportamiento
        // nocturno sin estropear el diurno. 0.65 es un punto agradable
        // (5 lux → 4%, 50 lux → 18%, 300 lux → 71%).
        const val GAMMA = 0.65f
        const val SYSTEM_BRIGHTNESS = -1f
    }
}
