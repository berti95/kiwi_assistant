package com.kiwi.assistant.ui

/**
 * State of the audio pipeline (mic → backend → Gemini → speaker).
 *
 * Orthogonal to [Scene]: the pipeline's progress is a transient
 * concern (you only "see" Listening or Processing for a second or
 * two), while a [Scene] persists until something else replaces it.
 *
 * The pipeline UI renders as an overlay **on top** of the active
 * scene, except in [Idle] where there is no overlay so the scene
 * shows through unchanged.
 */
sealed interface PipelineState {
    /** Pipeline detenido. Sin sesión abierta. No overlay. */
    data object Idle : PipelineState

    /**
     * WebSocket abriendo (entre `openSession` y `session.ready`). Estado
     * transitorio: en cuanto el server confirma, el ViewModel arranca el
     * primer turno automáticamente — el usuario no toca nada aquí.
     */
    data object Connecting : PipelineState

    /** Capturando audio del usuario (entre activity.start y activity.end). */
    data object Listening : PipelineState

    /**
     * Audio del usuario enviado, esperando primera respuesta de Gemini.
     *
     * `userTranscript` se va llenando con la transcripción de lo que el
     * usuario acaba de decir (Gemini suele entregarla antes del primer
     * audio chunk de respuesta), para que la UI pueda mostrarla y el
     * usuario sepa si Kiwi le entendió.
     */
    data class Processing(val userTranscript: String = "") : PipelineState

    /** Gemini está respondiendo; se muestra el transcript acumulado. */
    data class Responding(
        val userTranscript: String,
        val kiwiTranscript: String,
    ) : PipelineState

    data class Error(val message: String) : PipelineState
}
