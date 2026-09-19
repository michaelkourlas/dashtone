package net.kourlas.dashtone.service.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kourlas.dashtone.service.R
import net.kourlas.dashtone.service.data.mediastore.AudioPermissionMonitor
import net.kourlas.dashtone.service.data.repository.LibraryRepository
import net.kourlas.dashtone.service.shuffle.ShuffleController
import javax.inject.Inject

@UnstableApi
@AndroidEntryPoint
class DashtoneMediaLibraryService : MediaLibraryService() {

    @Inject
    lateinit var browseTree: LibraryBrowseTree

    @Inject
    lateinit var shuffleController: ShuffleController

    @Inject
    lateinit var libraryRepository: LibraryRepository

    @Inject
    lateinit var queueStateStore: QueueStateStore

    @Inject
    lateinit var playbackResumptionProvider: PlaybackResumptionProvider

    @Inject
    lateinit var audioPermissionMonitor: AudioPermissionMonitor

    // Dispatchers.Default: verified on-device that a Main-dispatched scope deadlocks here. Legacy
    // MediaBrowserServiceCompat clients (car head units, Android Auto) reach this session through
    // Media3's compatibility bridge, which dispatches onGetLibraryRoot/onGetChildren from the main
    // thread without ever yielding it back to resume a Main-dispatched coroutine -- the callback's
    // future never completes and the browse tree stays empty.
    //
    // The exception handler earns its place separately. SupervisorJob only stops one child's
    // failure from cancelling its siblings; an uncaught exception in a launch{} whose result
    // nobody consumes still reaches the thread's default handler, which on Android kills the
    // process. Every launch below is fire-and-forget (permission monitoring, shuffle-mode sync,
    // repeat-mode restore, resumption seeding), so degrading one quietly beats taking the app
    // down with it.
    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { context, throwable ->
                Log.e("Dashtone", "Unhandled exception in $context", throwable)
            },
    )
    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaLibrarySession: MediaLibrarySession? = null
    private var libraryChangeObserver: LibraryChangeObserver? = null

    // The bare ExoPlayer underneath QueueShufflePlayer, kept so PlaybackErrorRecoveryListener can
    // reach ExoPlayer's own seek directly -- see its comment for why recovery skips take this route
    // while every other caller goes through the wrapper.
    private var exoPlayer: ExoPlayer? = null

    // Referenced by QueuePersistingListener (needs naturalOrder/isShuffled/currentGrouping
    // alongside the player's own live queue) and by the onShuffleChanged callback below -- a class
    // field, not a local val, for the same reason mediaLibrarySession is: both inner classes and
    // this callback need to reach it after onCreate() has returned.
    private lateinit var queueShuffleState: QueueShuffleState

    // Reached when the OS starts this service directly (the head unit's "resume last media source"
    // flow on ignition, say); a plain bindService() connection, which is how car.media browses the
    // library, goes straight to onCreate(). Android then requires startForeground() within a few
    // seconds whether or not anything ends up playing, and a service that misses the deadline is
    // killed with ForegroundServiceDidNotStartInTimeException -- a system_server kill delivered
    // outside the normal uncaught-exception path, with no crash dialog and no ANR to explain it.
    //
    // Media3's own handling covers this only once there is playback to show a notification for,
    // which needs something to resume (see PlaybackResumptionProvider.resumptionState) -- an empty
    // library or a fresh install offers it nothing. Calling startForeground() here, before any of
    // onCreate()'s async work, meets the deadline however the rest of startup resolves; Media3
    // supersedes the placeholder below the moment real playback state exists.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForegroundStarted()
        return super.onStartCommand(intent, flags, startId)
    }

    private fun ensureForegroundStarted() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(STARTUP_NOTIFICATION_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    STARTUP_NOTIFICATION_CHANNEL_ID,
                    getString(R.string.app_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val notification = NotificationCompat.Builder(this, STARTUP_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setSmallIcon(R.drawable.ic_category_songs)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this,
            STARTUP_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
    }

    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()
        val exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        this.exoPlayer = exoPlayer
        // The real ExoPlayer's shuffle flag is never touched (see QueueShufflePlayer), so a
        // shuffle-state change never fires EVENT_SHUFFLE_MODE_ENABLED_CHANGED on its own --
        // refresh the custom layout explicitly instead of relying on PlaybackStateSavingListener
        // for it. Wired into QueueShuffleState itself (fires on ANY change to isShuffled, not just
        // ones that go through QueueShufflePlayer's toggle) so establishing a shuffle-preset queue
        // via DashtoneLibrarySessionCallback also refreshes the icon, not only a live toggle.
        //
        // Posted through mainHandler because this callback fires from two call sites on different
        // threads: a live QueueShufflePlayer.setShuffleModeEnabled() toggle arrives on the main
        // thread, while DashtoneLibrarySessionCallback.resolveQueue() (tapping "Shuffle Albums",
        // say) runs on serviceScope, which is deliberately Dispatchers.Default (see its own
        // comment above). Touching the player or session off the main thread leaves the icon
        // stale and raises nothing.
        //
        // persistQueueState() rides along in the same post: it's also the only hook that catches a
        // shuffle toggle on a queue too small to actually reorder (QueueShufflePlayer.
        // setShuffleModeEnabled flips state.isShuffled directly without calling setMediaItems() in
        // that case, so QueuePersistingListener's EVENT_MEDIA_ITEM_TRANSITION below never fires).
        queueShuffleState = QueueShuffleState { shuffled ->
            mainHandler.post {
                mediaLibrarySession?.setCustomLayout(
                    listOf(
                        PlaybackCommandButtons.repeat(this, exoPlayer.repeatMode),
                        PlaybackCommandButtons.shuffle(this, shuffled),
                    ),
                )
                persistQueueState()
            }
        }
        val player = QueueShufflePlayer(exoPlayer, queueShuffleState)
        player.addListener(QueuePersistingListener())
        player.addListener(PlaybackErrorRecoveryListener())

        val callback = DashtoneLibrarySessionCallback(
            browseTree,
            shuffleController,
            playbackResumptionProvider,
            queueShuffleState,
            audioPermissionMonitor,
            serviceScope,
        )
        val session = MediaLibrarySession.Builder(this, player, callback)
            // Repeat and shuffle aren't part of the "required actions" a car host renders from the
            // standard Player commands alone -- see PlaybackCommandButtons.
            .setCustomLayout(
                listOf(
                    PlaybackCommandButtons.repeat(this, Player.REPEAT_MODE_OFF),
                    PlaybackCommandButtons.shuffle(this, isShuffled = false),
                ),
            )
            .build()
        mediaLibrarySession = session

        // AAOS reads a missing-permission error off the session's legacy PlaybackStateCompat error
        // state (STATE_ERROR), not off onGetLibraryRoot's result -- confirmed against Media3
        // 1.10.1's own MediaSessionLegacyStub source: setPlaybackException() is the one public
        // entry point that threads that state through to a legacy car host. This collector is what
        // makes reporting that state work for an app with no launcher UI of its own inside its
        // media session: the StateFlow immediately replays its current value to a new collector, so
        // the very first service creation (which IS the car's first connection, i.e. "first launch"
        // in an AAOS sense) evaluates permission state without needing a separate one-time trigger.
        // It also reflects live state for as long as permission stays missing, rather than firing
        // once: it clears the moment SettingsFragment's own permission row calls
        // audioPermissionMonitor.refresh() after a grant.
        serviceScope.launch {
            audioPermissionMonitor.isGranted.collect { granted -> updatePermissionState(session, granted) }
        }

        // Keeps queueShuffleState.categoryShuffleMode current so the generic shuffle toggle (see
        // QueueShufflePlayer.setShuffleModeEnabled, which overrides a plain, non-suspend Player
        // method and so can't itself await a DataStore read) can honor the user's configured mode
        // instead of always falling back to a flat, ungrouped shuffle.
        serviceScope.launch {
            libraryRepository.categoryShuffleMode.collect { mode -> queueShuffleState.categoryShuffleMode = mode }
        }

        // Restore the last repeat mode (off/one/all): otherwise every service restart silently
        // resets it to off, regardless of what the user had set. The DB read must happen off the
        // main thread, but the player itself may only be touched on the thread it was built on.
        // Independent of the queue-seeding block below (no mediaItemCount guard): repeat mode
        // should still apply even if a real connection already raced in and established a queue of
        // its own before this launch runs.
        serviceScope.launch {
            val repeatMode = queueStateStore.load()?.repeatModeOrdinal ?: return@launch
            withContext(Dispatchers.Main) {
                player.repeatMode = repeatMode
            }
        }

        // Media3 activates the legacy session (MediaSessionCompat#setActive(true)) the instant a
        // player is attached, unconditionally and with no app-level override point -- confirmed by
        // reading MediaSessionImpl/MediaSessionLegacyStub's sources directly. That part genuinely
        // can't be changed, and means a car host's connection-handshake prepare() call can advance
        // an EMPTY player straight to Player.STATE_ENDED / legacy STATE_STOPPED with null metadata
        // -- an active-looking bar with nothing in it, e.g. on first launch before anything has
        // ever played. QueueShufflePlayer.prepare() holds that call back while the queue is empty,
        // keeping the player at STATE_IDLE and that bar away entirely -- see its own doc comment.
        // What seeding the player with the last queue here (paused, not auto-playing) adds on top,
        // for a RETURNING user, is a resumable
        // "paused on X" bar rather than an empty one -- the whole last queue, not just the one song
        // that happened to be playing (see QueueStateEntity's doc comment for why there's only ever
        // one queue to restore).
        serviceScope.launch {
            val resumption = playbackResumptionProvider.resumptionState() ?: return@launch
            withContext(Dispatchers.Main) {
                if (player.mediaItemCount == 0) {
                    if (resumption.isShuffled) {
                        queueShuffleState.setShuffled(resumption.naturalItems, resumption.grouping)
                    } else {
                        queueShuffleState.setNatural(resumption.items, resumption.grouping)
                    }
                    // Matches onPlaybackResumption's own convention (see its doc comment): resumes
                    // from the start of the current track, not a remembered mid-song position.
                    player.setMediaItems(resumption.items, resumption.startIndex, 0L)
                    player.prepare()
                }
            }
        }

        libraryChangeObserver = LibraryChangeObserver(contentResolver, serviceScope) {
            libraryRepository.rescan()
            mediaLibrarySession?.let { notifyLibraryChildrenChanged(it) }
        }.apply { start() }

        // Settings changes (Browse Tabs order/enabled state) rely on the categorySnapshot
        // mechanism below, rather than notifyChildrenChanged(ROOT/MORE_ROOT, ...) here, to keep
        // the tab strip consistent: car.media's handling of ROOT's children is a confirmed,
        // still-open upstream limitation in every Media3 host reference implementation Google
        // ships -- github.com/androidx/media/issues/644, /561, and /3253 report the identical
        // symptom against Android Auto's own host, with #3253 pinning down the exact same root
        // cause reasoned about above (only ROOT's direct children are ever cached-and-never-
        // re-subscribed; a non-root node updates live without even needing notifyChildrenChanged).
        // Calling notifyChildrenChanged here was tried and confirmed dead for its intended purpose
        // -- five separate live experiments (plain notifyChildrenChanged, reordering vs.
        // enabling/disabling down across the 3/4-tab threshold, switching sources away and back
        // via "Switch apps", and fully releasing + rebuilding the MediaLibrarySession with the
        // same player) all failed to make car.media rebuild its tab strip. No amount of app-side
        // notify-call tuning can reach across that boundary.
        //
        // What IS within reach, and is now guaranteed rather than just documented, is that the tab
        // strip and the "More" overflow list can never disagree with each other. See
        // LibraryBrowseTree's categorySnapshot: the enabled/ordered category list is snapshotted
        // once per root() call (the same call that produces whatever tab strip the host ends up
        // caching) and that exact snapshot -- not a live re-read -- is what every children() call,
        // including MORE_ROOT, is built from until the next root() call. A settings change can
        // never cause a category to appear in both the tabs and "More" at once, or vanish from
        // both, because the two views are structurally forced to describe the same settings state
        // at all times. "Changes apply the next time you open Dashtone" is therefore literally
        // true -- the next root() call is what "opening Dashtone" (a fresh host connection) means
        // -- not an unverified promise resting on Settings' hint text alone.
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaLibrarySession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaLibrarySession?.player ?: return
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        libraryChangeObserver?.stop()
        libraryChangeObserver = null
        mediaLibrarySession?.let { session ->
            session.player.release()
            session.release()
        }
        mediaLibrarySession = null
        exoPlayer = null
        serviceScope.cancel()
        super.onDestroy()
    }

    // Player calls must happen on the thread the session was built on (main) -- same constraint
    // documented on the repeat-mode restore above, just for setPlaybackException instead of a
    // direct player property.
    //
    // The not-granted branch reports a plain PlaybackException carrying just the message string
    // and error code. Attaching ERROR_RESOLUTION_ACTION_LABEL/ACTION_INTENT extras as well would
    // offer a car host a "Grant Access" button to hand control back through, but on-device
    // evidence favours the simpler form: a real vehicle with permission denied showed no
    // permission dialog at all, while Dashtone's own diagnostics confirmed its side of that same
    // connection completed cleanly through onGetChildren(root) and beyond. Rendering those extras
    // is entirely up to the host's own error-card code, where a bare exception is a far more
    // exercised corner of the same API. Settings reaches the permission reliably either way (see
    // SettingsActivity's manifest entry), through the car's native "app settings" affordance and
    // independent of whether this session ever connects.
    private suspend fun updatePermissionState(session: MediaLibrarySession, granted: Boolean) {
        if (granted) {
            withContext(Dispatchers.Main) { session.setPlaybackException(null) }
            // SettingsFragment's permission row rescans the library itself before calling
            // audioPermissionMonitor.refresh() (see its own comment), so the library is already
            // populated by the time this collector observes the flip to granted -- the browse
            // tree just needs to be told to re-fetch it now, the same way any other library
            // change does.
            notifyLibraryChildrenChanged(session)
            return
        }
        val exception = PlaybackException(
            getString(R.string.permission_required_playback_message),
            null,
            PlaybackException.ERROR_CODE_SETUP_REQUIRED,
        )
        withContext(Dispatchers.Main) { session.setPlaybackException(exception) }
    }

    private suspend fun notifyLibraryChildrenChanged(session: MediaLibrarySession) {
        val parentIds = listOf(
            MediaIds.ALBUMS_ROOT,
            MediaIds.ARTISTS_ROOT,
            MediaIds.GENRES_ROOT,
            MediaIds.ALBUM_ARTISTS_ROOT,
            MediaIds.COMPOSERS_ROOT,
            MediaIds.DECADES_ROOT,
            MediaIds.FOLDERS_ROOT,
            MediaIds.SONGS_ROOT,
            MediaIds.PLAYLISTS_ROOT,
        )
        notifyChildrenChanged(session, parentIds)
    }

    // Gives a legacy MediaBrowserCompat host (car.media) a fresh, current row to render for a song
    // the instant playback moves off it, on the album screen and the flat Songs list -- the two
    // browsable contexts every song always belongs to -- rather than leaving whatever the host
    // rendered while the song was playing to sit until some unrelated event (a library rescan)
    // happens to refresh it. Genre/composer/decade/album artist/folder/playlist rows are cheaper
    // left alone here: refreshing every context a song could appear in would mean a repository
    // lookup and notify call per grouping on every single track transition.
    private fun refreshBrowseRowsForSong(songId: Long) {
        val session = mediaLibrarySession ?: return
        serviceScope.launch {
            val song = libraryRepository.songById(songId) ?: return@launch
            notifyChildrenChanged(session, listOf(MediaIds.album(song.albumId), MediaIds.SONGS_ROOT))
        }
    }

    private suspend fun notifyChildrenChanged(session: MediaLibrarySession, parentIds: List<String>) {
        for (parentId in parentIds) {
            val count = browseTree.childCount(parentId) ?: continue
            session.notifyChildrenChanged(parentId, count, null)
        }
    }

    // The single persistence choke point for "there's one queue" (see QueueStateEntity's doc
    // comment): reads whatever's actually loaded on the player right now -- not whatever a
    // queue-establishing call computed a moment ago -- so it's correct regardless of which of the
    // many ways the queue could have just changed (a fresh queue from any browse-tree action,
    // natural advance, skip, double-tap group-skip, or a live shuffle-toggle reorder) triggered the
    // call. Must run on the thread the player was built on (main) -- both call sites (
    // QueuePersistingListener's Player.Listener callback, and the onShuffleChanged lambda's
    // mainHandler.post above) already guarantee that.
    private fun persistQueueState() {
        val player = mediaLibrarySession?.player ?: return
        if (player.mediaItemCount == 0) return
        val songIds = (0 until player.mediaItemCount).mapNotNull { index ->
            MediaIds.songIdOrNull(player.getMediaItemAt(index).mediaId)
        }
        val naturalSongIds = queueShuffleState.naturalOrder.mapNotNull { MediaIds.songIdOrNull(it.mediaId) }
        val currentIndex = player.currentMediaItemIndex
        val isShuffled = queueShuffleState.isShuffled
        val grouping = queueShuffleState.currentGrouping
        val repeatMode = player.repeatMode
        serviceScope.launch {
            queueStateStore.save(songIds, naturalSongIds, currentIndex, isShuffled, grouping, repeatMode)
        }
    }

    private inner class QueuePersistingListener : Player.Listener {
        // Only tracked here to know, on the NEXT transition, which song playback just moved off
        // of -- see refreshBrowseRowsForSong.
        private var currentSongId: Long? = null

        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(Player.EVENT_REPEAT_MODE_CHANGED)) {
                mediaLibrarySession?.setCustomLayout(
                    listOf(
                        PlaybackCommandButtons.repeat(this@DashtoneMediaLibraryService, player.repeatMode),
                        PlaybackCommandButtons.shuffle(this@DashtoneMediaLibraryService, player.shuffleModeEnabled),
                    ),
                )
            }
            if (!events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_REPEAT_MODE_CHANGED)) {
                return
            }
            persistQueueState()

            if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                val songId = player.currentMediaItem?.mediaId?.let { MediaIds.songIdOrNull(it) }
                val previousSongId = currentSongId
                currentSongId = songId
                if (previousSongId != null && songId != null && previousSongId != songId) {
                    refreshBrowseRowsForSong(previousSongId)
                }
            }
        }
    }

    // A corrupt file, an unsupported codec, or (very plausible in a car -- someone yanks a USB
    // stick mid-drive) a source that just vanished must not leave playback dead in the water:
    // without this, ExoPlayer surfaces the error and simply stops, and the only way out is the
    // user manually picking a different track. Auto-advancing past the bad item, the same way a
    // real music player would, keeps the queue moving. onIsPlayingChanged(true) is the signal that
    // an item actually started playing -- as opposed to onMediaItemTransition, which fires the
    // moment a broken item BECOMES current, before ExoPlayer has even tried (and failed) to play
    // it -- so it's what resets the run of consecutive failures back to zero. The cap exists so a
    // queue that's gone entirely bad (e.g. the whole removable volume it lived on disappeared)
    // gives up cleanly instead of machine-gunning through every remaining item.
    private inner class PlaybackErrorRecoveryListener : Player.Listener {
        private val recovery = PlaybackErrorRecovery()

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) recovery.onPlaybackStarted()
        }

        // Drives the bare ExoPlayer, reaching its own seek directly. QueueShufflePlayer routes
        // seekToNext through DoubleTapNavigation, which pairs any two seeks arriving inside its
        // 400ms window into one category jump and consumes the second -- right for a user tapping
        // a steering-wheel button twice, wrong for the back-to-back failures a yanked USB stick
        // produces, where it would swallow the very skip that gets playback moving again.
        // QueuePersistingListener still observes the resulting transition through the wrapper, so
        // queue persistence is unaffected by taking this route.
        override fun onPlayerError(error: PlaybackException) {
            val player = exoPlayer ?: return
            when (recovery.onError(hasNextItem = player.hasNextMediaItem())) {
                PlaybackErrorRecovery.Action.SkipToNext -> {
                    player.seekToNextMediaItem()
                    player.prepare()
                }
                PlaybackErrorRecovery.Action.GiveUp -> player.stop()
            }
        }
    }

    private companion object {
        // Dedicated to the ensureForegroundStarted() placeholder specifically, kept separate from
        // whatever channel Media3's own MediaNotificationManager creates for the real "Now
        // Playing" notification -- this one only ever needs to exist long enough to satisfy
        // Android's startForeground() timing requirement, not to look polished.
        const val STARTUP_NOTIFICATION_CHANNEL_ID = "dashtone_startup"
        const val STARTUP_NOTIFICATION_ID = 1
    }
}
