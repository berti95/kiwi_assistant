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

    /**
     * Pantalla completa con el sitio web de YouTube (m.youtube.com)
     * cargado en un WebView. Fallback para todo lo que no tengamos
     * como tool específica: home, suscripciones, recomendaciones,
     * canales sueltos. El usuario interactúa con el dedo.
     */
    data class BrowseYouTube(val url: String) : Scene

    /**
     * Lo que está sonando en Spotify ahora mismo. Carátula grande,
     * título, artista, álbum, indicador play/pause y barra de
     * progreso (estática — refresca al pedir "qué suena" de nuevo).
     *
     * Renderiza para cualquier dispositivo Spotify Connect activo
     * del usuario (móvil, PC, altavoz) — el tablet aún no es un
     * dispositivo de Connect.
     */
    data class NowPlaying(
        val title: String,
        val artist: String,
        val album: String,
        val albumArtUrl: String?,
        val isPlaying: Boolean,
        val durationMs: Long,
        val progressMs: Long,
    ) : Scene

    /**
     * Lista de tareas pendientes capturadas con "Kiwi, apúntame que…".
     * Render dedicado (lista grande) mientras dura la conversación;
     * un resumen reducido aparece también en [Idle] vía [HomeSnapshot].
     */
    data class TodoList(val items: List<TodoItem>) : Scene

    /**
     * Cuenta atrás de cocina. ``endsAtMs`` = epoch-ms en el que el
     * timer suena. ``label`` opcional ("pasta", "horno"). Cuando
     * ``endsAtMs == 0`` la escena interpreta "no hay timer activo"
     * y se cierra sola — así el tool ``timer_cancel`` puede empujar
     * la misma escena vacía y el tablet vuelve a la home.
     */
    data class Timer(val endsAtMs: Long, val label: String) : Scene

    /**
     * Lista de despertadores activos, empujada tras alarm_set /
     * alarm_cancel / alarm_list. Visualiza lo que hay programado;
     * la sincronización con AlarmManager la dispara el ViewModel
     * cuando llega esta escena (o cuando llegan alarmas en
     * homeSnapshot).
     */
    data class AlarmList(val items: List<AlarmItem>) : Scene

    /**
     * Despertador sonando ahora mismo. La activa el BroadcastReceiver
     * de Android cuando AlarmManager dispara el PendingIntent;
     * MainActivity lee los extras y se la mete al ViewModel.
     */
    data class AlarmRinging(
        val alarmId: String,
        val label: String,
        val firesAtMs: Long,
    ) : Scene

    /**
     * Lista de la compra. Mismo patrón que TodoList: tap en pendiente
     * lo marca como comprado, tap en comprado lo elimina; "Kiwi ya he
     * hecho la compra" la vacía entera vía tool.
     */
    data class ShoppingList(val items: List<ShoppingItem>) : Scene
}

/**
 * Un artículo de la lista de la compra. Reutiliza la misma forma que
 * TodoItem porque, en práctica, los renderers son hermanos.
 */
data class ShoppingItem(
    val id: String,
    val text: String,
    val completed: Boolean,
)

/**
 * Despertador one-shot que el backend persiste en GCS. ``firesAtMs``
 * es el momento absoluto en epoch-ms; el tablet usa AlarmManager.
 * setAlarmClock para programarlo localmente.
 */
data class AlarmItem(
    val id: String,
    val firesAtMs: Long,
    val label: String,
)

/**
 * One persistent TODO. ``completed`` reflects whether the user already
 * marked it done — items still appear in the list (visually struck
 * through) until they are explicitly removed.
 */
data class TodoItem(
    val id: String,
    val text: String,
    val completed: Boolean,
)

/**
 * "Now playing" chip rendered on the home dashboard. Slimmer than
 * [Scene.NowPlaying]: only what the chip needs.
 */
data class NowPlayingChip(
    val title: String,
    val artist: String,
    val albumArtUrl: String?,
)

/**
 * Current-weather snapshot for the home dashboard, sourced from
 * Open-Meteo via the backend's /api/home endpoint. ``icon`` is one
 * of the strings emitted by ``weather._icon`` (clear / partly_cloudy
 * / cloudy / fog / rain / snow / storm); the UI maps it to an emoji.
 */
data class WeatherInfo(
    val temperatureC: Double,
    val description: String,
    val icon: String,
)

/**
 * Aggregated snapshot for the Idle/Home dashboard. Fetched periodically
 * from `GET /api/home`. ``null`` means the tablet hasn't received a
 * snapshot yet (first load) — the home scene falls back to the bare
 * clock in that case.
 */
data class HomeSnapshot(
    val eventsToday: List<CalendarEvent>,
    val todos: List<TodoItem>,
    val nowPlaying: NowPlayingChip?,
    val weather: WeatherInfo?,
    val alarms: List<AlarmItem>,
)

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
