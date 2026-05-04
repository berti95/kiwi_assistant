package com.kiwi.assistant.ui

/**
 * What the main canvas of the tablet is currently showing.
 *
 * Orthogonal to [PipelineState]: the audio pipeline (Listening,
 * Processing, …) renders as an overlay **on top** of whichever scene
 * is active. So Kiwi can be in the middle of answering a question
 * while a NowPlaying scene is up — the user keeps seeing what's
 * playing and the conversation just dims/floats over it.
 *
 * The default is [Idle] (the clock). Tools that produce visible
 * output (a calendar lookup, a Spotify play call, a YouTube search)
 * push their own scene; long-press / explicit close returns to Idle.
 *
 * Future scenes (added in later fases) will be:
 *   • `Calendar(events)` — a list of upcoming events.
 *   • `NowPlaying(...)`  — Spotify / YouTube currently-playing card.
 *   • `VideoPlayer(...)` — embedded YouTube player WebView.
 *   • `BrowseYT`         — full YouTube WebView fallback.
 */
sealed interface Scene {
    /** Reloj — pantalla de reposo por defecto. */
    data object Idle : Scene
}
