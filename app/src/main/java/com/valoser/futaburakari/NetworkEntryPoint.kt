package com.valoser.futaburakari

/**
 * Hilt の EntryPoint 定義。
 *
 * Hilt によって管理されていない場所（例: `ContentProvider`、`BroadcastReceiver`、
 * ライブラリコード、静的コンテキストなど）から依存関係を取得するために使用します。
 * `EntryPointAccessors.fromApplication(appContext, NetworkEntryPoint::class.java)` のように呼び出して、
 * `SingletonComponent` に紐づく依存関係へアクセスできます。
 */

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface NetworkEntryPoint {
    /**
     * アプリ全体で共有される `NetworkClient` を取得します。
     * `SingletonComponent` のスコープで提供されるインスタンスが返されます。
     */
    fun networkClient(): NetworkClient
}
