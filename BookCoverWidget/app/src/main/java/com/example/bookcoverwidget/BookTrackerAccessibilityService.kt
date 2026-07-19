package com.example.bookcoverwidget

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

class BookTrackerAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName != "org.readera") return

        val root = rootInActiveWindow ?: return
        val candidates = LinkedHashSet<String>()
        collectText(root, candidates)

        if (candidates.isNotEmpty()) {
            val progress = extractProgressText(candidates)
            val prefs = getSharedPreferences(UpdateWidgetWorker.PREFS_NAME, Context.MODE_PRIVATE)
            val previous = prefs.getStringSet(KEY_CANDIDATE_TEXTS, emptySet())
            prefs.edit().apply {
                putStringSet(KEY_CANDIDATE_TEXTS, candidates)
                if (progress != null) {
                    putString(KEY_READING_PROGRESS, progress)
                }
                apply()
            }

            // Trigger a widget refresh when the detected text changes
            // (likely means a different book was opened)
            if (previous != candidates) {
                val request = OneTimeWorkRequestBuilder<UpdateWidgetWorker>().build()
                WorkManager.getInstance(this@BookTrackerAccessibilityService).enqueue(request)
            }
        }
    }

    /**
     * Scans the visible screen text for a reading-progress indicator.
     * Handles common formats ReadEra/most readers show: "42%", "Page 118 of 320",
     * "118/320", "118 of 320 pages".
     */
    private fun extractProgressText(candidates: Set<String>): String? {
        val percentRegex = Regex("""\b(\d{1,3})\s?%""")
        val pageOfRegex = Regex("""(?i)\bpage\s+(\d+)\s+of\s+(\d+)\b""")
        val slashRegex = Regex("""\b(\d+)\s*/\s*(\d+)\b""")
        val ofPagesRegex = Regex("""(?i)\b(\d+)\s+of\s+(\d+)\s+pages?\b""")
        val plainOfRegex = Regex("""(?i)\b(\d+)\s+of\s+(\d+)\b""")

        for (text in candidates) {
            pageOfRegex.find(text)?.let {
                val (current, total) = it.destructured
                return "Page $current of $total"
            }
        }
        for (text in candidates) {
            ofPagesRegex.find(text)?.let {
                val (current, total) = it.destructured
                return "$current of $total pages"
            }
        }
        for (text in candidates) {
            plainOfRegex.find(text)?.let {
                val current = it.groupValues[1].toIntOrNull()
                val total = it.groupValues[2].toIntOrNull()
                if (current != null && total != null && total > 0 && current <= total) {
                    return "$current of $total"
                }
            }
        }
        for (text in candidates) {
            percentRegex.find(text)?.let {
                val value = it.groupValues[1].toIntOrNull()
                // Guard against matching unrelated numbers like battery/volume percentages
                // that might leak into the node tree; ReadEra progress is 0-100 inclusive.
                if (value != null && value in 0..100) return "$value%"
            }
        }
        for (text in candidates) {
            slashRegex.find(text)?.let {
                val current = it.groupValues[1].toIntOrNull()
                val total = it.groupValues[2].toIntOrNull()
                if (current != null && total != null && total > 0 && current <= total) {
                    return "$current/$total"
                }
            }
        }
        return null
    }

    private fun collectText(node: AccessibilityNodeInfo, out: MutableSet<String>, depth: Int = 0) {
        if (depth > 12) return // safety guard against deep trees
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty() && text.length in 2..120) {
            out.add(text)
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectText(it, out, depth + 1) }
        }
    }

    override fun onInterrupt() {}

    companion object {
        const val KEY_CANDIDATE_TEXTS = "readera_candidate_texts"
        const val KEY_READING_PROGRESS = "reading_progress"
    }
}
