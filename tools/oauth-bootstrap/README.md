# OAuth bootstrap (Google Calendar + YouTube)

Script one-shot que obtiene un refresh token de Google con scopes de
Calendar (read-only) + YouTube Data (read-only) y lo guarda en GCP
Secret Manager para que el backend lo use.

## Pre-requisitos

1. **GCP Console** — proyecto `kiwi-assistant-494421` con:
   - Google Calendar API habilitada.
   - YouTube Data API v3 habilitada.
   - Secret Manager API habilitada.
   - OAuth consent screen en **Production** (User type External).
   - Un OAuth client ID de tipo **Desktop app**, JSON descargado.
2. **gcloud CLI autenticado** con tu cuenta — el script habla con
   Secret Manager usando ADC (Application Default Credentials):

   ```sh
   gcloud auth application-default login
   ```
3. **Python 3.10+**.

## Uso

```sh
cd tools/oauth-bootstrap
pip install -r requirements.txt
python bootstrap.py /ruta/a/client_secret.json
```

Se abre el navegador → consent (la primera vez verás "Google hasn't
verified this app"; **Advanced → Go to Kiwi (unsafe)**) → vuelves a la
terminal y ves:

```
✓ refresh token stored at projects/.../secrets/kiwi-google-credentials/versions/1
```

El script imprime al final los siguientes pasos manuales (binding de
IAM para que la SA del backend pueda leer el secret).

Después puedes borrar el `client_secret.json` local — el bundle ya
vive en Secret Manager.

## Re-ejecutar

Volver a correr el script añade una **nueva versión** al mismo secret
(`versions/2`, `versions/3`, …). El backend siempre lee `:latest`.
Útil para:

- Rotar el refresh token (revoca el anterior en
  https://myaccount.google.com/permissions y vuelve a hacer el consent).
- Añadir scopes nuevos: cambias `SCOPES` en `bootstrap.py` y vuelves a
  correr el script.

## Coste

Cero. Secret Manager incluye 6 versiones activas y 10 000 accesos al
mes en el tier gratuito; el backend hace 1 acceso al arrancar la
instancia y unos pocos refrescos de access token al día.
