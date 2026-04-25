package com.kiwi.assistant.ui

sealed interface KiwiState {
    data object Idle : KiwiState
    data object Listening : KiwiState
    data object Processing : KiwiState
    data class Responding(val transcript: String) : KiwiState
    data class Error(val message: String) : KiwiState
}
