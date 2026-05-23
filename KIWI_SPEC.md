# Kiwi — Asistente de voz personal en Pixel Tablet

## Visión del proyecto

Construir un asistente de voz inteligente y personalizado que funcione como un electrodoméstico siempre encendido en el salón, usando una Google Pixel Tablet con base de carga como hardware. El asistente escucha una palabra de activación ("Hey Jarvis"), envía el audio a un backend propio en Cloud Run que conecta con la Gemini Live API, y devuelve la respuesta por voz (altavoz de la base) y por texto (pantalla).

La tablet funciona en modo kiosk: no se puede salir de la app, no aparecen notificaciones, y el brillo se ajusta automáticamente según la luz ambiente. Las actualizaciones se instalan automáticamente sin intervención del usuario.

### Principios de diseño

- **Electrodoméstico**: la tablet es un dispositivo dedicado, no una tablet Android normal. Enciendes, funciona. No hay launcher, no hay notificaciones, no hay distracciones.
- **El cerebro vive en la nube**: la app Android solo se encarga del hardware (micro, pantalla, altavoz). Toda la lógica, personalidad, tools e integraciones viven en Cloud Run. Esto permite iterar el comportamiento sin redesplegar la app.
- **Seguridad sin complejidad**: API key rotable para autenticar la tablet contra Cloud Run. Si alguien compromete la key, la rotas desde el backend sin tocar la tablet. La API key de Gemini nunca sale de Cloud Run.
- **Actualizaciones silenciosas**: la app comprueba periódicamente si hay versión nueva, la descarga y se instala sola. Cero intervención.

---

## Arquitectura general

```
┌─────────────────────────────────────────────────────────────────┐
│                      DESARROLLO                                 │
│                                                                 │
│  Claude Code (móvil) → GitHub (repo privado) → GitHub Actions   │
│                                                    │         │  │
│                                              Cloud Run    APK   │
│                                              deploy    build    │
└──────────────────────────────────────────────────┼─────────┼────┘
                                                   │         │
┌──────────────────────────────────────────────────┼─────────┼────┐
│                    GOOGLE CLOUD                  │         │    │
│                                                  ▼         │    │
│  ┌──────────────────────┐    ┌──────────────┐              │    │
│  │  Cloud Run (backend) │───▶│ Gemini Live  │              │    │
│  │  - System prompt     │◀───│ API (WS)     │              │    │
│  │  - Auth (API key)    │    └──────────────┘              │    │
│  │  - Tools / config    │                                  │    │
│  │  - Versión check     │    ┌──────────────┐              │    │
│  └──────────┬───────────┘    │ Firebase App │◀─────────────┘    │
│             │                │ Distribution │              │    │
│             │                └──────┬───────┘              │    │
└─────────────┼───────────────────────┼──────────────────────┘    │
              │                       │                           │
              │ WebSocket             │ APK download               │
              │ bidireccional         │                           │
┌─────────────┼───────────────────────┼──────────────────────────┐│
│             ▼                       ▼         PIXEL TABLET     ││
│  ┌──────────────────┐    ┌──────────────────┐                  ││
│  │ Foreground Svc   │    │  Auto-updater    │                  ││
│  │ Wake word local  │    │  (polling)       │                  ││
│  │ (Porcupine)      │    └──────────────────┘                  ││
│  └────────┬─────────┘                                          ││
│           ▼                                                    ││
│  ┌──────────────────┐    ┌──────────────────┐                  ││
│  │  Compose UI      │    │  Altavoz (base)  │                  ││
│  │  Reloj/Respuesta │    │  Audio streaming │                  ││
│  └──────────────────┘    └──────────────────┘                  ││
│                                                                ││
│  Modo kiosk (Device Owner + Lock Task Mode)                    ││
│  Brillo adaptativo (sensor de luz ambiente)                    ││
└────────────────────────────────────────────────────────────────┘│
```

---

## V1 — Scope mínimo viable

La V1 valida el flujo completo de extremo a extremo. Sin features extra.

### Flujo del usuario

1. La tablet muestra un reloj con fecha y hora en pantalla completa (modo reposo).
2. El usuario dice "Hey Jarvis".
3. La pantalla muestra un indicador visual de "escuchando" (animación sutil).
4. El usuario habla su pregunta.
5. La app abre un WebSocket contra Cloud Run y envía el stream de audio.
6. Cloud Run abre otro WebSocket contra Gemini Live API y proxea el audio.
7. Gemini responde con audio + transcripción de texto.
8. Cloud Run reenvía ambos a la tablet.
9. La tablet reproduce el audio por el altavoz de la base y muestra el texto en pantalla.
10. Tras un timeout de silencio (o cuando Gemini indica fin de turno), vuelve a modo reposo.

### Lo que incluye V1

- App Android nativa (Kotlin + Jetpack Compose)
- Modo kiosk (Device Owner + Lock Task Mode)
- Pantalla de reposo (reloj + fecha)
- Wake word "Hey Jarvis" (Porcupine, on-device)
- Foreground Service para escucha permanente
- WebSocket bidireccional Tablet ↔ Cloud Run
- Backend en Cloud Run (Python + FastAPI/websockets)
- Proxy WebSocket Cloud Run ↔ Gemini Live API
- Audio streaming bidireccional
- Texto de respuesta en pantalla
- Brillo adaptativo (sensor de luz ambiente del Pixel Tablet)
- Auto-updater silencioso (polling + instalación Device Owner)
- CI/CD con GitHub Actions (build APK → Firebase App Distribution + deploy Cloud Run)

