package com.kiwi.assistant.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import com.kiwi.assistant.log.KLog

/**
 * Pide foco de audio transitorio mientras Kiwi conversa, para que la
 * app que esté sonando (YouTube, Spotify…) se pause sola y reanude al
 * terminar — igual que hace Google Assistant.
 *
 * AUDIOFOCUS_GAIN_TRANSIENT: las apps que respetan el foco PAUSAN
 * (no bajan volumen) mientras lo tenemos, y al soltarlo (abandon)
 * reanudan donde estaban. Es lo que queremos para una conversación:
 * silencio total mientras hablas con Kiwi, y la música/vídeo vuelve
 * después.
 *
 * Se adquiere al abrir sesión y se suelta al cerrarla, no por turno,
 * para que el vídeo no se reanude entre pregunta y respuesta.
 */
class AudioFocusController(context: Context) {

    private val audio =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var request: AudioFocusRequest? = null

    fun acquire() {
        val mgr = audio ?: return
        if (request != null) return  // ya lo tenemos
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val req = AudioFocusRequest.Builder(
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            )
                .setAudioAttributes(attrs)
                // No queremos que nos avisen de cambios de foco: Kiwi
                // controla su propio ciclo (abre/cierra sesión).
                .setWillPauseWhenDucked(true)
                .build()
            request = req
            val result = mgr.requestAudioFocus(req)
            KLog.i(TAG, "requestAudioFocus → $result")
        } else {
            @Suppress("DEPRECATION")
            mgr.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            )
        }
    }

    fun release() {
        val mgr = audio ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            request?.let { mgr.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            mgr.abandonAudioFocus(null)
        }
        request = null
    }

    private companion object {
        const val TAG = "AudioFocusController"
    }
}
