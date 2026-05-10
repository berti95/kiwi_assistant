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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kiwi.assistant.ui.Scene
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Pantalla cuando un despertador suena.
 *
 * Reusa el patrón de la fase "expirado" del TimerScene: tono de
 * alarma del sistema en bucle + vibración mientras la composable
 * está montada; DisposableEffect.onDispose libera ambos al salir.
 *
 * Dos botones: "Posponer 10 min" llama al endpoint snooze + vuelve
 * a home; "Apagar" llama al endpoint dismiss + vuelve a home. El
 * próximo refresh de /api/home reconcilia AlarmManager (snooze ⇒
 * re-arma con el nuevo fires_at_ms; dismiss ⇒ borra del scheduler).
 */
@Composable
fun AlarmRingingScene(
    scene: Scene.AlarmRinging,
    onDismiss: (alarmId: String) -> Unit,
    onSnooze: (alarmId: String, minutes: Int) -> Unit,
) {
    Alarm()
    val tapSink = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF2B0F0F))
            // Modal: cualquier tap fuera de los botones no debe abrir
            // conversación con Kiwi. Sin esto, un dedo torpe en el
            // borde del botón Posponer dispara también el
            // combinedClickable raíz y arranca a escuchar.
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
            Text(
                text = "¡Despertador!",
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = formatTime(scene.firesAtMs),
                color = Color.White.copy(alpha = 0.95f),
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = 168.sp,
                    fontWeight = FontWeight.Light,
                ),
            )
            if (scene.label.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = scene.label.replaceFirstChar { it.uppercase() },
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Spacer(Modifier.height(40.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                FilledTonalButton(
                    onClick = { onSnooze(scene.alarmId, 10) },
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color.White.copy(alpha = 0.12f),
                        contentColor = Color.White,
                    ),
                    contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp),
                ) {
                    Text(
                        text = "Posponer 10 min",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                FilledTonalButton(
                    onClick = { onDismiss(scene.alarmId) },
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color.White.copy(alpha = 0.20f),
                        contentColor = Color.White,
                    ),
                    contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp),
                ) {
                    Text(
                        text = "Apagar",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String =
    if (ms <= 0L) "--:--"
    else Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(TIME_FMT)

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ringtone?.isLooping = true
        }
        ringtone?.play()

        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Vibrator::class.java) as? Vibrator)
                ?: context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? Vibrator
        }
        // Patrón continuo: 600 ms vibrar / 600 ms pausa, en bucle a
        // partir del índice 0.
        val pattern = longArrayOf(0, 600, 600)
        vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))

        onDispose {
            ringtone?.stop()
            vibrator?.cancel()
        }
    }
}