### Lo que NO incluye V1

- Spotify Connect
- Home Assistant / domótica
- Calendario / recordatorios
- Rutinas automáticas
- Reconocimiento de múltiples usuarios/voces
- Widgets personalizados
- Function calling de Gemini (tools)
- Historial de conversaciones
- Modo visual (mostrar imágenes, gráficos)

---

## Stack técnico

### App Android

| Componente | Tecnología | Versión |
|---|---|---|
| Lenguaje | Kotlin | 2.0+ |
| UI | Jetpack Compose + Material 3 | Latest stable |
| Min SDK | API 33 (Android 13) | Pixel Tablet viene con 13+ |
| Target SDK | API 35 (Android 15) | |
| Build | Gradle (Kotlin DSL) | 8.x |
| Wake word | Porcupine (Picovoice) | 4.x |
| WebSocket client | OkHttp | 4.x |
| Audio capture | Android AudioRecord | API nativa |
| Audio playback | Android AudioTrack | API nativa |
| DI | Hilt | Latest stable |
| Kiosk | DevicePolicyManager + Lock Task Mode | API nativa |

### Backend (Cloud Run)

| Componente | Tecnología |
|---|---|
| Lenguaje | Python 3.12+ |
| Framework | FastAPI con soporte WebSocket |
| WebSocket server | websockets / uvicorn |
| Gemini client | google-genai SDK (Python) |
| Autenticación | API key en header + Secret Manager |
| Container | Docker (imagen slim) |
| Región | europe-west1 (o europe-southwest1 Madrid) |

### CI/CD

| Componente | Tecnología |
|---|---|
| Repo | GitHub (privado) |
| CI | GitHub Actions |
| Build Android | Gradle en GitHub Actions |
| Distribución APK | Firebase App Distribution |
| Deploy backend | gcloud run deploy |
| Secrets | GitHub Secrets + GCP Secret Manager |

---

## Detalle de componentes

### 1. Modo kiosk (Device Owner)

El Pixel Tablet debe configurarse como dispositivo dedicado con la app como Device Owner. Esto permite:

- **Lock Task Mode**: la app se fija en primer plano. No hay botón Home, no hay Recent Apps, no hay barra de estado.
- **Instalación silenciosa de actualizaciones**: el Device Owner puede instalar APKs sin confirmación del usuario.
- **Control de pantalla**: mantener pantalla encendida, ajustar brillo programáticamente.

#### Setup inicial (una sola vez)

El Pixel Tablet debe estar en estado de fábrica (factory reset). Entonces:

```bash
# Conectar por USB y establecer Device Owner con ADB
adb shell dpm set-device-owner com.kiwi.assistant/.receiver.KiwiDeviceAdminReceiver
```

Tras este comando, la app tiene permisos de Device Owner y puede activar Lock Task Mode programáticamente.

#### Implementación

```kotlin
// DeviceAdminReceiver
class KiwiDeviceAdminReceiver : DeviceAdminReceiver() {
    companion object {
        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context, KiwiDeviceAdminReceiver::class.java)
        }
    }
}

// En MainActivity.onCreate
val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
val adminComponent = KiwiDeviceAdminReceiver.getComponentName(this)

if (dpm.isDeviceOwnerApp(packageName)) {
    // Permitir lock task para nuestro paquete
    dpm.setLockTaskPackages(adminComponent, arrayOf(packageName))

    // Ocultar status bar, navbar, etc.
    dpm.setLockTaskFeatures(adminComponent, 
        DevicePolicyManager.LOCK_TASK_FEATURE_NONE)

    // Activar lock task mode
    startLockTask()

    // Mantener pantalla encendida
    dpm.setGlobalSetting(adminComponent, 
        Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
        (BatteryManager.BATTERY_PLUGGED_AC or 
         BatteryManager.BATTERY_PLUGGED_USB or 
         BatteryManager.BATTERY_PLUGGED_WIRELESS).toString())
}
```

#### Manifest

```xml
<receiver
    android:name=".receiver.KiwiDeviceAdminReceiver"
    android:permission="android.permission.BIND_DEVICE_ADMIN"
    android:exported="true">
    <meta-data
        android:name="android.app.device_admin"
        android:resource="@xml/device_admin_receiver" />
    <intent-filter>
        <action android:name="android.app.action.DEVICE_ADMIN_ENABLED" />
    </intent-filter>
</receiver>

<activity
    android:name=".ui.MainActivity"
    android:lockTaskMode="if_whitelisted"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
        <category android:name="android.intent.category.HOME" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
</activity>
```

#### Salida de emergencia del modo kiosk

Para poder salir del modo kiosk durante el desarrollo, implementar un gesto secreto (por ejemplo, 5 toques rápidos en una esquina específica de la pantalla) que llame a `stopLockTask()` y permita acceder a los ajustes de Android.

---

### 2. Wake word — Porcupine

Porcupine de Picovoice corre 100% on-device. No envía audio a ningún servidor hasta que detecta la palabra de activación.

