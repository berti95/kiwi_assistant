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
"vale ya", o cualquier variante / matiz contextual del mismo sentido), \
llama al tool `end_conversation` y, en la misma respuesta, di una despedida \
MUY breve (1-3 palabras como "Hasta luego" o "Vale, hasta luego"). El \
sistema cerrará la conversación en cuanto termines de hablar, sin esperar \
más entrada. NO llames a `end_conversation` si el usuario está en mitad \
de algo (apuntando TODOs, pidiendo info, etc.): sólo cuando el contexto \
deja claro que terminó.

YOUTUBE — DOS TIPOS DE LISTA: distingue de dónde sale la lista de vídeos.
- Lista que mostró KIWI en su pantalla (tras `youtube_search` o \
`youtube_playlist_items`): para reproducir usa `youtube_play` con \
`position` (1, 2, 3…).
- Lista abierta en la APP NATIVA de YouTube (al usar `youtube_watch_later`, \
`youtube_open`, o cuando el usuario ya está navegando YouTube): ahí \
`youtube_play` NO sirve (no hay caché). Para "pon el tercero", "abre el de \
arriba", etc., llama primero a `ui_read_screen` para LEER los títulos que \
hay en pantalla, identifica el que pidió y luego `ui_click` con ese título. \
Igual para cualquier botón visible (Me gusta, Saltar anuncio, Suscribirse): \
`ui_click` con la etiqueta. Si dudas de qué hay en pantalla, `ui_read_screen` \
primero y no te inventes etiquetas.
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
    kiwi_state_usage_path: str = "usage.json"
    kiwi_state_plans_path: str = "plans.json"

    # Tarifas para estimar el coste en la pantalla de Uso. Calibradas
    # contra el gasto REAL observado en el billing de Gemini: con ~30
    # días de uso (1637 s audio in, 1139 s out, 235 turnos) el gasto
    # real fue ~€2.79, que el audio solo estimaba en ~€0.10 (28× corto).
    # El grueso del coste es el TEXTO que se re-envía cada turno (system
    # prompt + historial, por la rotación de sesión por turno), de ahí
    # el término por turno. Audio in/out aproximan el token-pricing del
    # modelo (~$3 / $12 por 1M tokens, 32 tok/s, 1 USD≈0.95 EUR). Si
    # Google cambia precios o cambias de modelo, recalibra vía env
    # (KIWI_COST_AUDIO_IN/OUT_EUR_PER_SECOND, KIWI_COST_PER_TURN_EUR).
    kiwi_cost_audio_in_eur_per_second: float = 0.0000912
    kiwi_cost_audio_out_eur_per_second: float = 0.0003648
    kiwi_cost_per_turn_eur: float = 0.0095

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
