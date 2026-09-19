package net.kourlas.dashtone.service.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import net.kourlas.dashtone.service.albumart.AlbumArtCache
import net.kourlas.dashtone.service.albumart.AlbumArtExtractor
import net.kourlas.dashtone.service.albumart.AlbumArtInvalidator
import net.kourlas.dashtone.service.albumart.AndroidAlbumArtExtractor

@Module
@InstallIn(SingletonComponent::class)
abstract class AlbumArtModule {
    @Binds
    abstract fun bindAlbumArtExtractor(impl: AndroidAlbumArtExtractor): AlbumArtExtractor

    @Binds
    abstract fun bindAlbumArtInvalidator(impl: AlbumArtCache): AlbumArtInvalidator
}