#### Configuración

- **Palabra de activación V1**: "Jarvis" (built-in keyword, no necesita entrenamiento personalizado)
- **Palabra de activación futuro**: entrenar una personalizada en OpenWakeWord
- **Sensibilidad**: empezar con 0.5 (rango 0.0-1.0). Subir si no detecta, bajar si hay falsos positivos.
- **AccessKey**: obtener gratis en https://console.picovoice.ai/

#### Integración en Android

```kotlin
class WakeWordService : Service() {
    private var porcupineManager: PorcupineManager? = null

    fun startListening() {
        porcupineManager = PorcupineManager.Builder()
            .setAccessKey(BuildConfig.PICOVOICE_ACCESS_KEY)
            .setKeyword(Porcupine.BuiltInKeyword.JARVIS)
            .setSensitivity(0.5f)
            .build(applicationContext) { keywordIndex ->
                // Wake word detectado
                onWakeWordDetected()
            }
        porcupineManager?.start()
    }

    private fun onWakeWordDetected() {
        // 1. Feedback visual: cambiar UI a estado "escuchando"
        // 2. Feedback sonoro: beep corto (opcional)
        // 3. Iniciar captura de audio y WebSocket a Cloud Run
    }
}
```

#### Consideraciones

- Porcupine necesita acceso permanente al micrófono → Foreground Service con notificación persistente (en modo kiosk la notificación no se ve).
- El built-in keyword "Jarvis" viene incluido en el SDK, no requiere fichero .ppn externo.
- Consumo de batería mínimo: Porcupine usa <1% CPU. En la base de carga no es problema.
- La licencia gratuita de Picovoice permite uso personal/desarrollo. Para producción comercial hay que pagar, pero para un proyecto personal está cubierto.

---

### 3. Audio pipeline

#### Captura de audio (micrófono → WebSocket)

```kotlin
class AudioCaptureManager {
    // Formato requerido por Gemini Live API
    private val sampleRate = 16000 // 16kHz
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(
        sampleRate, channelConfig, audioFormat
    )

    private var audioRecord: AudioRecord? = null

    fun startCapture(onAudioChunk: (ByteArray) -> Unit) {
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )
        audioRecord?.startRecording()

        // Thread dedicado para lectura de audio
        thread {
            val buffer = ByteArray(bufferSize)
            while (isCapturing) {
                val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (bytesRead > 0) {
                    // Codificar a base64 y enviar por WebSocket
                    val chunk = buffer.copyOf(bytesRead)
                    onAudioChunk(chunk)
                }
            }
        }
    }
}
```

#### Reproducción de audio (WebSocket → altavoz)

```kotlin
class AudioPlaybackManager {
    // Gemini Live API devuelve audio PCM 24kHz
    private val sampleRate = 24000
    private val channelConfig = AudioFormat.CHANNEL_OUT_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private var audioTrack: AudioTrack? = null

    fun init() {
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate, channelConfig, audioFormat
        )
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .setEncoding(audioFormat)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    fun playChunk(pcmData: ByteArray) {
        audioTrack?.write(pcmData, 0, pcmData.size)
        if (audioTrack?.playState != AudioTrack.PLAYSTATE_PLAYING) {
            audioTrack?.play()
        }
    }
}
```

#### Formato de audio

- **Entrada (micrófono → Gemini)**: PCM 16-bit, 16kHz, mono. Codificado en base64 para el JSON del WebSocket.
- **Salida (Gemini → altavoz)**: PCM 16-bit, 24kHz, mono. Llega en base64 dentro del JSON del WebSocket.

---

### 4. WebSocket — Tablet ↔ Cloud Run

La app Android mantiene un WebSocket contra Cloud Run durante cada interacción.

#### Protocolo de mensajes (app → Cloud Run)

```json
// Inicio de sesión (tras wake word)
{
    "type": "session.start",
    "api_key": "kwi_xxxxxxxxxxxx"
}

// Chunk de audio del micrófono
{
    "type": "audio.input",
    "data": "<base64 encoded PCM>"
}

// Fin de turno del usuario (silencio detectado o botón)
{
    "type": "audio.end"
}

// Cierre de sesión
{
    "type": "session.end"
}
```

#### Protocolo de mensajes (Cloud Run → app)

```json
// Sesión iniciada, config confirmada
{
    "type": "session.ready"
}

// Chunk de audio de respuesta
{
    "type": "audio.output",
    "data": "<base64 encoded PCM>"
}

// Transcripción del input del usuario
{
    "type": "transcript.input",
    "text": "¿Qué tiempo hace hoy?"
}

// Transcripción de la respuesta de Gemini
{
    "type": "transcript.output",
    "text": "Hoy en Madrid hace 22 grados..."
}

// Fin de la respuesta
{
    "type": "response.end"
}

// Error
{
    "type": "error",
    "message": "Gemini API unavailable"
}
```

#### Implementación en Android (OkHttp)

```kotlin
class CloudRunWebSocket(
    private val baseUrl: String,
    private val apiKey: String
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // Sin timeout para WebSocket
        .build()

    fun connect(listener: KiwiWebSocketListener): WebSocket {
        val request = Request.Builder()
            .url("$baseUrl/ws/session")
            .addHeader("X-API-Key", apiKey)
            .build()
        return client.newWebSocket(request, listener)
    }
}
```

