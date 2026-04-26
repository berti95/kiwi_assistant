package com.kiwi.assistant.ui

sealed interface KiwiState {
    /** Reloj. Sin sesión abierta. */
    data object Idle : KiwiState

    /**
     * WebSocket abriendo (entre `openSession` y `session.ready`). Estado
     * transitorio: en cuanto el server confirma, el ViewModel arranca el
     * primer turno automáticamente — el usuario no toca nada aquí.
     */
    data object Connecting : KiwiState

    /** Capturando audio del usuario (entre activity.start y activity.end). */
    data object Listening : KiwiState

    /**
     * Audio del usuario enviado, esperando primera respuesta de Gemini.
     *
     * `userTranscript` se va llenando con la transcripción de lo que el
     * usuario acaba de decir (Gemini suele entregarla antes del primer
     * audio chunk de respuesta), para que la UI pueda mostrarla y el
     * usuario sepa si Kiwi le entendió.
     */
    data class Processing(val userTranscript: String = "") : KiwiState

    /** Gemini está respondiendo; se muestra el transcript acumulado. */
    data class Responding(
        val userTranscript: String,
        val kiwiTranscript: String,
    ) : KiwiState

    data class Error(val message: String) : KiwiState
}
