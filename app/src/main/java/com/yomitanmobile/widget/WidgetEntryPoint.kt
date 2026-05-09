package com.yomitanmobile.widget

import com.yomitanmobile.data.local.dao.FavoriteWordDao
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt EntryPoint that gives [SearchWidgetProvider] access to the singleton
 * AppDatabase / DAO graph. Without this, the widget had to call
 * `Room.databaseBuilder(...).build()` on every onUpdate — opening a fresh
 * connection, missing the PRAGMA tuning we apply on the shared connection,
 * and never benefiting from Room's prepared-statement cache.
 *
 * AppWidgetProvider is a BroadcastReceiver, not an Android Component Hilt
 * can inject. EntryPointAccessors.fromApplication is the standard escape
 * hatch — it pulls the SingletonComponent off the Application object and
 * returns whatever this interface exposes.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun favoriteWordDao(): FavoriteWordDao
}