---

### 5. Backend — Cloud Run

El backend es un servicio Python ligero que actúa como proxy inteligente entre la tablet y Gemini Live API.

#### Responsabilidades

1. **Autenticación**: validar la API key de la tablet.
2. **Proxy WebSocket**: reenviar audio entre la tablet y Gemini Live API.
3. **Configuración de Gemini**: system prompt, voz, idioma, tools.
4. **Endpoint de versión**: la app pregunta qué versión es la última para auto-actualizarse.
5. **Futuro**: function calling, historial, multi-usuario.

#### Estructura del proyecto

```
backend/
├── Dockerfile
├── requirements.txt
├── main.py                 # FastAPI app + WebSocket endpoint
├── config.py               # System prompt, modelo, configuración
├── gemini_client.py        # Conexión WebSocket con Gemini Live API
├── auth.py                 # Validación de API key
└── version.py              # Endpoint de versión para auto-update
```

#### Implementación principal

```python
# main.py
from fastapi import FastAPI, WebSocket, WebSocketDisconnect, HTTPException
from fastapi.websockets import WebSocketState
import json
import asyncio
import base64
from auth import validate_api_key
from gemini_client import GeminiLiveSession
from config import SYSTEM_PROMPT, GEMINI_MODEL, VOICE_NAME

app = FastAPI()

@app.websocket("/ws/session")
async def websocket_session(ws: WebSocket):
    await ws.accept()
    
    # Esperar mensaje de inicio con API key
    init_msg = json.loads(await ws.receive_text())
    if init_msg.get("type") != "session.start":
        await ws.close(code=4001, reason="Expected session.start")
        return
    
    if not validate_api_key(init_msg.get("api_key")):
        await ws.close(code=4003, reason="Invalid API key")
        return

    await ws.send_json({"type": "session.ready"})
    
    # Abrir sesión con Gemini Live API
    async with GeminiLiveSession(
        model=GEMINI_MODEL,
        system_prompt=SYSTEM_PROMPT,
        voice=VOICE_NAME
    ) as gemini:
        
        # Task para recibir respuestas de Gemini y reenviar a la tablet
        async def gemini_to_tablet():
            async for msg in gemini.receive():
                if msg["type"] == "audio":
                    await ws.send_json({
                        "type": "audio.output",
                        "data": msg["data"]  # base64 PCM
                    })
                elif msg["type"] == "input_transcript":
                    await ws.send_json({
                        "type": "transcript.input",
                        "text": msg["text"]
                    })
                elif msg["type"] == "output_transcript":
                    await ws.send_json({
                        "type": "transcript.output",
                        "text": msg["text"]
                    })
                elif msg["type"] == "turn_complete":
                    await ws.send_json({"type": "response.end"})

        relay_task = asyncio.create_task(gemini_to_tablet())

        try:
            # Recibir audio de la tablet y reenviar a Gemini
            while True:
                data = json.loads(await ws.receive_text())
                if data["type"] == "audio.input":
                    await gemini.send_audio(data["data"])
                elif data["type"] == "audio.end":
                    await gemini.send_end_of_turn()
                elif data["type"] == "session.end":
                    break
        except WebSocketDisconnect:
            pass
        finally:
            relay_task.cancel()


@app.get("/api/version")
async def get_version():
    """Endpoint para que la app compruebe si hay actualización."""
    return {
        "version_code": 1,
        "version_name": "1.0.0",
        "apk_url": "https://firebaseappdistribution.googleapis.com/..."
        # O una URL de GCS signed
    }
```

#### Conexión con Gemini Live API

```python
# gemini_client.py
import websockets
import json
import os

GEMINI_API_KEY = os.environ.get("GEMINI_API_KEY")
GEMINI_WS_URL = (
    "wss://generativelanguage.googleapis.com/ws/"
    "google.ai.generativelanguage.v1beta."
    "GenerativeService.BidiGenerateContent"
    f"?key={GEMINI_API_KEY}"
)

class GeminiLiveSession:
    def __init__(self, model: str, system_prompt: str, voice: str):
        self.model = model
        self.system_prompt = system_prompt
        self.voice = voice
        self.ws = None

    async def __aenter__(self):
        self.ws = await websockets.connect(GEMINI_WS_URL)
        
        # Enviar configuración de sesión
        config = {
            "setup": {
                "model": f"models/{self.model}",
                "generationConfig": {
                    "responseModalities": ["AUDIO"],
                    "speechConfig": {
                        "voiceConfig": {
                            "prebuiltVoiceConfig": {
                                "voiceName": self.voice
                            }
                        }
                    }
                },
                "systemInstruction": {
                    "parts": [{"text": self.system_prompt}]
                }
            }
        }
        await self.ws.send(json.dumps(config))
        
        # Esperar confirmación de setup
        setup_response = json.loads(await self.ws.recv())
        return self

    async def __aexit__(self, *args):
        if self.ws:
            await self.ws.close()

    async def send_audio(self, audio_base64: str):
        """Enviar chunk de audio a Gemini."""
        msg = {
            "realtimeInput": {
                "mediaChunks": [{
                    "mimeType": "audio/pcm;rate=16000",
                    "data": audio_base64
                }]
            }
        }
        await self.ws.send(json.dumps(msg))

    async def send_end_of_turn(self):
        """Indicar fin del turno del usuario."""
        msg = {
            "clientContent": {
                "turnComplete": True
            }
        }
        await self.ws.send(json.dumps(msg))

    async def receive(self):
        """Generador async de mensajes de Gemini."""
        async for message in self.ws:
            response = json.loads(message)
            
            if "serverContent" in response:
                sc = response["serverContent"]
                
                # Audio de respuesta
                if "modelTurn" in sc and "parts" in sc["modelTurn"]:
                    for part in sc["modelTurn"]["parts"]:
                        if "inlineData" in part:
                            yield {
                                "type": "audio",
                                "data": part["inlineData"]["data"]
                            }
                
                # Transcripciones
                if "inputTranscription" in sc:
                    yield {
                        "type": "input_transcript",
                        "text": sc["inputTranscription"]["text"]
                    }
                if "outputTranscription" in sc:
                    yield {
                        "type": "output_transcript",
                        "text": sc["outputTranscription"]["text"]
                    }
                
                # Fin de turno
                if sc.get("turnComplete"):
                    yield {"type": "turn_complete"}
```

