package com.kiwi.assistant.ui

sealed interface KiwiState {
    /** Reloj. Sin sesión abierta. */
    data object Idle : KiwiState

    /** Sesión Gemini abierta, esperando que el usuario toque para hablar. */
    data object Standby : KiwiState

    /** Capturando audio del usuario (entre activity.start y activity.end). */
    data object Listening : KiwiState

    /** Audio del usuario enviado, esperando primera respuesta de Gemini. */
    data object Processing : KiwiState

    /** Gemini está respondiendo; se muestra el transcript acumulado. */
    data class Responding(val transcript: String) : KiwiState

    data class Error(val message: String) : KiwiState
}
