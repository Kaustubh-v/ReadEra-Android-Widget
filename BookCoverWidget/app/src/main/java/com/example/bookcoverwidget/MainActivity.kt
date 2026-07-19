package com.example.bookcoverwidget

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private val pickFolder = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            // Persist permission so we can keep reading this folder after reboots
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            getSharedPreferences(UpdateWidgetWorker.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(UpdateWidgetWorker.KEY_TREE_URI, uri.toString())
                .apply()

            findViewById<TextView>(R.id.status_text).text = "Folder selected: ${uri.path}"
            schedulePeriodicWork()
            triggerImmediateUpdate()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences(UpdateWidgetWorker.PREFS_NAME, Context.MODE_PRIVATE)
        val savedUri = prefs.getString(UpdateWidgetWorker.KEY_TREE_URI, null)
        findViewById<TextView>(R.id.status_text).text =
            if (savedUri != null) "Folder already selected." else "No folder selected yet."

        findViewById<Button>(R.id.select_folder_button).setOnClickListener {
            pickFolder.launch(null)
        }

        findViewById<Button>(R.id.refresh_now_button).setOnClickListener {
            triggerImmediateUpdate()
        }

        findViewById<Button>(R.id.enable_accessibility_button).setOnClickListener {
            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun schedulePeriodicWork() {
        // 15 minutes is the minimum interval Android allows for periodic WorkManager jobs
        val request = PeriodicWorkRequestBuilder<UpdateWidgetWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "book_cover_refresh",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    private fun triggerImmediateUpdate() {
        val request = OneTimeWorkRequestBuilder<UpdateWidgetWorker>().build()
        WorkManager.getInstance(this).enqueue(request)
    }
}