#### Configuración del asistente

```python
# config.py

GEMINI_MODEL = "gemini-2.5-flash-live-native-audio"
# Alternativa más reciente: "gemini-3.1-flash-live-preview"
# Verificar el modelo más reciente en https://ai.google.dev/gemini-api/docs/models

VOICE_NAME = "Kore"  # Voz en español. Opciones: Aoede, Charon, Fenrir, Kore, Puck...
# Ver lista completa en https://ai.google.dev/gemini-api/docs/live-api

SYSTEM_PROMPT = """
Eres Kiwi, un asistente personal inteligente instalado en el salón de Alberto.
Respondes siempre en español.
Eres conciso y directo. No des explicaciones innecesarias.
Si no sabes algo, dilo claramente.
Tienes un tono amable pero no servil.
Cuando te pregunten la hora o el tiempo, usa los datos más recientes disponibles.
"""
```

#### Dockerfile

```dockerfile
FROM python:3.12-slim

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8080"]
```

#### Seguridad de la API key

- La API key de la tablet (`kwi_xxxxxxxxxxxx`) se almacena en GCP Secret Manager.
- Cloud Run la lee al arrancar como variable de entorno.
- La API key de Gemini también vive en Secret Manager, nunca en código.
- Si la API key de la tablet se compromete, se rota en Secret Manager y se redesplega Cloud Run. La tablet se queda sin servicio hasta que se actualice la key en la app (por eso en V2 conviene migrar a Firebase Auth).
- Cloud Run usa HTTPS por defecto, así que la API key viaja cifrada.

```python
# auth.py
import os
import hmac

VALID_API_KEY = os.environ.get("KIWI_API_KEY")

def validate_api_key(key: str) -> bool:
    if not key or not VALID_API_KEY:
        return False
    return hmac.compare_digest(key, VALID_API_KEY)
```

---

### 6. Auto-updater

La app comprueba periódicamente si hay una nueva versión disponible, la descarga y se instala silenciosamente gracias a los permisos de Device Owner.

#### Flujo

1. Cada 30 minutos (configurable), la app hace un GET a `https://<cloud-run-url>/api/version`.
2. Compara `version_code` remoto con el local (`BuildConfig.VERSION_CODE`).
3. Si hay nueva versión, descarga el APK desde la URL proporcionada.
4. Usa `PackageInstaller` con permisos de Device Owner para instalar silenciosamente.
5. La app se reinicia automáticamente tras la instalación.

#### Implementación

```kotlin
class AutoUpdater(
    private val context: Context,
    private val cloudRunUrl: String,
    private val apiKey: String
) {
    suspend fun checkForUpdate(): Boolean {
        val client = OkHttpClient()
        val request = Request.Builder()
            .url("$cloudRunUrl/api/version")
            .addHeader("X-API-Key", apiKey)
            .build()

        val response = client.newCall(request).execute()
        val body = JSONObject(response.body?.string() ?: return false)
        
        val remoteVersion = body.getInt("version_code")
        val currentVersion = BuildConfig.VERSION_CODE
        
        if (remoteVersion > currentVersion) {
            val apkUrl = body.getString("apk_url")
            downloadAndInstall(apkUrl)
            return true
        }
        return false
    }

    private suspend fun downloadAndInstall(apkUrl: String) {
        // Descargar APK a almacenamiento interno
        val apkFile = downloadApk(apkUrl)
        
        // Instalar silenciosamente (requiere Device Owner)
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        
        apkFile.inputStream().use { input ->
            session.openWrite("kiwi.apk", 0, -1).use { output ->
                input.copyTo(output)
                session.fsync(output)
            }
        }
        
        val intent = Intent(context, UpdateReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, intent, PendingIntent.FLAG_MUTABLE
        )
        session.commit(pendingIntent.intentSender)
    }
}
```

#### Nota sobre Firebase App Distribution

