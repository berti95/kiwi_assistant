package com.kiwi.assistant.network

import kotlinx.serialization.Serializable

/**
 * Wire protocol shared with the backend (see backend/kiwi_backend/protocol.py).
 *
 * Both directions are JSON with a discriminator field "type". We model
 * outbound messages as concrete classes and parse inbound ones by reading
 * the type field then decoding into the right class.
 */
object Protocol {
    const val TYPE_SESSION_START = "session.start"
    const val TYPE_ACTIVITY_START = "activity.start"
    const val TYPE_AUDIO_INPUT = "audio.input"
    const val TYPE_ACTIVITY_END = "activity.end"
    const val TYPE_TURN_CANCEL = "turn.cancel"
    const val TYPE_AUDIO_END = "audio.end"
    const val TYPE_SESSION_END = "session.end"
    // Respuesta a un device_command "ui_read": etiquetas/textos visibles
    // en pantalla, para que el backend (Gemini) sepa qué hay.
    const val TYPE_UI_SCREEN = "ui.screen"

    const val TYPE_SESSION_READY = "session.ready"
    const val TYPE_AUDIO_OUTPUT = "audio.output"
    const val TYPE_TRANSCRIPT_INPUT = "transcript.input"
    const val TYPE_TRANSCRIPT_OUTPUT = "transcript.output"
    const val TYPE_RESPONSE_END = "response.end"
    const val TYPE_SCENE_SET = "scene.set"
    const val TYPE_ERROR = "error"

    const val CLOSE_EXPECTED_SESSION_START = 4001
    const val CLOSE_BAD_REQUEST = 4002
    const val CLOSE_INVALID_API_KEY = 4003
}

@Serializable
data class SessionStart(val type: String = Protocol.TYPE_SESSION_START, val api_key: String)

@Serializable
data class ActivityStart(val type: String = Protocol.TYPE_ACTIVITY_START)

@Serializable
data class AudioInput(val type: String = Protocol.TYPE_AUDIO_INPUT, val data: String)

@Serializable
data class ActivityEnd(val type: String = Protocol.TYPE_ACTIVITY_END)

@Serializable
data class TurnCancel(val type: String = Protocol.TYPE_TURN_CANCEL)

@Serializable
data class AudioEnd(val type: String = Protocol.TYPE_AUDIO_END)

@Serializable
data class SessionEnd(val type: String = Protocol.TYPE_SESSION_END)

@Serializable
data class UiScreen(
    val type: String = Protocol.TYPE_UI_SCREEN,
    val items: List<String>,
)
