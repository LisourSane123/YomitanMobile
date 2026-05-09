package com.yomitanmobile.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.yomitanmobile.MainActivity
import com.yomitanmobile.R
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SearchWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    companion object {
        // SupervisorJob scope is reused across widget updates so the work
        // gets cancelled cleanly if the process is torn down. Previously
        // each onUpdate spun a fresh GlobalScope-style coroutine that
        // outlived the receiver.
        private val widgetScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        private fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_search)

            // Click on widget opens the app
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
            views.setOnClickPendingIntent(R.id.widget_search_bar, pendingIntent)

            // Use the app-wide singleton DAO via Hilt's EntryPoint instead
            // of building a fresh AppDatabase per update — that path opened
            // a new SQLite connection on every refresh, missed our shared
            // PRAGMA tuning, and never benefited from Room's prepared
            // statement cache (audit issue (d)).
            widgetScope.launch {
                try {
                    val entryPoint = EntryPointAccessors.fromApplication(
                        context.applicationContext,
                        WidgetEntryPoint::class.java
                    )
                    val favorite = entryPoint.favoriteWordDao().getRandomFavorite()
                    if (favorite != null) {
                        views.setViewVisibility(R.id.widget_word_section, View.VISIBLE)
                        views.setTextViewText(R.id.widget_word_expression, favorite.expression)
                        if (favorite.reading.isNotBlank() && favorite.reading != favorite.expression) {
                            views.setViewVisibility(R.id.widget_word_reading, View.VISIBLE)
                            views.setTextViewText(R.id.widget_word_reading, favorite.reading)
                        } else {
                            views.setViewVisibility(R.id.widget_word_reading, View.GONE)
                        }
                        if (favorite.definitionPreview.isNotBlank()) {
                            views.setTextViewText(R.id.widget_word_definition, favorite.definitionPreview)
                        }
                        views.setOnClickPendingIntent(R.id.widget_word_section, pendingIntent)
                    } else {
                        views.setViewVisibility(R.id.widget_word_section, View.GONE)
                    }
                    appWidgetManager.updateAppWidget(appWidgetId, views)
                } catch (_: Exception) {
                    appWidgetManager.updateAppWidget(appWidgetId, views)
                }
            }

            // Initial update without word (will be updated async)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
