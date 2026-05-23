package com.kiwi.assistant.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.kiwi.assistant.log.KLog
import com.kiwi.assistant.ui.PipelineState

/**
 * Ventana flotante (overlay) que muestra el estado de la conversación
 * de Kiwi ENCIMA de la app que esté en primer plano (YouTube, etc.),
 * sin cambiar de app — estilo Google Assistant.
 *
 * Usa un [View] clásico montado a mano (no Compose) porque hospedar
 * Compose en una ventana de WindowManager requiere cablear
 * Lifecycle/ViewModelStore/SavedStateRegistry owners a mano, que es
 * frágil. Una pill con icono+texto cubre lo que necesita el overlay.
 *
 * FLAG_NOT_FOCUSABLE: la ventana recibe sus propios taps (para
 * cerrar) pero NO roba el foco de input a la app de debajo, así
 * YouTube sigue reproduciendo/respondiendo a gestos.
 *
 * Requiere el permiso "Mostrar sobre otras apps" (SYSTEM_ALERT_WINDOW),
 * que ya pedimos en MainActivity.
 */
class ConversationOverlay(private val context: Context) {

    private val wm =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: View? = null
    private var label: TextView? = null

    /** Muestra la pill. [onTap] cierra la conversación. No-op si ya está. */
    fun show(onTap: () -> Unit) {
        if (root != null) return
        val text = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            maxLines = 3
            text = "Escuchando…"
        }
        label = text
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(56, 36, 56, 36)
            background = GradientDrawable().apply {
                cornerRadius = 64f
                setColor(Color.parseColor("#E6101010"))
                setStroke(2, Color.parseColor("#33FFFFFF"))
            }
            addView(text)
            setOnClickListener { onTap() }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 160
        }
        runCatching { wm.addView(pill, params) }
            .onSuccess { root = pill }
            .onFailure { KLog.w(TAG, "addView overlay falló: ${it.message}") }
    }

    /** Refleja el estado del pipeline en el texto de la pill. */
    fun render(state: PipelineState) {
        val txt = when (state) {
            PipelineState.Connecting -> "Conectando…"
            is PipelineState.Reconnecting -> "Reconectando…"
            PipelineState.Listening -> "Escuchando…"
            is PipelineState.Processing ->
                state.userTranscript.takeIf { it.isNotBlank() } ?: "Pensando…"
            is PipelineState.Responding ->
                state.kiwiTranscript.takeIf { it.isNotBlank() } ?: "Respondiendo…"
            is PipelineState.Error -> state.message
            PipelineState.Idle -> return
        }
        label?.text = txt
    }

    fun hide() {
        root?.let { v -> runCatching { wm.removeView(v) } }
        root = null
        label = null
    }

    private companion object {
        const val TAG = "ConversationOverlay"
    }
}
