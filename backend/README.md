# Kiwi backend

Backend FastAPI que actúa como proxy WebSocket entre la tablet y la
Gemini Live API en Vertex AI. Despliegue: Google Cloud Run en
`europe-west1`, autenticando contra Vertex AI con Application Default
Credentials de la service account del servicio (sin API key de Gemini).

## Estructura

```
backend/
├── Dockerfile
├── pyproject.toml          # ruff + pytest config
├── requirements.txt        # deps de runtime
├── requirements-dev.txt    # deps de tests/lint
├── kiwi_backend/
│   ├── __init__.py
│   ├── main.py             # FastAPI app
│   └── settings.py         # pydantic-settings
└── tests/
    └── test_endpoints.py
```

## Variables de entorno

| Variable | Default | Descripción |
|---|---|---|
| `KIWI_API_KEY` | _vacío_ | Secreto compartido tablet ↔ backend (rotable). |
| `GCP_PROJECT` | _vacío_ | ID del proyecto GCP donde corre Vertex AI. |
| `GCP_LOCATION` | `europe-west1` | Región de Vertex AI. |
| `GEMINI_MODEL` | `gemini-2.5-flash-preview-native-audio-dialog` | Modelo Live API. |
| `APP_VERSION_CODE` | `1` | versionCode publicado para auto-update. |
| `APP_VERSION_NAME` | `0.1.0` | versionName publicado para auto-update. |
| `APK_URL` | _vacío_ | URL desde la que la tablet descarga el APK más reciente. |

## Desarrollo local

```bash
cd backend
python -m venv .venv && source .venv/bin/activate
pip install -r requirements-dev.txt
uvicorn kiwi_backend.main:app --reload --port 8080
curl http://localhost:8080/health
```

## Tests

```bash
cd backend
pytest
```

## Build Docker

```bash
cd backend
docker build -t kiwi-backend .
docker run --rm -p 8080:8080 -e KIWI_API_KEY=dev kiwi-backend
```
