package net.kourlas.dashtone.service.playback

import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** These map directly to what the car head unit's toolbar actually renders (see
 * PlaybackCommandButtons' own doc comment on why they must be bound to a SessionCommand, not a
 * Player.Command) -- a wrong icon constant here is exactly the kind of bug that's easy to miss in
 * code review but immediately visible live, as happened with the shuffle icon earlier this
 * session. Runs under Robolectric because PlaybackCommandButtons references android.os.Bundle.EMPTY
 * at class-init time, which needs real Android framework shadows to resolve. */
@RunWith(RobolectricTestRunner::class)
class PlaybackCommandButtonsTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `repeat button icon reflects the current repeat mode`() {
        assertEquals(CommandButton.ICON_REPEAT_OFF, PlaybackCommandButtons.repeat(context, Player.REPEAT_MODE_OFF).icon)
        assertEquals(CommandButton.ICON_REPEAT_ALL, PlaybackCommandButtons.repeat(context, Player.REPEAT_MODE_ALL).icon)
        assertEquals(CommandButton.ICON_REPEAT_ONE, PlaybackCommandButtons.repeat(context, Player.REPEAT_MODE_ONE).icon)
    }

    @Test
    fun `repeat button is always bound to the cycle-repeat-mode session command`() {
        val button = PlaybackCommandButtons.repeat(context, Player.REPEAT_MODE_ALL)

        assertEquals(PlaybackCommandButtons.CYCLE_REPEAT_MODE.customAction, button.sessionCommand?.customAction)
    }

    @Test
    fun `shuffle button icon reflects shuffled state`() {
        assertEquals(CommandButton.ICON_SHUFFLE_ON, PlaybackCommandButtons.shuffle(context, isShuffled = true).icon)
        assertEquals(CommandButton.ICON_SHUFFLE_OFF, PlaybackCommandButtons.shuffle(context, isShuffled = false).icon)
    }

    @Test
    fun `shuffle button is always bound to the toggle-shuffle session command`() {
        val button = PlaybackCommandButtons.shuffle(context, isShuffled = true)

        assertEquals(PlaybackCommandButtons.TOGGLE_SHUFFLE.customAction, button.sessionCommand?.customAction)
    }
}
