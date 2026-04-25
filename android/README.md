# Kiwi — App Android

## Configuración de secretos

Las claves de runtime se inyectan en `BuildConfig` a partir de (en este orden):

1. Variables de entorno (CI usa esto).
2. `local.properties` en `android/` (desarrollo local — **no commitear**).
3. Cadena vacía como fallback, para que el proyecto compile aunque no haya
   secretos. Las llamadas al backend o al wake word fallarán en runtime con un
   error claro hasta que las pongas.

Ejemplo de `android/local.properties`:

```
CLOUD_RUN_URL=https://kiwi-backend-xxx.a.run.app
KIWI_API_KEY=kwi_xxxxxxxxxxxxxxxx
PICOVOICE_ACCESS_KEY=xxxx
```

## Versionado

`versionCode` y `versionName` se leen de variables de entorno
(`KIWI_VERSION_CODE`, `KIWI_VERSION_NAME`). En CI el workflow las setea con
el número del run de GitHub Actions, así que cada build a `main` produce un
APK con un `versionCode` monotónicamente creciente que el AutoUpdater de la
tablet usa para detectar nuevas versiones. En desarrollo local sin esas
variables, se usa `versionCode=1` y `versionName="0.1.0-dev"`.

## Pipeline de release

1. Push a `main` (o disparo manual) → workflow `Android Deploy`.
2. Build firmado con `versionCode` = `github.run_number`.
3. APK subido a `gs://kiwi-assistant-494421-apks/kiwi-vN.apk` y a `latest.apk`.
4. Cloud Run actualiza `APP_VERSION_CODE`, `APP_VERSION_NAME` y `APK_URL`.
5. La tablet polea `/api/version` cada 30 min, descarga e instala.

## Build

```bash
cd android
./gradlew assembleDebug
```

## Firma del release

`./gradlew assembleRelease` lee la firma de cuatro variables de entorno:

- `ANDROID_KEYSTORE_B64` — keystore JKS codificado en base64.
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS` (típicamente `kiwi`)
- `ANDROID_KEY_PASSWORD`

Si las cuatro están presentes, el release se firma con esa keystore. Si
falta cualquiera, el release cae al `signingConfig` de debug — útil en
local sin tener que copiar el JKS, pero esos APKs **no** sirven para
auto-actualizar la tablet.

Para generar la keystore una sola vez:

```bash
keytool -genkey -v \
    -keystore kiwi-release.jks \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -alias kiwi
```

Y para subirla a GitHub Secrets en PowerShell:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("kiwi-release.jks")) | clip
# pega el contenido del portapapeles en Settings > Secrets como ANDROID_KEYSTORE_B64
```

## Setup inicial de la tablet (modo kiosk)

Tras factory reset y con depuración USB activada:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
adb shell dpm set-device-owner \
    com.kiwi.assistant/.receiver.KiwiDeviceAdminReceiver
```

`set-device-owner` solo funciona si en la tablet no hay cuentas de Google
configuradas. Sin Device Owner la app sigue arrancando como app normal y la
activación de Lock Task se omite silenciosamente, así que el desarrollo en un
emulador o una tablet "personal" sigue siendo posible.
