package com.valoser.futaburakari

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * メタデータキャッシュ (`MetadataCache`) を提供する Hilt モジュール。
 * アプリケーションコンテキストを用いた永続キャッシュをアプリ全体で共有されるシングルトンとして公開する。
 */
@Module
@InstallIn(SingletonComponent::class)
object CacheModule {
    /**
     * SharedPreferences ベースの `MetadataCache` をアプリケーションスコープの依存として公開する。
     */
    @Provides
    @Singleton
    fun provideMetadataCache(
        @ApplicationContext context: Context,
    ): MetadataCache {
        return MetadataCache(context)
    }
}