Firebase App Distribution proporciona la infraestructura para subir APKs y distribuirlos a dispositivos registrados. En el contexto de modo kiosk, la app no va a recibir notificaciones de Firebase, por eso implementamos el polling manual contra nuestro endpoint de versión. Firebase App Distribution se usa principalmente como almacén de APKs accesible vía URL, no por sus notificaciones push.

---

### 7. Brillo adaptativo

El Pixel Tablet tiene sensor de luz ambiente. La app ajusta el brillo programáticamente.

```kotlin
class BrightnessManager(private val activity: Activity) {
    private val sensorManager = activity.getSystemService(SENSOR_SERVICE) as SensorManager
    private val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val lux = event.values[0]
            val brightness = mapLuxToBrightness(lux)
            setBrightness(brightness)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun mapLuxToBrightness(lux: Float): Float {
        // 0 lux (oscuridad total) → brillo mínimo (0.01, casi apagado)
        // 500+ lux (luz de día) → brillo máximo (1.0)
        return (lux / 500f).coerceIn(0.01f, 1.0f)
    }

    private fun setBrightness(brightness: Float) {
        val layoutParams = activity.window.attributes
        layoutParams.screenBrightness = brightness
        activity.window.attributes = layoutParams
    }

    fun start() {
        sensorManager.registerListener(
            listener, lightSensor, SensorManager.SENSOR_DELAY_NORMAL
        )
    }

    fun stop() {
        sensorManager.unregisterListener(listener)
    }
}
```

---

### 8. UI — Jetpack Compose

#### Pantalla de reposo

```kotlin
@Composable
fun IdleScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Hora principal
            Text(
                text = currentTime, // "14:30"
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = 120.sp,
                    fontWeight = FontWeight.Thin
                ),
                color = Color.White.copy(alpha = 0.9f)
            )
            // Fecha
            Text(
                text = currentDate, // "Sábado, 25 de abril"
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White.copy(alpha = 0.5f)
            )
        }
    }
}
```

#### Estado de la app

```kotlin
sealed class KiwiState {
    object Idle : KiwiState()           // Reloj
    object Listening : KiwiState()      // Wake word detectado, escuchando
    object Processing : KiwiState()     // Enviando a Gemini
    data class Responding(              
        val audioPlaying: Boolean,
        val transcript: String
    ) : KiwiState()                     // Mostrando/reproduciendo respuesta
    data class Error(
        val message: String
    ) : KiwiState()                     // Error
}
```

#### Pantalla principal con estados

```kotlin
@Composable
fun KiwiScreen(state: KiwiState) {
    when (state) {
        is KiwiState.Idle -> IdleScreen()
        is KiwiState.Listening -> ListeningScreen()
        is KiwiState.Processing -> ProcessingScreen()
        is KiwiState.Responding -> RespondingScreen(
            transcript = state.transcript
        )
        is KiwiState.Error -> ErrorScreen(
            message = state.message
        )
    }
}
```

---

### 9. CI/CD — GitHub Actions

#### Workflow: Build y distribución

```yaml
# .github/workflows/deploy.yml
name: Build & Deploy

on:
  push:
    branches: [main]

jobs:
  # Job 1: Build APK y subir a Firebase App Distribution
  build-android:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Build Release APK
        working-directory: ./android
        run: ./gradlew assembleRelease
        env:
          PICOVOICE_ACCESS_KEY: ${{ secrets.PICOVOICE_ACCESS_KEY }}
          CLOUD_RUN_URL: ${{ secrets.CLOUD_RUN_URL }}
          KIWI_API_KEY: ${{ secrets.KIWI_API_KEY }}

      - name: Upload to Firebase App Distribution
        uses: wzieba/Firebase-Distribution-Github-Action@v1
        with:
          appId: ${{ secrets.FIREBASE_APP_ID }}
          serviceCredentialsFileContent: ${{ secrets.FIREBASE_CREDENTIALS }}
          groups: kiwi-device
          file: android/app/build/outputs/apk/release/app-release.apk

  # Job 2: Deploy backend a Cloud Run
  deploy-backend:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Auth GCP
        uses: google-github-actions/auth@v2
        with:
          credentials_json: ${{ secrets.GCP_SA_KEY }}

      - name: Deploy to Cloud Run
        uses: google-github-actions/deploy-cloudrun@v2
        with:
          service: kiwi-backend
          source: ./backend
          region: europe-west1
          env_vars: |
            GEMINI_API_KEY=${{ secrets.GEMINI_API_KEY }}
            KIWI_API_KEY=${{ secrets.KIWI_API_KEY }}
```

---

### 10. Estructura del repositorio

