package com.kiwi.assistant.ui.scenes

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.kiwi.assistant.log.KLog
import com.kiwi.assistant.ui.Scene

private const val TAG = "VideoPlayerScene"
private const val ASSET_HTML_PATH = "youtube/player.html"
// Match the host on the IFrame Player config inside player.html so
// postMessage origin checks succeed. Using youtube-nocookie.com
// instead of www.youtube.com works around the recurring "152-4"
// error that the player throws when the embedding origin/cookie
// context isn't to its liking inside WebViews.
private const val PLAYER_BASE_URL = "https://www.youtube-nocookie.com"

/**
 * YouTube video playback inside an Android [WebView] using the
 * official IFrame Player API.
 *
 * Why a WebView and not the YouTube Android Player SDK:
 *  - The official SDK is gated by Google Play Services and ties us
 *    to the Play APIs we don't otherwise use on this kiosk tablet.
 *  - The IFrame Player API is what every embedded YouTube player on
 *    the web uses. Stable, voice-friendly via JS bridge once we
 *    need it, and inherits the user's YouTube cookies if any.
 *
 * The HTML host (`assets/youtube/player.html`) is loaded with
 * https://www.youtube.com as the base URL so the IFrame's
 * postMessage origin checks accept it. We substitute the video ID
 * inline before handing the HTML to the WebView — no JS bridge
 * needed for the basic play case.
 *
 * Wrapped in [key] keyed on videoId so a "play another video" tool
 * call disposes the previous WebView (releasing its audio) and
 * builds a fresh one for the new id, instead of trying to re-load
 * inside the same player.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun VideoPlayerScene(scene: Scene.VideoPlayer, onExit: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        key(scene.videoId) {
            VideoPlayerWebView(videoId = scene.videoId)
        }

        // Back / exit overlay — top-left circular button. The
        // WebView eats most touches so without this the user has no
        // visible escape (only the long-press gesture, which most
        // people never discover).
        IconButton(
            onClick = onExit,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 12.dp, start = 12.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f))
                .size(44.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Salir del video",
                tint = Color.White,
            )
        }

        if (scene.title.isNotBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp, start = 80.dp, end = 24.dp)
                    .widthIn(max = 720.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = if (scene.channel.isNotBlank()) {
                        "${scene.title}  ·  ${scene.channel}"
                    } else {
                        scene.title
                    },
                    color = Color.White.copy(alpha = 0.92f),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun VideoPlayerWebView(videoId: String) {
    val context = LocalContext.current
    val html = remember(videoId) {
        loadAssetText(context, ASSET_HTML_PATH).replace("__VIDEO_ID__", videoId)
    }
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                settings.javaScriptEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.domStorageEnabled = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                setBackgroundColor(android.graphics.Color.BLACK)
                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun onReceivedError(
                        view: WebView?,
                        errorCode: Int,
                        description: String?,
                        failingUrl: String?,
                    ) {
                        KLog.w(
                            TAG,
                            "WebView error $errorCode at $failingUrl: $description",
                        )
                    }
                }
                loadDataWithBaseURL(
                    PLAYER_BASE_URL,
                    html,
                    "text/html",
                    "utf-8",
                    null,
                )
            }
        },
        onRelease = { web ->
            // Belt-and-braces teardown when this composable leaves
            // the tree. onPause kills any in-flight media decoders
            // immediately; destroy releases native resources so the
            // YouTube audio doesn't keep playing in the background
            // when we switch back to e.g. the calendar scene.
            KLog.i(TAG, "Releasing player WebView (videoId=$videoId)")
            web.onPause()
            web.loadUrl("about:blank")
            web.destroy()
        },
        modifier = Modifier.fillMaxSize(),
    )
}

private fun loadAssetText(context: android.content.Context, path: String): String {
    return context.assets.open(path).bufferedReader().use { it.readText() }
}
