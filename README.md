# Kiwi — Asistente de voz personal en Pixel Tablet

Asistente de voz dedicado para Google Pixel Tablet en modo kiosk. Wake word
"Hey Jarvis" → backend en Cloud Run → Gemini Live API → respuesta por voz y
texto.

La especificación completa del proyecto está en [`KIWI_SPEC.md`](./KIWI_SPEC.md).

## Estructura del repositorio

```
android/   App Android (Kotlin + Jetpack Compose)
backend/   Backend en Cloud Run (Python + FastAPI + Gemini Live)
.github/   CI/CD con GitHub Actions
```

## Estado

V1 en desarrollo activo. Ver `KIWI_SPEC.md` § "V1 — Scope mínimo viable".
