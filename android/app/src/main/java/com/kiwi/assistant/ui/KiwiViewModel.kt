package com.kiwi.assistant.ui

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class KiwiViewModel : ViewModel() {

    private val _state = MutableStateFlow<KiwiState>(KiwiState.Idle)
    val state: StateFlow<KiwiState> = _state.asStateFlow()

    /**
     * V1 activation gesture. While we don't have a wake word, the user taps
     * anywhere on the screen to start a session and taps again on the
     * listening screen to cancel.
     */
    fun onTap() {
        when (_state.value) {
            KiwiState.Idle -> _state.value = KiwiState.Listening
            KiwiState.Listening -> _state.value = KiwiState.Idle
            else -> Unit
        }
    }

    fun setState(next: KiwiState) {
        _state.value = next
    }
}
