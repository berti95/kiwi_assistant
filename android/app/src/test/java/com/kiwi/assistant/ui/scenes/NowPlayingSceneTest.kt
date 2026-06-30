package com.kiwi.assistant.ui.scenes

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kiwi.assistant.ui.Scene
import com.kiwi.assistant.ui.SpotifyDevice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UI tests para [NowPlayingScene] sobre Robolectric — corren en JVM
 * con ``./gradlew testDebugUnitTest``, sin emulador.
 *
 * Cubren el contrato visible: que los datos de la scene se rendericen
 * correctamente y los callbacks tappables se inviertan a las funciones
 * pasadas, además del banner de auto-wake que añadimos para que el
 * usuario sepa que algo está pasando durante los reintentos de play.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NowPlayingSceneTest {

    @get:Rule
    val rule = createComposeRule()

    private fun baseScene(
        title: String = "Heroes",
        artist: String = "David Bowie",
        album: String = "Heroes",
        isPlaying: Boolean = true,
        shuffle: Boolean = false,
        repeatState: String = "off",
        liked: Boolean? = null,
        device: SpotifyDevice? = SpotifyDevice(
            id = "d1",
            name = "Sonos Salón",
            type = "speaker",
            isActive = true,
            volumePercent = 60,
            supportsVolume = true,
        ),
        durationMs: Long = 200_000L,
        progressMs: Long = 50_000L,
    ): Scene.NowPlaying = Scene.NowPlaying(
        title = title,
        artist = artist,
        album = album,
        albumArtUrl = null,
        isPlaying = isPlaying,
        durationMs = durationMs,
        progressMs = progressMs,
        trackUri = "spotify:track:abc",
        shuffle = shuffle,
        repeatState = repeatState,
        liked = liked,
        device = device,
    )

    @Test
    fun renders_title_artist_and_device() {
        rule.setContent { NowPlayingScene(scene = baseScene()) }
        rule.onNodeWithText("Heroes").assertIsDisplayed()
        rule.onNodeWithText("David Bowie").assertIsDisplayed()
        rule.onNodeWithText("Sonos Salón").assertIsDisplayed()
    }

    @Test
    fun play_pause_button_invokes_callback() {
        var clicks = 0
        rule.setContent {
            NowPlayingScene(
                scene = baseScene(isPlaying = true),
                onPlayPause = { clicks++ },
            )
        }
        // isPlaying=true → el icono es "Pausa".
        rule.onNodeWithContentDescription("Pausa").performClick()
        assert(clicks == 1)
    }

    @Test
    fun pause_state_shows_play_icon() {
        rule.setContent {
            NowPlayingScene(scene = baseScene(isPlaying = false))
        }
        rule.onNodeWithContentDescription("Play").assertIsDisplayed()
    }

    @Test
    fun next_and_previous_callbacks_fire() {
        var nextHits = 0
        var prevHits = 0
        rule.setContent {
            NowPlayingScene(
                scene = baseScene(),
                onNext = { nextHits++ },
                onPrevious = { prevHits++ },
            )
        }
        rule.onNodeWithContentDescription("Siguiente").performClick()
        rule.onNodeWithContentDescription("Anterior").performClick()
        assert(nextHits == 1)
        assert(prevHits == 1)
    }

    @Test
    fun shuffle_off_uses_dim_icon_and_toggles() {
        var hits = 0
        rule.setContent {
            NowPlayingScene(
                scene = baseScene(shuffle = false),
                onToggleShuffle = { hits++ },
            )
        }
        rule.onNodeWithContentDescription("Aleatorio").performClick()
        assert(hits == 1)
    }

    @Test
    fun repeat_track_state_uses_repeat_one_icon_and_cycles() {
        var hits = 0
        rule.setContent {
            NowPlayingScene(
                scene = baseScene(repeatState = "track"),
                onCycleRepeat = { hits++ },
            )
        }
        rule.onNodeWithContentDescription("Repetir pista").performClick()
        assert(hits == 1)
    }

    @Test
    fun like_button_reflects_liked_state() {
        rule.setContent {
            NowPlayingScene(scene = baseScene(liked = true))
        }
        rule.onNodeWithContentDescription("Quitar de favoritos")
            .assertIsDisplayed()
    }

    @Test
    fun like_button_callback_fires_when_unliked() {
        var hits = 0
        rule.setContent {
            NowPlayingScene(
                scene = baseScene(liked = false),
                onToggleLike = { hits++ },
            )
        }
        rule.onNodeWithContentDescription("Me gusta").performClick()
        assert(hits == 1)
    }

    @Test
    fun device_chip_falls_back_to_no_device_text_when_null() {
        rule.setContent {
            NowPlayingScene(scene = baseScene(device = null))
        }
        rule.onNodeWithText("Sin dispositivo").assertIsDisplayed()
    }

    @Test
    fun autowake_banner_only_shows_when_status_is_set() {
        rule.setContent {
            NowPlayingScene(
                scene = baseScene(),
                autoWakeStatus = "Despertando Spotify…",
            )
        }
        rule.onNodeWithText("Despertando Spotify…").assertIsDisplayed()
    }

    @Test
    fun autowake_banner_absent_by_default() {
        rule.setContent { NowPlayingScene(scene = baseScene()) }
        // Sin status, el banner no debe estar en la jerarquía.
        rule.onAllNodesWithText("Despertando Spotify…").assertCountEquals(0)
    }
}
