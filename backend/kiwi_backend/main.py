from fastapi import FastAPI

from .settings import settings

app = FastAPI(title="Kiwi backend", version=settings.app_version_name)


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}


@app.get("/api/version")
async def version() -> dict[str, object]:
    return {
        "version_code": settings.app_version_code,
        "version_name": settings.app_version_name,
        "apk_url": settings.apk_url,
    }
