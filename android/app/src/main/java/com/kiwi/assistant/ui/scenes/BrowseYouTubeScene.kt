package com.kiwi.assistant.ui.scenes

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.kiwi.assistant.log.KLog
import com.kiwi.assistant.ui.Scene

private const val TAG = "BrowseYouTubeScene"

/**
 * UA string we hand to the WebView so YouTube serves the regular
 * mobile-web experience (and isn't tempted to redirect us into the
 * "open in app" flow). Keeping it pinned at a real Chrome version
 * also widens the chance Google's anti-WebView heuristics let the
 * sign-in page through, though the official line is still that
 * WebView sign-in is unreliable.
 */
private const val UA_CHROME_MOBILE =
    "Mozilla/5.0 (Linux; Android 14; Pixel Tablet) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/130.0.0.0 Mobile Safari/537.36"

/**
 * Full-screen YouTube web app inside an Android [WebView].
 *
 * Shown when the user asks for "abre YouTube" or anything else that
 * doesn't have a dedicated tool (recommendations, channel pages,
 * subscriptions feed, …). Voice control inside the page is
 * impossible — the user touches the WebView directly.
 *
 * A back arrow at the top-left:
 *   • Pops the WebView's own history when there's something to go
 *     back to (links the user followed inside YouTube).
 *   • Calls [onExit] otherwise to close the scene back to the clock.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowseYouTubeScene(scene: Scene.BrowseYouTube, onExit: () -> Unit) {
    var progress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    val webViewRef = remember { mutableStateOf<WebView?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
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
                    settings.databaseEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    settings.userAgentString = UA_CHROME_MOBILE
                    setBackgroundColor(android.graphics.Color.BLACK)
                    // Persistent cookies across sessions — once the
                    // user is signed into YouTube here, we want it
                    // to stay signed in next time.
                    CookieManager.getInstance().apply {
                        setAcceptCookie(true)
                        setAcceptThirdPartyCookies(this@apply, true)
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) {
                            progress = newProgress
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: android.webkit.WebResourceRequest,
                        ): Boolean {
                            // Keep navigation inside the WebView (don't
                            // hand off to an external browser/Chrome).
                            view.loadUrl(request.url.toString())
                            return true
                        }

                        override fun doUpdateVisitedHistory(
                            view: WebView,
                            url: String?,
                            isReload: Boolean,
                        ) {
                            super.doUpdateVisitedHistory(view, url, isReload)
                            canGoBack = view.canGoBack()
                        }

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
                    webViewRef.value = this
                    loadUrl(scene.url)
                }
            },
            update = { web ->
                if (web.url != scene.url && web.originalUrl != scene.url) {
                    web.loadUrl(scene.url)
                }
            },
            onRelease = { web ->
                KLog.i(TAG, "Releasing browse WebView")
                web.onPause()
                web.loadUrl("about:blank")
                web.destroy()
                webViewRef.value = null
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Loading bar across the top — only visible mid-load.
        if (progress in 1..99) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth(),
                color = Color(0xFFFF0033),  // YouTube red
                trackColor = Color.Black.copy(alpha = 0.0f),
            )
        }

        // Back / exit overlay — top-left circular button so it
        // doesn't fight YouTube's own header for space.
        IconButton(
            onClick = {
                val web = webViewRef.value
                if (web != null && web.canGoBack()) {
                    web.goBack()
                    canGoBack = web.canGoBack()
                } else {
                    onExit()
                }
            },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 12.dp, start = 12.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f))
                .size(44.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = if (canGoBack) "Atrás" else "Salir de YouTube",
                tint = Color.White,
            )
        }
    }
}