```
kiwi/
├── README.md
├── KIWI_SPEC.md              # Este documento
├── .github/
│   └── workflows/
│       └── deploy.yml          # CI/CD
│
├── android/                    # App Android
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── java/com/kiwi/assistant/
│   │   │   │   ├── KiwiApplication.kt
│   │   │   │   ├── di/                    # Hilt modules
│   │   │   │   ├── receiver/
│   │   │   │   │   └── KiwiDeviceAdminReceiver.kt
│   │   │   │   ├── service/
│   │   │   │   │   ├── WakeWordService.kt
│   │   │   │   │   └── AudioService.kt
│   │   │   │   ├── network/
│   │   │   │   │   └── CloudRunWebSocket.kt
│   │   │   │   ├── audio/
│   │   │   │   │   ├── AudioCaptureManager.kt
│   │   │   │   │   └── AudioPlaybackManager.kt
│   │   │   │   ├── updater/
│   │   │   │   │   └── AutoUpdater.kt
│   │   │   │   ├── ui/
│   │   │   │   │   ├── MainActivity.kt
│   │   │   │   │   ├── KiwiViewModel.kt
│   │   │   │   │   ├── KiwiState.kt
│   │   │   │   │   ├── screens/
│   │   │   │   │   │   ├── IdleScreen.kt
│   │   │   │   │   │   ├── ListeningScreen.kt
│   │   │   │   │   │   ├── RespondingScreen.kt
│   │   │   │   │   │   └── ErrorScreen.kt
│   │   │   │   │   └── theme/
│   │   │   │   │       └── Theme.kt
│   │   │   │   └── util/
│   │   │   │       └── BrightnessManager.kt
│   │   │   ├── res/
│   │   │   │   └── xml/
│   │   │   │       └── device_admin_receiver.xml
│   │   │   └── AndroidManifest.xml
│   │   └── build.gradle.kts
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   └── gradle.properties
│
└── backend/                    # Cloud Run backend
    ├── Dockerfile
    ├── requirements.txt
    ├── main.py
    ├── config.py
    ├── gemini_client.py
    ├── auth.py
    └── version.py
```

---

## Permisos Android necesarios

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
<uses-permission android:name="android.permission.INSTALL_PACKAGES" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```

---

## Costes estimados

### Pago único

| Concepto | Coste |
|---|---|
| Pixel Tablet + base | Ya lo tienes — 0€ |
| Picovoice (AccessKey gratuita) | 0€ |
| Firebase App Distribution | Gratuito |
| **Total** | **0€** |

### Mensual

| Concepto | Coste estimado |
|---|---|
| Gemini Live API (~30 interacciones/día) | ~6-10€/mes |
| Cloud Run (uso mínimo, escala a 0) | ~1-3€/mes |
| Firebase (distribución APK) | 0€ |
| GitHub Actions (minutos gratuitos) | 0€ |
| Electricidad (tablet 24/7 en base) | ~1-2€/mes |
| **Total** | **~8-15€/mes** |

---

## Setup inicial — Paso a paso

### 1. Preparar el entorno

```bash
# Clonar el repo
git clone https://github.com/<tu-usuario>/kiwi.git
cd kiwi

# Crear proyecto en GCP (si no existe)
gcloud projects create kiwi-assistant --name="Kiwi"
gcloud config set project kiwi-assistant

# Habilitar APIs necesarias
gcloud services enable run.googleapis.com
gcloud services enable secretmanager.googleapis.com
gcloud services enable generativelanguage.googleapis.com

# Crear secrets
echo -n "tu-gemini-api-key" | gcloud secrets create GEMINI_API_KEY --data-file=-
echo -n "kwi_$(openssl rand -hex 32)" | gcloud secrets create KIWI_API_KEY --data-file=-
```

### 2. Configurar la Pixel Tablet

```bash
# Factory reset la tablet
# Durante el setup inicial, NO configurar cuenta de Google (o hacerlo mínimo)
# Habilitar opciones de desarrollador y depuración USB

# Conectar por USB
adb devices

# Establecer Device Owner
adb shell dpm set-device-owner com.kiwi.assistant/.receiver.KiwiDeviceAdminReceiver

# Instalar la primera versión manualmente
adb install app-release.apk
```

### 3. Configurar Picovoice

1. Crear cuenta en https://console.picovoice.ai/
2. Copiar el AccessKey
3. Añadirlo como secret de GitHub (`PICOVOICE_ACCESS_KEY`)
4. El keyword "Jarvis" viene built-in, no hay que entrenar nada

### 4. Configurar Firebase App Distribution

1. Crear proyecto en Firebase Console
2. Habilitar App Distribution
3. Crear grupo de testers "kiwi-device" y registrar el email asociado a la tablet
4. Obtener credenciales de servicio y añadirlas como secret de GitHub

### 5. Primer deploy

```bash
# Deploy manual del backend
cd backend
gcloud run deploy kiwi-backend \
    --source . \
    --region europe-west1 \
    --set-secrets GEMINI_API_KEY=GEMINI_API_KEY:latest,KIWI_API_KEY=KIWI_API_KEY:latest \
    --allow-unauthenticated

