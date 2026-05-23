# OAuth bootstrap (Spotify)

Script one-shot que obtiene un refresh token de Spotify y lo guarda en
GCP Secret Manager para que el backend lo use.

## Pre-requisitos

1. **Spotify Developer App** creada en
   [developer.spotify.com/dashboard](https://developer.spotify.com/dashboard):
   - Redirect URI registrada: `http://127.0.0.1:8765/callback` (exacto).
   - APIs / SDKs marcadas: Web API + Web Playback SDK.
   - Tienes a mano el **Client ID** y el **Client Secret**.
2. **gcloud CLI autenticado** con tu cuenta — el script habla con
   Secret Manager usando ADC:
   ```sh
   gcloud auth application-default login
   ```
3. **Python 3.10+**.

## Uso

```powershell
cd tools/spotify-oauth-bootstrap
pip install -r requirements.txt

# Mete client_id y client_secret en variables de entorno (no me los
# pegues en el chat — quedan solo en tu sesión local):
$env:SPOTIFY_CLIENT_ID = "7c9b44d1c4a9436eb3bff096cac4feef"
$env:SPOTIFY_CLIENT_SECRET = "el-que-vea-spotify-developer"

python bootstrap.py
```

Se abre el navegador → consent de Spotify (acepta los scopes) → vuelves
a la terminal y ves:

```
✓ refresh token stored at projects/.../secrets/kiwi-spotify-credentials/versions/1
```

El propio script imprime al final el comando para conceder a la SA del
backend acceso al secret. Cópialo y pégalo.

## Re-ejecutar

Volver a correr el script añade una **nueva versión** al mismo secret
(`versions/2`, `versions/3`, …). El backend siempre lee `:latest`. Útil
para:
- Rotar el refresh token (revoca la app en
  [tu cuenta Spotify → apps](https://www.spotify.com/account/apps/) y
  vuelve a hacer el consent).
- Añadir scopes nuevos: cambias `SCOPES` en `bootstrap.py` y vuelves a
  correrlo.

## Coste

Cero. Spotify Web API es gratis para uso personal.
