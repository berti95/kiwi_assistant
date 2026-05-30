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
     * "Vista de pared" — tras N min en Idle sin tocar, el tablet
     * entra aquí: reloj gigante + una sola pieza de info útil
     * (próxima alarma / evento / canción / clima). Cualquier toque o
     * wake word lo saca. NO se mete al stack de navegación — es una
     * deriva natural desde Idle, no una scene a la que vuelves con
     * back.
     */
    data object Ambient : Scene

    /**
     * Lista de próximos eventos del calendario primario, devuelta por
     * la tool ``calendar_list_events``. ``period`` es el filtro
     * solicitado ("today" / "tomorrow" / "this_week" / "next_7_days")
     * y se muestra como subtítulo de la escena.
     */
    data class Calendar(
        val period: String,
        val events: List<CalendarEvent>,
        /**
         * Mensaje de error si el backend no pudo cargar los eventos
         * (típicamente Google OAuth caducado). Null en caso normal.
         * Si está presente, la UI muestra un empty state explicativo
         * con un botón "Renovar Google" en vez de "Día tranquilo".
         */
        val error: String? = null,
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

    /**
     * Pantalla de costes / telemetría. Empujada por el tool
     * ``usage_stats(period)`` con el agregado del backend.
     */
    data class UsageStats(
        val period: String,  // "today" / "7d" / "30d"
        val conversationCount: Int,
        val turnCount: Int,
        val audioInSeconds: Double,
        val audioOutSeconds: Double,
        val audioTotalSeconds: Double,
        val estimatedCostEur: Double,
        val topTools: List<UsageToolCount>,
        /** Coste por día (UTC) en el periodo, para el gráfico de barras. */
        val byDay: List<UsageDay> = emptyList(),
    ) : Scene

    /**
     * Lista de planes / viajes / eventos especiales separados del
     * Google Calendar. Empujada por los tools ``plan_add`` /
     * ``plan_list`` / ``plan_remove``. Un chip en el [Idle] dashboard
     * avisa cuando un plan cae en un día-milestone vía
     * [HomeSnapshot.planChip].
     */
    data class PlansList(val items: List<Plan>) : Scene
}

/** Una entrada de "tool más usada" dentro de [Scene.UsageStats]. */
data class UsageToolCount(val name: String, val count: Int)

/** Coste de un día concreto, para el gráfico de [Scene.UsageStats]. */
data class UsageDay(val date: String, val costEur: Double)

/**
 * Un plan / viaje / evento especial. ``date`` es ISO YYYY-MM-DD;
 * ``daysUntil`` viene calculado por el backend para evitar mismatches
 * de TZ entre tablet y servidor (días al plan según hora de Madrid).
 * Negativo => plan pasado (puede aparecer en el grace post-fecha).
 */
data class Plan(
    val id: String,
    val label: String,
    val date: String,
    val daysUntil: Int,
)

/**
 * Chip de cuenta atrás que muestra el dashboard del Home cuando un
 * plan cae en un día-milestone (1, 2, 3, 5, 7… definidos en el
 * backend). Es nullable en [HomeSnapshot] — la mayoría de días no
 * hay milestone y el chip no se pinta.
 */
data class PlanChip(
    val id: String,
    val label: String,
    val date: String,
    val daysUntil: Int,
)

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
 * Banner overlay para "evento empieza pronto". Se renderiza encima de
 * la escena activa (sin reemplazarla) cuando un evento del calendario
 * está dentro de los próximos 5 min y no se ha notificado aún. Se
 * dispara desde una coroutine en el ViewModel basada en
 * [HomeSnapshot.eventsToday]; auto-se-cierra tras 30 s o al pulsar la X.
 */
data class EventSoonBanner(
    val title: String,
    val startsAt: String,  // ISO 8601 con offset, mismo wire format que CalendarEvent
    val location: String?,
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
 *
 * Las tareas tienen dueño:
 * - [TodoOwner.Mine] (default): el usuario las hará. Pueden traer
 *   [dueDate] (ISO YYYY-MM-DD) y el UI las pinta en rojo si están
 *   vencidas, en ámbar si vencen hoy.
 * - [TodoOwner.Kiwi]: encargos para la IA. Sin fecha — el usuario las
 *   acumula y dice "revisa la lista" para que Kiwi las ejecute.
 */
data class TodoItem(
    val id: String,
    val text: String,
    val completed: Boolean,
    val owner: TodoOwner = TodoOwner.Mine,
    val dueDate: String? = null,
)

enum class TodoOwner { Mine, Kiwi }

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
    val eventsTodayError: String?,
    val todos: List<TodoItem>,
    val nowPlaying: NowPlayingChip?,
    val weather: WeatherInfo?,
    val alarms: List<AlarmItem>,
    /** Cuenta atrás del próximo plan, solo presente en días-milestone. */
    val planChip: PlanChip? = null,
    /**
     * ``true`` cuando el backend reporta que el refresh_token de
     * Spotify caducó. El home pinta un chip "Renovar Spotify" para
     * que el usuario pueda relanzar el OAuth sin tener que entrar en
     * NowPlaying (donde el botón también aparece) — caso "Spotify
     * roto + sin música" en el que el chip 'Suena ahora' habría
     * desaparecido y dejaría al usuario sin acceso al reauth.
     */
    val spotifyAuthRequired: Boolean = false,
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
