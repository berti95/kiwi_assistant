package com.kiwi.assistant.ui.scenes

import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kiwi.assistant.ui.Scene
import kotlin.math.max
import kotlinx.coroutines.delay

/**
 * Cuenta atrás del temporizador con alarma al expirar.
 *
 * El backend sólo manda ``ends_at_ms``; el tablet hace su propio
 * tick (cada 250 ms) usando ``System.currentTimeMillis()`` para que
 * el reloj no se desincronice con el del backend (que además sólo
 * tiene segundos de precisión). Cuando llega a 0 sonamos un alarm
 * tone del sistema + vibración hasta que el usuario lo dimite.
 *
 * ``ends_at_ms == 0`` significa "no hay temporizador" — el composable
 * llama a [onDismiss] inmediatamente para volver a la home (el tool
 * timer_cancel del backend aprovecha esta convención).
 */
@Composable
fun TimerScene(scene: Scene.Timer, onDismiss: () -> Unit) {
    if (scene.endsAtMs <= 0L) {
        LaunchedEffect(scene) { onDismiss() }
        return
    }

    var nowMs by remember(scene) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(scene) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(250L)
        }
    }

    val remainingMs = max(0L, scene.endsAtMs - nowMs)
    val expired = remainingMs == 0L

    if (expired) {
        Alarm()
    }

    val tapSink = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (expired) Color(0xFF2B0F0F) else Color.Black)
            // Modal: ningún tap fuera de Cancelar/Apagar debe abrir
            // conversación con Kiwi. Mismo motivo que en
            // AlarmRingingScene.
            .clickable(
                interactionSource = tapSink,
                indication = null,
                onClick = {},
            )
            .padding(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (scene.label.isNotBlank()) {
                Text(
                    text = scene.label.replaceFirstChar { it.uppercase() },
                    color = Color.White.copy(alpha = if (expired) 0.95f else 0.65f),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.height(20.dp))
            }
            Text(
                text = if (expired) "¡Tiempo!" else formatRemaining(remainingMs),
                color = Color.White.copy(alpha = 0.95f),
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = if (expired) 140.sp else 168.sp,
                    fontWeight = FontWeight.Light,
                ),
            )
            Spacer(Modifier.height(40.dp))
            FilledTonalButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = Color.White.copy(alpha = if (expired) 0.18f else 0.12f),
                    contentColor = Color.White,
                ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 28.dp, vertical = 14.dp,
                ),
            ) {
                Text(
                    text = if (expired) "Apagar" else "Cancelar",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

private fun formatRemaining(ms: Long): String {
    val totalSeconds = (ms + 999) / 1000  // ceil so 1.2s shows as "0:02"
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val hours = minutes / 60
    val mm = minutes % 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, mm, seconds)
    } else {
        String.format("%d:%02d", mm, seconds)
    }
}

/**
 * Suena el tono de alarma del sistema y vibra mientras la composable
 * está montada. Se libera todo en [DisposableEffect.onDispose] —
 * salimos de la escena (Cancelar / Apagar / nuevo scene push) y se
 * apaga sin chillar.
 */
@Composable
private fun Alarm() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val ringtone = uri?.let { RingtoneManager.getRingtone(context, it) }
        ringtone?.audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        // Loop on Android 8+; older devices play a single shot.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ringtone?.isLooping = true
        }
        ringtone?.play()

        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Vibrator::class.java) as? Vibrator)
                ?: (context.getSystemService(VibratorManager::class.java))
                    ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? Vibrator
        }
        // Triple-pulse pattern, repeating from index 1 (skipping the
        // initial 0-ms wait) so the device buzzes the moment it lands.
        val pattern = longArrayOf(0, 400, 200, 400, 200, 400, 800)
        vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 1))

        onDispose {
            ringtone?.stop()
            vibrator?.cancel()
        }
    }
}
