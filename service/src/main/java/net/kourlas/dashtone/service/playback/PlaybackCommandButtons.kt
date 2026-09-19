package net.kourlas.dashtone.service.playback

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import net.kourlas.dashtone.service.R

/**
 * Repeat and shuffle aren't part of the "required actions" a car host is guaranteed to render
 * from the standard Player commands alone -- they have to be explicitly published as custom
 * layout buttons (https://developer.android.com/training/cars/media/enable-playback#custom-icons).
 *
 * These are bound to custom [SessionCommand]s, not directly to Player.COMMAND_SET_REPEAT_MODE/
 * COMMAND_SET_SHUFFLE_MODE: confirmed by reading MediaSessionLegacyStub's actual translation code
 * that the legacy PlaybackStateCompat bridge -- which is what car hosts like this one read their
 * custom action buttons from -- only ever converts CommandButtons whose sessionCommand is set; a
 * button bound directly to a Player.Command is silently dropped by that
 * translation and never reaches the car host at all, regardless of how it renders for a modern
 * Media3 controller. [DashtoneLibrarySessionCallback.onCustomCommand] handles the resulting tap.
 */
@UnstableApi
internal object PlaybackCommandButtons {
    val CYCLE_REPEAT_MODE = SessionCommand("net.kourlas.dashtone.CYCLE_REPEAT_MODE", android.os.Bundle.EMPTY)
    val TOGGLE_SHUFFLE = SessionCommand("net.kourlas.dashtone.TOGGLE_SHUFFLE", android.os.Bundle.EMPTY)

    fun repeat(context: Context, currentMode: @Player.RepeatMode Int): CommandButton {
        val icon = when (currentMode) {
            Player.REPEAT_MODE_OFF -> CommandButton.ICON_REPEAT_OFF
            Player.REPEAT_MODE_ALL -> CommandButton.ICON_REPEAT_ALL
            else -> CommandButton.ICON_REPEAT_ONE
        }
        return CommandButton.Builder(icon)
            .setSessionCommand(CYCLE_REPEAT_MODE)
            .setDisplayName(context.getString(R.string.command_repeat))
            .build()
    }

    fun shuffle(context: Context, isShuffled: Boolean): CommandButton {
        val icon = if (isShuffled) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF
        return CommandButton.Builder(icon)
            .setSessionCommand(TOGGLE_SHUFFLE)
            .setDisplayName(context.getString(R.string.command_shuffle))
            .build()
    }
}
