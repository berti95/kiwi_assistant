package com.kiwi.assistant.service

import com.kiwi.assistant.ui.Scene

/**
 * Puente de un solo valor entre [KiwiVoiceService] (conversación en
 * overlay, en background) y MainActivity.
 *
 * Cuando una respuesta del overlay produce una escena visual
 * (calendario, lista de vídeos, NowPlaying…), el service la deja aquí
 * y trae Kiwi al frente; MainActivity la consume en onResume y la
 * pinta en su ViewModel. Para respuestas de voz pura (mates, hora) no
 * se usa — esas se quedan en la pill.
 *
 * Proceso único / un solo usuario, así que un holder volátil basta;
 * la última escena gana y se consume una sola vez.
 */
object ForegroundSceneRelay {

    @Volatile
    private var pending: Scene? = null

    fun put(scene: Scene) {
        pending = scene
    }

    /** Devuelve y limpia la escena pendiente (o null si no hay). */
    fun consume(): Scene? {
        val s = pending
        pending = null
        return s
    }
}
