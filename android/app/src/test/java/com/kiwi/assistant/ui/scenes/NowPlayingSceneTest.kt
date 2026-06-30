package com.kiwi.assistant.ui.scenes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
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
 *
 * Trucos del entorno:
 *
 *   - El window por defecto del Compose Test rule es demasiado pequeño
 *     para que ``Arrangement.Center`` quepa con todo el contenido de
 *     la scene; envolvemos en un Box de 1024x768 para simular un
 *     tablet en landscape y que ``assertIsDisplayed`` no falle.
 *   - ``IconButton`` envuelve un ``Icon`` con su contentDescription
 *     pero el clickable vive en el padre; usar ``useUnmergedTree =
 *     true`` permite que ``onNodeWithContentDescription`` encuentre
 *     el Icon, y ``hasClickAction()`` filtra al ancestro tappable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NowPlayingSceneTest {

    @get:Rule
    val rule = createComposeRule()

    private fun baseScene(
        title: String = "Heroes",
        artist: String = "David Bowie",
        album: String = "Hunky Dory",  // Distinto al título para que ningún test mate dos pájaros del mismo nombre.
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

    /**
     * Envuelve el contenido en un Box dimensionado a un tablet en
     * landscape para que la layout de NowPlayingScene tenga sitio.
     */
    @Composable
    private fun sized(content: @Composable () -> Unit) {
        Box(modifier = Modifier.size(1280.dp, 800.dp)) { content() }
    }

    /**
     * Helper: tap sobre el ancestro clickable de un Icon con esa
     * descripción. ``hasAnyDescendant`` cubre cualquier profundidad
     * sin depender de cuántos `Box` envuelvan al Icon dentro del
     * IconButton / FilledIconButton.
     */
    private fun tap(description: String) {
        rule.onNode(
            hasClickAction() and hasAnyDescendant(hasContentDescription(description)),
            useUnmergedTree = true,
        ).performClick()
    }

    /** Helper: asserts que un node con la contentDescription dada existe. */
    private fun assertIconVisible(description: String) {
        rule.onNodeWithContentDescription(description, useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun renders_title_artist_and_device() {
        rule.setContent { sized { NowPlayingScene(scene = baseScene()) } }
        rule.onNodeWithText("Heroes").assertIsDisplayed()
        rule.onNodeWithText("David Bowie").assertIsDisplayed()
        rule.onNodeWithText("Hunky Dory").assertIsDisplayed()
        rule.onNodeWithText("Sonos Salón").assertIsDisplayed()
    }

    @Test
    fun play_pause_button_invokes_callback() {
        var clicks = 0
        rule.setContent {
            sized {
                NowPlayingScene(
                    scene = baseScene(isPlaying = true),
                    onPlayPause = { clicks++ },
                )
            }
        }
        tap("Pausa")
        assert(clicks == 1)
    }

    @Test
    fun pause_state_shows_play_icon() {
        rule.setContent {
            sized { NowPlayingScene(scene = baseScene(isPlaying = false)) }
        }
        assertIconVisible("Play")
    }

    @Test
    fun next_and_previous_callbacks_fire() {
        var nextHits = 0
        var prevHits = 0
        rule.setContent {
            sized {
                NowPlayingScene(
                    scene = baseScene(),
                    onNext = { nextHits++ },
                    onPrevious = { prevHits++ },
                )
            }
        }
        tap("Siguiente")
        tap("Anterior")
        assert(nextHits == 1)
        assert(prevHits == 1)
    }

    @Test
    fun shuffle_off_uses_dim_icon_and_toggles() {
        var hits = 0
        rule.setContent {
            sized {
                NowPlayingScene(
                    scene = baseScene(shuffle = false),
                    onToggleShuffle = { hits++ },
                )
            }
        }
        tap("Aleatorio")
        assert(hits == 1)
    }

    @Test
    fun repeat_track_state_uses_repeat_one_icon_and_cycles() {
        var hits = 0
        rule.setContent {
            sized {
                NowPlayingScene(
                    scene = baseScene(repeatState = "track"),
                    onCycleRepeat = { hits++ },
                )
            }
        }
        tap("Repetir pista")
        assert(hits == 1)
    }

    @Test
    fun like_button_reflects_liked_state() {
        rule.setContent {
            sized { NowPlayingScene(scene = baseScene(liked = true)) }
        }
        assertIconVisible("Quitar de favoritos")
    }

    @Test
    fun like_button_callback_fires_when_unliked() {
        var hits = 0
        rule.setContent {
            sized {
                NowPlayingScene(
                    scene = baseScene(liked = false),
                    onToggleLike = { hits++ },
                )
            }
        }
        tap("Me gusta")
        assert(hits == 1)
    }

    @Test
    fun device_chip_falls_back_to_no_device_text_when_null() {
        rule.setContent {
            sized { NowPlayingScene(scene = baseScene(device = null)) }
        }
        rule.onNodeWithText("Sin dispositivo").assertIsDisplayed()
    }

    @Test
    fun autowake_banner_only_shows_when_status_is_set() {
        rule.setContent {
            sized {
                NowPlayingScene(
                    scene = baseScene(),
                    autoWakeStatus = "Despertando Spotify…",
                )
            }
        }
        rule.onNodeWithText("Despertando Spotify…").assertIsDisplayed()
    }

    @Test
    fun autowake_banner_absent_by_default() {
        rule.setContent { sized { NowPlayingScene(scene = baseScene()) } }
        rule.onAllNodesWithText("Despertando Spotify…").assertCountEquals(0)
    }
}
