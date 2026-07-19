package com.example.bookcoverwidget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.File
import java.io.FileOutputStream

class UpdateWidgetWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val treeUriString = prefs.getString(KEY_TREE_URI, null) ?: return Result.failure()
        val treeUri = Uri.parse(treeUriString)

        val candidateTexts = prefs.getStringSet(
            BookTrackerAccessibilityService.KEY_CANDIDATE_TEXTS, emptySet()
        ) ?: emptySet()

        val epub = CoverExtractor.findEpubByTitleMatch(applicationContext, treeUri, candidateTexts)
            ?: CoverExtractor.findMostRecentEpub(applicationContext, treeUri) // fallback to old heuristic
            ?: return Result.success()

        // Avoid redoing work if this is already the book we last extracted.
        val lastPath = prefs.getString(KEY_LAST_EPUB_PATH, null)
        val currentPath = epub.uri.toString()

        val bitmap = CoverExtractor.extractCover(applicationContext, epub) ?: return Result.success()

        val coverFile = File(applicationContext.filesDir, COVER_FILE_NAME)
        FileOutputStream(coverFile).use { fos ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos)
        }

        prefs.edit()
            .putString(KEY_LAST_EPUB_PATH, currentPath)
            .putString(KEY_LAST_BOOK_TITLE, epub.name?.removeSuffix(".epub"))
            .apply()

        // Trigger widget refresh
        val appWidgetManager = AppWidgetManager.getInstance(applicationContext)
        val ids = appWidgetManager.getAppWidgetIds(
            android.content.ComponentName(applicationContext, BookCoverWidgetProvider::class.java)
        )
        BookCoverWidgetProvider.updateWidgets(applicationContext, appWidgetManager, ids)

        return Result.success()
    }

    companion object {
        const val PREFS_NAME = "book_cover_widget_prefs"
        const val KEY_TREE_URI = "tree_uri"
        const val KEY_LAST_EPUB_PATH = "last_epub_path"
        const val KEY_LAST_BOOK_TITLE = "last_book_title"
        const val COVER_FILE_NAME = "current_cover.png"
    }
}
