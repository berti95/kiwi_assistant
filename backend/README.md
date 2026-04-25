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

## Deploy a Cloud Run

### Setup inicial (una sola vez)

Se provisiona todo lo que necesita Cloud Run + GitHub Actions de forma
idempotente. Necesitas tener `gcloud` autenticado contra el proyecto:

```
gcloud auth login
```

Después corres uno de los dos scripts equivalentes según tu sistema:

**macOS / Linux / WSL / Git Bash:**

```bash
export GCP_PROJECT=kiwi-assistant-494421
export GCP_REGION=europe-west1
export GH_REPO=berti95/kiwi_assistant
./backend/scripts/setup-gcp.sh
```

**Windows PowerShell:**

```powershell
$env:GCP_PROJECT = "kiwi-assistant-494421"
$env:GCP_REGION  = "europe-west1"
$env:GH_REPO     = "berti95/kiwi_assistant"
.\backend\scripts\setup-gcp.ps1
```

Si PowerShell se queja con "no se puede ejecutar scripts en este sistema":

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
```

(solo afecta a la sesión actual de PowerShell).

El script crea:

- APIs habilitadas (run, artifactregistry, secretmanager, aiplatform, etc.).
- Artifact Registry `kiwi` en `europe-west1`.
- Service account de runtime `kiwi-backend@…` con `roles/aiplatform.user` y
  `roles/secretmanager.secretAccessor`.
- Service account de deploy `gha-deploy@…` con permisos para empujar imagen
  y desplegar Cloud Run.
- Workload Identity Federation pool/provider que permite a GitHub Actions
  obtener tokens del SA de deploy sin claves JSON.
- Secret Manager `KIWI_API_KEY` con un valor aleatorio recién generado (la
  primera vez; reruns no lo tocan).

Al final imprime los dos secretos que tienes que añadir a GitHub:

| Secreto en GitHub | Valor |
|---|---|
| `WIF_PROVIDER` | `projects/.../providers/github` (que imprime el script) |
| `WIF_SERVICE_ACCOUNT` | `gha-deploy@kiwi-assistant-494421.iam.gserviceaccount.com` |

Después de añadirlos en `Settings → Secrets and variables → Actions`, cualquier
push a `main` que toque `backend/**` dispara `.github/workflows/backend-deploy.yml`
y despliega automáticamente.

### Deploy manual (para forzar uno)

`Actions → Backend Deploy → Run workflow`. El workflow hace:

1. Auth contra GCP via WIF (no hay claves en el repo).
2. Build de la imagen Docker desde `backend/`.
3. Push a `europe-west1-docker.pkg.dev/kiwi-assistant-494421/kiwi/kiwi-backend`.
4. `gcloud run deploy` con `--service-account=kiwi-backend@…`,
   `KIWI_API_KEY` montado desde Secret Manager,
   `--session-affinity` (importante para WebSockets), `--timeout=3600`.

### Rotar el API key

```bash
NEW=kwi_$(openssl rand -hex 32)
printf '%s' "$NEW" | gcloud secrets versions add KIWI_API_KEY --data-file=-
```

Y dispara `Backend Deploy` para que Cloud Run levante una nueva revisión que
lea la versión `latest`. Hasta entonces la revisión vieja sigue con la key
anterior.