# A partir de aquí, los deploys son automáticos via GitHub Actions
```

---

## Camino W — Decisión de arquitectura post-V1

Tras evaluar caminos alternativos (Home Assistant + Lovelace, Music Assistant
para unificar reproductores, MCPs como capa de integración) la dirección
elegida para todo el roadmap post-V1 es **Camino W: integraciones DIY
directas desde el backend**. Las razones:

- **Single-user, 30 años, premium en todas las plataformas relevantes.** No
  hay multi-tenant, ni perfil infantil, ni necesidad de un "hub" doméstico
  abierto. Cada decisión se puede tomar para esta persona.
- **UI estilo Echo Show, no Lovelace.** La interfaz de Home Assistant /
  Lovelace está pensada para dashboards de domótica; aquí queremos un
  asistente con escenas custom (now-playing, video, calendario) en Compose.
- **Servicios separados, no unificados.** Spotify, YouTube y Calendar se
  integran como tools independientes. Un wrapper tipo Music Assistant
  facilita un reproductor común pero quita personalización a futuro
  (carátulas Spotify, recomendaciones YouTube, etc.).
- **HA queda en reserva, sólo para domótica.** Las persianas/estores llegan
  en ~6 meses. Cuando llegue ese momento se levanta una Raspberry Pi con
  Home Assistant y se expone una sola tool (`ha_call_service`) desde el
  backend. Hasta entonces no hay HA en el sistema.

### Servicios objetivo

| Servicio | Plan | Alcance v1 de la integración |
|---|---|---|
| Spotify | Premium | Web API + Web Playback SDK en WebView para reproducir en la propia tablet, controlado por voz ("pon música de fondo"). |
| YouTube (regular) | Premium | YouTube Data API v3 para listar/buscar/Watch Later + IFrame Player API en WebView para reproducir video embebido. **No** YouTube Music: el plan es mantener Spotify para audio. |
| Google Calendar | Cuenta personal | Calendar API v3 **sólo lectura** ("¿qué tengo hoy?", "¿hay algo el viernes?"). Escritura queda fuera de scope hasta que esté pulido el read. |

### Cuotas y avisos en UI

Las APIs anteriores tienen límites diarios o mensuales (YouTube Data API:
10 000 units/día; Calendar: 1 000 000 queries/día; Spotify: 180 req/min
sliding window). El plan es:

- El backend lleva contadores por API en memoria/Firestore.
- Se exponen vía un endpoint `GET /api/quotas` que la UI Compose consulta
  cada N segundos.
- La UI muestra un icono pequeño con el % consumido y avisa cuando se cruza
  el 80 %. Si una API se queda sin cuota, el tool correspondiente devuelve
  un error claro que Gemini comunica al usuario.

### Plan por fases

Cada fase deja el sistema verificable end-to-end antes de avanzar.

| Fase | Alcance | Entregable | Estim. |
|---|---|---|---|
| **0** | Function calling base + tool dummy `get_current_time` | Gemini llama tools y el backend responde correctamente | ~2 h |
| **1** | Refactor `KiwiState` → escenas + Router Compose | Navegación entre escenas (Idle/NowPlaying/Calendar/Video/…) | ~3 h |
| **2** | Bootstrap OAuth de Google (Calendar + YouTube Data API) | Refresh token guardado en GCP Secret Manager | ~2 h |
| **3** | Tool `calendar_list_events` + escena `CalendarView` | Pregunta por agenda funciona y se ve | ~4 h |
| **4** | Tools de YouTube Data (search, Watch Later) + escena `VideoListView` | Listas y resultados navegables por voz | ~5 h |
| **5** | WebView + IFrame Player + escena `VideoPlayer` | Reproducción de YouTube embebido controlable por voz | ~5 h |
| **6** | Escena `BrowseYT` (WebView de YouTube completa) | Fallback para "abre YouTube" sin parsearlo | ~2 h |
| **7** | OAuth Spotify + Web API tools + escena `NowPlaying` | Pedir música por voz, Now Playing en pantalla | ~6 h |
| **8** | `MediaController` unificado (pause/next/skip transversal) | Comandos de medios sin discriminar Spotify/YouTube | ~3 h |
| **9** | Volumen vía `AudioManager` | "Sube/baja el volumen" sin tocar la app subyacente | ~1 h |
| **10** *(en ~6 meses)* | Home Assistant + persianas | Tool `ha_call_service`. Sólo cuando llegue el hardware. | — |

Las fases se desarrollan en orden estricto. Se puede cortar el roadmap en
cualquier punto y el sistema queda en un estado coherente: V1 sigue
funcionando, las escenas previas siguen funcionando, etc.

### Lo que queda fuera (consciente)

- Reconocimiento multi-usuario (Eagle / Speaker ID): single-user.
- Cámara como entrada visual: bonito pero ortogonal a integraciones.
- Multi-dispositivo: una sola tablet en el salón.
- Plugins / arquitectura de extensión genérica: sobreingeniería para un
  asistente personal — añadir un servicio nuevo es escribir un fichero
  `tools/<servicio>.py` y registrarlo.
- App companion en el móvil: el asistente vive en la tablet; la
  configuración se hace por SSH/Cloud Run/CLI.

---

## Referencias técnicas

- [Gemini Live API — Google AI](https://ai.google.dev/gemini-api/docs/live-api)
- [Gemini Live API — WebSocket Reference](https://ai.google.dev/api/live)
- [Gemini Live API — Get Started with WebSockets](https://ai.google.dev/gemini-api/docs/live-api/get-started-websocket)
- [Porcupine Wake Word — Android Quick Start](https://picovoice.ai/docs/quick-start/porcupine-android/)
- [Porcupine Wake Word — Android API](https://picovoice.ai/docs/api/porcupine-android/)
- [Android Lock Task Mode (Kiosk)](https://developer.android.com/work/dpc/dedicated-devices/lock-task-mode)
- [Firebase App Distribution](https://firebase.google.com/docs/app-distribution)
- [Cloud Run — Deploy](https://cloud.google.com/run/docs/deploying)
- [Google AI Studio — Probar Live API](https://aistudio.google.com/)
