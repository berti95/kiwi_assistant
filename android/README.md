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

## Build

```bash
cd android
./gradlew assembleDebug
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
