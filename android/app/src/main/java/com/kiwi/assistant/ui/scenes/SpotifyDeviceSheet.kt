package com.kiwi.assistant.ui.scenes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.TabletAndroid
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kiwi.assistant.ui.SpotifyDevice
import com.kiwi.assistant.ui.theme.KiwiOpacity
import com.kiwi.assistant.ui.theme.KiwiRadii
import com.kiwi.assistant.ui.theme.KiwiSpacing
import com.kiwi.assistant.ui.theme.spotifyGreen

/**
 * Bottom sheet con la lista de dispositivos Spotify Connect. Tap en
 * uno → transfiere playback. Incluye un slider de volumen para el
 * device *activo* (cuando soporta control de volumen).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyDeviceSheet(
    devices: List<SpotifyDevice>,
    activeVolumePercent: Int?,
    onDismiss: () -> Unit,
    onPick: (SpotifyDevice) -> Unit,
    onVolumeChange: (Int) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF111111),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = KiwiSpacing.lg,
                    vertical = KiwiSpacing.md,
                ),
        ) {
            Text(
                text = "Dispositivo",
                color = Color.White.copy(alpha = 0.92f),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                modifier = Modifier.padding(bottom = KiwiSpacing.md),
            )
            if (activeVolumePercent != null) {
                VolumeRow(
                    percent = activeVolumePercent,
                    onChange = onVolumeChange,
                )
                Spacer(Modifier.height(KiwiSpacing.md))
            }
            if (devices.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = KiwiSpacing.lg),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Buscando dispositivos…",
                        color = Color.White.copy(alpha = KiwiOpacity.TEXT_SECONDARY),
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(KiwiSpacing.xs)) {
                    devices.forEach { device ->
                        DeviceRow(
                            device = device,
                            onPick = { onPick(device) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(KiwiSpacing.md))
        }
    }
}

@Composable
private fun VolumeRow(percent: Int, onChange: (Int) -> Unit) {
    var draft by remember(percent) { mutableStateOf(percent.toFloat()) }
    Column {
        Text(
            text = "Volumen · ${draft.toInt()}%",
            color = Color.White.copy(alpha = KiwiOpacity.TEXT_SECONDARY),
            style = MaterialTheme.typography.labelLarge,
        )
        Slider(
            value = draft,
            onValueChange = { draft = it },
            onValueChangeFinished = { onChange(draft.toInt()) },
            valueRange = 0f..100f,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.spotifyGreen,
                activeTrackColor = MaterialTheme.colorScheme.spotifyGreen,
                inactiveTrackColor = Color.White.copy(alpha = 0.18f),
            ),
        )
    }
}

@Composable
private fun DeviceRow(device: SpotifyDevice, onPick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KiwiRadii.sm))
            .background(
                if (device.isActive) {
                    Color.White.copy(alpha = 0.08f)
                } else {
                    Color.Transparent
                },
            )
            .clickable(onClick = onPick)
            .padding(horizontal = KiwiSpacing.md, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KiwiSpacing.md),
    ) {
        Icon(
            imageVector = iconFor(device.type),
            contentDescription = null,
            tint = if (device.isActive) {
                MaterialTheme.colorScheme.spotifyGreen
            } else {
                Color.White.copy(alpha = 0.7f)
            },
            modifier = Modifier.size(24.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.name,
                color = Color.White.copy(alpha = 0.92f),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = device.type.takeIf { it.isNotBlank() } ?: "device",
                color = Color.White.copy(alpha = KiwiOpacity.TEXT_TERTIARY),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (device.isActive) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Activo",
                tint = MaterialTheme.colorScheme.spotifyGreen,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private fun iconFor(type: String): ImageVector = when {
    type.contains("smartphone") -> Icons.Filled.PhoneAndroid
    type.contains("tablet") -> Icons.Filled.TabletAndroid
    type.contains("computer") -> Icons.Filled.Computer
    type.contains("tv") -> Icons.Filled.Tv
    else -> Icons.Filled.Speaker
}
