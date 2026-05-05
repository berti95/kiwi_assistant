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
 * output push their own scene via the backend's ``scene.set``
 * message; long-press / explicit close returns to Idle.
 *
 * Future scenes (added in later fases) will be:
 *   • `NowPlaying(...)`  — Spotify / YouTube currently-playing card.
 *   • `VideoPlayer(...)` — embedded YouTube player WebView.
 *   • `BrowseYT`         — full YouTube WebView fallback.
 */
sealed interface Scene {
    /** Reloj — pantalla de reposo por defecto. */
    data object Idle : Scene

    /**
     * Lista de próximos eventos del calendario primario, devuelta por
     * la tool ``calendar_list_events``. ``period`` es el filtro
     * solicitado ("today" / "tomorrow" / "this_week" / "next_7_days")
     * y se muestra como subtítulo de la escena.
     */
    data class Calendar(
        val period: String,
        val events: List<CalendarEvent>,
    ) : Scene

    /**
     * Resultados de búsqueda de YouTube o contenido de una playlist.
     * Mismo composable, mismo wire-format ("video_list") — la única
     * diferencia es ``title``: la query entrecomillada para search,
     * el nombre de la playlist para playlist items.
     */
    data class VideoList(
        val title: String,
        val videos: List<VideoItem>,
    ) : Scene

    /** Lista de playlists propias del usuario. */
    data class PlaylistList(
        val playlists: List<PlaylistItem>,
    ) : Scene

    /**
     * Reproducción embebida de un video de YouTube (IFrame Player API
     * dentro de un WebView). Ocupa toda la pantalla; el HUD se queda
     * encima como en cualquier otra escena.
     */
    data class VideoPlayer(
        val videoId: String,
        val title: String,
        val channel: String,
    ) : Scene
}

/** A YouTube video item, used in [Scene.VideoList]. */
data class VideoItem(
    val videoId: String,
    val title: String,
    val channel: String,
    val durationLabel: String?,
    val thumbnailUrl: String?,
)

/** A YouTube playlist item, used in [Scene.PlaylistList]. */
data class PlaylistItem(
    val playlistId: String,
    val title: String,
    val itemCount: Int,
    val thumbnailUrl: String?,
)

/**
 * Event payload shared between the wire format and the Compose scene.
 *
 * Times are kept as strings (ISO 8601 with offset for timed events,
 * `YYYY-MM-DD` for all-day) — the UI does the parsing/formatting,
 * which keeps the wire format trivial for the backend to produce.
 */
data class CalendarEvent(
    val title: String,
    val startsAt: String,
    val endsAt: String,
    val location: String?,
    val allDay: Boolean,
)
