package com.kiwi.assistant.ui.theme

import android.graphics.Bitmap
import androidx.collection.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil.ImageLoader
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Extracts the dominant color of an album cover and caches it.
 *
 * Compose-friendly: ``rememberAlbumDominantColor(url)`` devuelve un
 * ``Color`` que arranca como [Color.Black] y se actualiza cuando
 * el bitmap se ha descargado y procesado. La extracción es muy
 * barata gracias al cache LRU (32 entradas — un álbum medio reusa
 * la misma carátula durante muchas pistas).
 */

private val PALETTE_CACHE = LruCache<String, Int>(32)
private const val DEFAULT_COLOR = 0xFF101010.toInt()

@Composable
fun rememberAlbumDominantColor(url: String?): Color {
    val context = LocalContext.current
    var color by remember(url) {
        val cached = url?.let { PALETTE_CACHE.get(it) }
        mutableStateOf(Color(cached ?: DEFAULT_COLOR))
    }
    LaunchedEffect(url) {
        if (url.isNullOrBlank()) {
            color = Color(DEFAULT_COLOR)
            return@LaunchedEffect
        }
        // Si ya está en cache no descargamos: el initialValue arriba
        // ya lo lee.
        if (PALETTE_CACHE.get(url) != null) return@LaunchedEffect
        val extracted = withContext(Dispatchers.IO) {
            val loader = ImageLoader(context)
            val request = ImageRequest.Builder(context)
                .data(url)
                // Bitmap de 96x96 es suficiente para extracción —
                // descarga ínfima frente al original.
                .size(96, 96)
                .allowHardware(false)  // Palette necesita bitmap mutable
                .build()
            when (val result = loader.execute(request)) {
                is SuccessResult -> {
                    val drawable = result.drawable
                    val bitmap = (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                    if (bitmap != null) extractDominant(bitmap) else null
                }
                is ErrorResult -> null
            }
        }
        if (extracted != null) {
            PALETTE_CACHE.put(url, extracted)
            color = Color(extracted)
        }
    }
    return color
}

private fun extractDominant(bitmap: Bitmap): Int {
    val palette = Palette.from(bitmap)
        .maximumColorCount(16)
        .generate()
    // Preferencias: vibrant > muted > dominant (Palette ya hace
    // este ranking internamente, pero queremos fallback robusto).
    val candidate = palette.vibrantSwatch
        ?: palette.darkVibrantSwatch
        ?: palette.mutedSwatch
        ?: palette.darkMutedSwatch
        ?: palette.dominantSwatch
    return candidate?.rgb ?: DEFAULT_COLOR
}
