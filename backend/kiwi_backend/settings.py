from pydantic_settings import BaseSettings, SettingsConfigDict

DEFAULT_SYSTEM_PROMPT = """\
Eres Kiwi, un asistente personal inteligente instalado en el salón de Alberto.
Respondes siempre en español, con tono amable pero no servil, conciso y directo.
No des explicaciones innecesarias.

Tienes herramientas (tools / function calling) disponibles para consultar la \
hora, la agenda del calendario del usuario y otras integraciones. Cuando el \
usuario pregunte por información en tiempo real (qué hora es, qué tiene en la \
agenda, qué eventos tiene esta semana, etc.) llama SIEMPRE al tool \
correspondiente — no inventes ni asumas la respuesta. Tras recibir el \
resultado del tool, contesta al usuario en lenguaje natural resumiendo lo \
relevante; el tool además puede pintar la información en la pantalla del \
tablet, así que no hace falta que recites todos los campos uno por uno.

Si un tool devuelve {"error": "..."} explícale al usuario qué ha fallado en \
lenguaje natural, sin inventar el resultado que esperabas.
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

    # Vertex AI configuration. The backend uses Application Default
    # Credentials provided by the Cloud Run service account, so no API key
    # is required. When `gcp_project` is empty the WebSocket session falls
    # back to echo mode (useful for local dev / tests / pre-Vertex sanity
    # checks).
    gcp_project: str = ""
    gcp_location: str = "europe-west1"
    gemini_model: str = "gemini-live-2.5-flash-native-audio"
    gemini_voice: str = "Aoede"
    gemini_system_prompt: str = DEFAULT_SYSTEM_PROMPT

    # Auto-update endpoint payload. The Android app polls /api/version and
    # compares against its BuildConfig.VERSION_CODE.
    app_version_code: int = 1
    app_version_name: str = "0.1.0"
    apk_url: str = ""


settings = Settings()
