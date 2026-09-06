package net.kourlas.dashtone.service.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.kourlas.dashtone.service.data.local.AlbumDao
import net.kourlas.dashtone.service.data.local.ArtistDao
import net.kourlas.dashtone.service.data.local.DashtoneDatabase
import net.kourlas.dashtone.service.data.local.PlaylistDao
import net.kourlas.dashtone.service.data.local.QueueStateDao
import net.kourlas.dashtone.service.data.local.SongDao
import net.kourlas.dashtone.service.data.mediastore.ContentResolverMediaStoreAudioSource
import net.kourlas.dashtone.service.data.mediastore.ContentResolverMediaStorePlaylistSource
import net.kourlas.dashtone.service.data.mediastore.LibraryScanner
import net.kourlas.dashtone.service.data.mediastore.MediaStoreAudioSource
import net.kourlas.dashtone.service.data.mediastore.MediaStoreLibraryScanner
import net.kourlas.dashtone.service.data.mediastore.MediaStorePlaylistScanner
import net.kourlas.dashtone.service.data.mediastore.MediaStorePlaylistSource
import net.kourlas.dashtone.service.data.mediastore.PlaylistScanner
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): DashtoneDatabase =
        Room.databaseBuilder(context, DashtoneDatabase::class.java, "dashtone.db")
            // Every table here is either fully re-derived from MediaStore/playlist files on the
            // next scan or is small transient state (shuffle/playback resumption) -- there's no
            // user data that a schema-bump migration would need to preserve (see
            // LibraryRepository.ensureScanned for why an empty table after a wipe is at least
            // handled gracefully rather than silently).
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun provideSongDao(database: DashtoneDatabase): SongDao = database.songDao()

    @Provides
    fun provideAlbumDao(database: DashtoneDatabase): AlbumDao = database.albumDao()

    @Provides
    fun provideArtistDao(database: DashtoneDatabase): ArtistDao = database.artistDao()

    @Provides
    fun provideQueueStateDao(database: DashtoneDatabase): QueueStateDao = database.queueStateDao()

    @Provides
    fun providePlaylistDao(database: DashtoneDatabase): PlaylistDao = database.playlistDao()

    @Provides
    @Singleton
    fun providePreferencesDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            produceFile = {
                val dir = File(context.filesDir, "datastore").apply { mkdirs() }
                File(dir, "dashtone_prefs.preferences_pb")
            },
        )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class MediaStoreModule {
    @Binds
    abstract fun bindMediaStoreAudioSource(impl: ContentResolverMediaStoreAudioSource): MediaStoreAudioSource

    @Binds
    abstract fun bindLibraryScanner(impl: MediaStoreLibraryScanner): LibraryScanner

    @Binds
    abstract fun bindMediaStorePlaylistSource(impl: ContentResolverMediaStorePlaylistSource): MediaStorePlaylistSource

    @Binds
    abstract fun bindPlaylistScanner(impl: MediaStorePlaylistScanner): PlaylistScanner
}
