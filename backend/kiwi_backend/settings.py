from pydantic_settings import BaseSettings, SettingsConfigDict


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
    )

    # Tablet ↔ backend shared secret. Empty in local dev means auth is open
    # for testing; production deploys must always set this.
    kiwi_api_key: str = ""

    # Vertex AI configuration. The backend uses Application Default
    # Credentials provided by the Cloud Run service account, so no API key
    # is required.
    gcp_project: str = ""
    gcp_location: str = "europe-west1"
    gemini_model: str = "gemini-2.5-flash-preview-native-audio-dialog"

    # Auto-update endpoint payload. The Android app polls /api/version and
    # compares against its BuildConfig.VERSION_CODE.
    app_version_code: int = 1
    app_version_name: str = "0.1.0"
    apk_url: str = ""


settings = Settings()
