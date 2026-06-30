package com.kiwi.assistant.ui.scenes

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kiwi.assistant.ui.Scene
import com.kiwi.assistant.ui.SpotifyResultItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UI tests para [SpotifyResultsScene]. Verifica el render del título
 * de la sección, de cada row, y que el tap dispara el callback con el
 * item correspondiente (clave para que tocar una canción la
 * reproduzca).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SpotifyResultsSceneTest {

    @get:Rule
    val rule = createComposeRule()

    private val tracks = listOf(
        SpotifyResultItem(
            uri = "spotify:track:1",
            title = "Suburbia",
            artist = "Pet Shop Boys",
            album = "Please",
        ),
        SpotifyResultItem(
            uri = "spotify:track:2",
            title = "West End Girls",
            artist = "Pet Shop Boys",
            album = "Please",
        ),
    )

    @Test
    fun renders_scene_title_and_each_row() {
        rule.setContent {
            SpotifyResultsScene(
                scene = Scene.SpotifyResults(
                    kind = "track",
                    title = "Pet Shop Boys",
                    items = tracks,
                ),
            )
        }
        rule.onNodeWithText("Pet Shop Boys").assertIsDisplayed()
        rule.onNodeWithText("Suburbia").assertIsDisplayed()
        rule.onNodeWithText("West End Girls").assertIsDisplayed()
    }

    @Test
    fun tap_on_track_invokes_callback_with_the_item() {
        var tapped: SpotifyResultItem? = null
        rule.setContent {
            SpotifyResultsScene(
                scene = Scene.SpotifyResults(
                    kind = "track",
                    title = "Pet Shop Boys",
                    items = tracks,
                ),
                onItemTap = { tapped = it },
            )
        }
        rule.onNodeWithText("Suburbia").performClick()
        assert(tapped?.uri == "spotify:track:1")
    }

    @Test
    fun empty_results_shows_placeholder() {
        rule.setContent {
            SpotifyResultsScene(
                scene = Scene.SpotifyResults(
                    kind = "track",
                    title = "Nada",
                    items = emptyList(),
                ),
            )
        }
        rule.onNodeWithText("No hay resultados.").assertIsDisplayed()
    }

    @Test
    fun playlist_kind_shows_owner_and_count_subtitle() {
        rule.setContent {
            SpotifyResultsScene(
                scene = Scene.SpotifyResults(
                    kind = "playlist",
                    title = "Tus playlists",
                    items = listOf(
                        SpotifyResultItem(
                            uri = "spotify:playlist:abc",
                            title = "Daily Mix",
                            owner = "Spotify",
                            itemCount = 42,
                        ),
                    ),
                ),
            )
        }
        rule.onNodeWithText("Daily Mix").assertIsDisplayed()
        // Subtítulo: "Spotify · 42 pistas"
        rule.onNodeWithText("Spotify · 42 pistas").assertIsDisplayed()
    }
}
