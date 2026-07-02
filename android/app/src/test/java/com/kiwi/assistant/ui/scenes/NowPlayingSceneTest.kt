package com.kiwi.assistant.ui.scenes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
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
 * Uso [NowPlayingTags] para identificar los botones en vez de
 * ``contentDescription``: la merge-tree de Compose bajo Robolectric a
 * veces no expone el content description del ``Icon`` interno de un
 * ``IconButton``, y ``assertIsDisplayed`` falla si la ventana del
 * test es demasiado pequeña para que el layout entero quepa. Con
 * ``testTag`` + ``assertExists`` verificamos el contrato que
 * realmente nos importa: que el composable se instancia y los
 * callbacks se invierten correctamente.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NowPlayingSceneTest {

    @get:Rule
    val rule = createComposeRule()

    private fun baseScene(
        title: String = "Heroes",
        artist: String = "David Bowie",
        album: String = "Hunky Dory",
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
     * landscape para que ``BoxWithConstraints`` reciba maxWidth /
     * maxHeight sensatos y el layout no se desmenuce por falta de
     * constraints.
     */
    @Composable
    private fun sized(content: @Composable () -> Unit) {
        Box(modifier = Modifier.size(1280.dp, 800.dp)) { content() }
    }

    // ---- render ----

    @Test
    fun renders_title_artist_album_and_device() {
        rule.setContent { sized { NowPlayingScene(scene = baseScene()) } }
        rule.onNodeWithText("Heroes").assertExists()
        rule.onNodeWithText("David Bowie").assertExists()
        rule.onNodeWithText("Hunky Dory").assertExists()
        rule.onNodeWithText("Sonos Salón").assertExists()
    }

    @Test
    fun device_chip_falls_back_to_no_device_text_when_null() {
        rule.setContent {
            sized { NowPlayingScene(scene = baseScene(device = null)) }
        }
        rule.onNodeWithText("Sin dispositivo").assertExists()
    }

    // ---- botones de transporte (usan testTag para localizar) ----

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
        rule.onNodeWithTag(NowPlayingTags.PLAY_PAUSE).performClick()
        assert(clicks == 1)
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
        rule.onNodeWithTag(NowPlayingTags.NEXT).performClick()
        rule.onNodeWithTag(NowPlayingTags.PREVIOUS).performClick()
        assert(nextHits == 1)
        assert(prevHits == 1)
    }

    @Test
    fun shuffle_button_toggles() {
        var hits = 0
        rule.setContent {
            sized {
                NowPlayingScene(
                    scene = baseScene(shuffle = false),
                    onToggleShuffle = { hits++ },
                )
            }
        }
        rule.onNodeWithTag(NowPlayingTags.SHUFFLE).performClick()
        assert(hits == 1)
    }

    @Test
    fun repeat_button_cycles() {
        var hits = 0
        rule.setContent {
            sized {
                NowPlayingScene(
                    scene = baseScene(repeatState = "track"),
                    onCycleRepeat = { hits++ },
                )
            }
        }
        rule.onNodeWithTag(NowPlayingTags.REPEAT).performClick()
        assert(hits == 1)
    }

    @Test
    fun like_button_fires_callback() {
        var hits = 0
        rule.setContent {
            sized {
                NowPlayingScene(
                    scene = baseScene(liked = false),
                    onToggleLike = { hits++ },
                )
            }
        }
        rule.onNodeWithTag(NowPlayingTags.LIKE).performClick()
        assert(hits == 1)
    }

    // ---- banner autowake ----

    @Test
    fun autowake_banner_shows_when_status_is_set() {
        rule.setContent {
            sized {
                NowPlayingScene(
                    scene = baseScene(),
                    autoWakeStatus = "Despertando Spotify…",
                )
            }
        }
        rule.onNodeWithText("Despertando Spotify…").assertExists()
    }

    @Test
    fun autowake_banner_absent_by_default() {
        rule.setContent { sized { NowPlayingScene(scene = baseScene()) } }
        rule.onAllNodesWithText("Despertando Spotify…").assertCountEquals(0)
    }
}
