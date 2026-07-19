package com.example.bookcoverwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.RemoteViews
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.io.File

class BookCoverWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        updateWidgets(context, appWidgetManager, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            // Manual refresh triggered by tapping the widget
            val request = OneTimeWorkRequestBuilder<UpdateWidgetWorker>().build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.example.bookcoverwidget.ACTION_REFRESH"
        const val READERA_PACKAGE = "org.readera"

        fun updateWidgets(context: Context, appWidgetManager: AppWidgetManager, ids: IntArray) {
            for (id in ids) {
                val views = RemoteViews(context.packageName, R.layout.widget_book_cover)

                val coverFile = File(context.filesDir, UpdateWidgetWorker.COVER_FILE_NAME)
                if (coverFile.exists()) {
                    val bitmap = BitmapFactory.decodeFile(coverFile.absolutePath)
                    views.setImageViewBitmap(R.id.widget_cover_image, bitmap)
                    views.setViewVisibility(R.id.widget_placeholder_text, android.view.View.GONE)
                } else {
                    views.setViewVisibility(R.id.widget_placeholder_text, android.view.View.VISIBLE)
                }

                val prefs = context.getSharedPreferences(
                    UpdateWidgetWorker.PREFS_NAME, Context.MODE_PRIVATE
                )
                val progress = prefs.getString(
                    BookTrackerAccessibilityService.KEY_READING_PROGRESS, null
                )
                views.setTextViewText(
                    R.id.widget_progress_text,
                    progress ?: "Open a book to track progress"
                )

                // Tapping the widget opens ReadEra
                val launchIntent = context.packageManager
                    .getLaunchIntentForPackage(READERA_PACKAGE)
                val pendingIntent = if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    PendingIntent.getActivity(
                        context, id, launchIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                } else {
                    // ReadEra isn't installed — fall back to opening its Play Store page
                    val marketIntent = Intent(
                        Intent.ACTION_VIEW,
                        android.net.Uri.parse("market://details?id=$READERA_PACKAGE")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    PendingIntent.getActivity(
                        context, id, marketIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                }
                views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

                appWidgetManager.updateAppWidget(id, views)
            }
        }
    }
}
