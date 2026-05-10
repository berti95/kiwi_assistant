from pydantic_settings import BaseSettings, SettingsConfigDict

DEFAULT_SYSTEM_PROMPT = """\
Eres Kiwi, un asistente personal inteligente instalado en el salón de Alberto.
Respondes siempre en español, con tono amable pero no servil, conciso y directo.
No des explicaciones innecesarias.

Tienes herramientas (tools / function calling) disponibles para consultar la \
hora, la agenda del calendario del usuario y otras integraciones. Cuando el \
usuario pregunte por información en tiempo real (qué hora es, qué tiene en la \
agenda, qué eventos tiene esta semana, etc.) llama SIEMPRE al tool \
correspondiente — no inventes ni asumas la respuesta.

REGLA INNEGOCIABLE: cada vez que llames a un tool, DEBES generar una respuesta \
hablada al usuario inmediatamente después, aunque el tool ya haya pintado la \
información en la pantalla del tablet. Nunca te quedes en silencio tras un \
tool. Resume el resultado en una o dos frases naturales en español:
- Si el tool devolvió datos, comunica los más relevantes (ej. "Hoy tienes \
reunión a las 10 y comida con Marta a las 14:30").
- Si devolvió una lista vacía, dilo claramente (ej. "Hoy no tienes nada en la \
agenda").
- Si devolvió {"error": "..."} explica el problema con tus propias palabras, \
sin inventar datos.

No recites todos los campos uno por uno: la pantalla ya enseña el detalle, tu \
voz es el resumen.

CAPTURA DE TAREAS (todo_add): cuando el usuario quiera apuntar algo, pasa al \
campo `text` lo más fielmente posible LO QUE DIJO el usuario, completo, sin \
resumir ni reformular. Si añadió motivo, contexto, plazo, descripción o \
ejemplos largos, inclúyelo TODO en el mismo `text` — la lista de tareas la \
relee Alberto luego para implementarlas, así que cuanta más información \
literal capture, mejor. No tengas miedo de TODOs largos.

DESPEDIDAS: cuando el usuario indique que la conversación termina ("nada \
más", "ya está", "gracias", "es todo", "hasta luego", "adiós", "ok kiwi", \
"vale ya", o cualquier variante con el mismo sentido), responde con una \
despedida MUY breve (1-3 palabras como "Hasta luego" o "Vale, hasta luego") \
y luego permanece en silencio sin añadir nada. El sistema cerrará la \
conversación por inactividad poco después.
"""


class Settings(BaseSettings):
    """Runtime configuration loaded from environment variables.

    On Cloud Run these come from `--set-env-vars` / `--set-secrets`. Locally
    you can drop a `.env` file at the backend root.
    """

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
        # Defensive: secrets piped in via `echo $token | gcloud secrets
        # create` on Windows PowerShell pick up a trailing CRLF. Strip
        # whitespace from all string fields so an extra newline doesn't
        # silently break auth comparisons.
        str_strip_whitespace=True,
    )

    # Tablet ↔ backend shared secret. Empty in local dev means auth is open
    # for testing; production deploys must always set this.
    kiwi_api_key: str = ""

    # Dev-only token gating GET /api/logs/recent (the in-memory recent-log
    # poll endpoint used for remote debugging). Empty (default) keeps the
    # endpoint closed — set this to a long random string in any deploy
    # where the developer wants to poll logs from outside Cloud Logging.
    dev_logs_token: str = ""

    # Gemini Live API configuration. We're on the AI Studio API rather
    # than Vertex Live because the 3.1 Flash Live preview (which
    # narrates reliably after function calls) is only published on AI
    # Studio. When `gemini_api_key` is empty the WebSocket session
    # falls back to echo mode (useful for local dev / tests). The
    # existing `gcp_project` / `gcp_location` knobs stay around — they
    # still gate other GCP-side helpers and Cloud Run sets them anyway.
    gemini_api_key: str = ""
    gcp_project: str = ""
    gcp_location: str = "europe-west1"
    gemini_model: str = "gemini-3.1-flash-live-preview"
    gemini_voice: str = "Aoede"
    gemini_system_prompt: str = DEFAULT_SYSTEM_PROMPT

    # YouTube's own "Watch Later" (system playlist WL) is unreachable
    # via Data API since 2016. We use a regular custom playlist as a
    # stand-in: when the user says "ver más tarde" / "pendientes" /
    # "videos guardados" etc., the youtube_watch_later tool reads
    # this playlist instead. Override via env var to point Kiwi at a
    # different playlist.
    youtube_watch_later_playlist_id: str = (
        "PLp9Klsh4vHGNILVgPJGsaK1JEm8BV7RIP"
    )

    # Auto-update endpoint payload. The Android app polls /api/version and
    # compares against its BuildConfig.VERSION_CODE.
    app_version_code: int = 1
    app_version_name: str = "0.1.0"
    apk_url: str = ""

    # GCS bucket holding cross-request state (TODOs, light caches…). Empty
    # in tests / local dev keeps the bucket calls behind a clear error
    # instead of silently writing somewhere unexpected.
    kiwi_state_bucket: str = ""
    kiwi_state_todos_path: str = "todos.json"
    kiwi_state_alarms_path: str = "alarms.json"
    kiwi_state_shopping_path: str = "shopping.json"

    # Lat/lon for the weather widget on the home dashboard + the
    # get_weather tool. Defaults to Madrid; override per deploy if Kiwi
    # ever lives somewhere else.
    kiwi_weather_lat: float = 40.4168
    kiwi_weather_lon: float = -3.7038

    # Substring (case-insensitive) used to identify the Pixel Tablet
    # in Spotify Connect's device list when "Kiwi pon Spotify aquí"
    # fires. Spotify name's the device after the model by default
    # (e.g. "Pixel Tablet"), so "tablet" matches out of the box. If
    # the user picks a custom name in the Spotify app, override here.
    kiwi_spotify_tablet_name: str = "tablet"


settings = Settings()
