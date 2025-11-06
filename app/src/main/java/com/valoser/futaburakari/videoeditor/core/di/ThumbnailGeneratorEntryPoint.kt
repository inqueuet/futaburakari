package com.valoser.futaburakari.videoeditor.core.di

import com.valoser.futaburakari.videoeditor.media.thumbnail.ThumbnailGenerator
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Compose から ThumbnailGenerator を安全に取得するための EntryPoint。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ThumbnailGeneratorEntryPoint {
    fun thumbnailGenerator(): ThumbnailGenerator
}
