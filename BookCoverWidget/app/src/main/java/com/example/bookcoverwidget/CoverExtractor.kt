package com.example.bookcoverwidget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Xml
import androidx.documentfile.provider.DocumentFile
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * Extracts the cover image from an epub file addressed by a content Uri.
 * Epub = a zip archive. We look up META-INF/container.xml to find the
 * .opf package file, then parse the .opf manifest/metadata to find which
 * item is the cover image, then pull that entry's bytes out of the zip.
 */
object CoverExtractor {

    /** Finds the most recently modified .epub under the given tree Uri. */
    fun findMostRecentEpub(context: Context, treeUri: Uri): DocumentFile? {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        var best: DocumentFile? = null
        searchRecursively(root) { doc ->
            if (doc.isFile && doc.name?.endsWith(".epub", ignoreCase = true) == true) {
                if (best == null || doc.lastModified() > best!!.lastModified()) {
                    best = doc
                }
            }
        }
        return best
    }

    /** Finds the epub whose filename best matches one of the given candidate screen-text strings. */
    fun findEpubByTitleMatch(
        context: Context,
        treeUri: Uri,
        candidateTexts: Set<String>
    ): DocumentFile? {
        if (candidateTexts.isEmpty()) return null
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null

        val normalizedCandidates = candidateTexts.map { normalize(it) }.filter { it.length >= 4 }
        var best: DocumentFile? = null
        var bestScore = 0

        searchRecursively(root) { doc ->
            val name = doc.name
            if (doc.isFile && name?.endsWith(".epub", ignoreCase = true) == true) {
                val fileTitle = normalize(name.removeSuffix(".epub").removeSuffix(".EPUB"))
                for (candidate in normalizedCandidates) {
                    val score = when {
                        fileTitle == candidate -> 100
                        fileTitle.contains(candidate) || candidate.contains(fileTitle) -> 70
                        else -> wordOverlapScore(fileTitle, candidate)
                    }
                    if (score > bestScore) {
                        bestScore = score
                        best = doc
                    }
                }
            }
        }
        // Require a reasonably confident match to avoid false positives
        return if (bestScore >= 40) best else null
    }

    private fun normalize(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun wordOverlapScore(a: String, b: String): Int {
        val wordsA = a.split(" ").filter { it.length > 2 }.toSet()
        val wordsB = b.split(" ").filter { it.length > 2 }.toSet()
        if (wordsA.isEmpty() || wordsB.isEmpty()) return 0
        val overlap = wordsA.intersect(wordsB).size
        return (overlap * 100) / maxOf(wordsA.size, wordsB.size)
    }

    private fun searchRecursively(dir: DocumentFile, onFile: (DocumentFile) -> Unit) {
        for (child in dir.listFiles()) {
            if (child.isDirectory) {
                searchRecursively(child, onFile)
            } else {
                onFile(child)
            }
        }
    }

    /** Extracts the cover bitmap from the given epub DocumentFile, or null if not found. */
    fun extractCover(context: Context, epub: DocumentFile): Bitmap? {
        val opfPath = readZipEntryAsText(context, epub.uri, "META-INF/container.xml")
            ?.let { parseOpfPathFromContainer(it) } ?: return null

        val opfText = readZipEntryAsText(context, epub.uri, opfPath) ?: return null
        val basePath = opfPath.substringBeforeLast('/', "")
        val coverHref = parseCoverHrefFromOpf(opfText) ?: return null
        val coverPath = if (basePath.isEmpty()) coverHref else "$basePath/$coverHref"

        val bytes = readZipEntryAsBytes(context, epub.uri, coverPath) ?: return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    // ---- container.xml parsing ----
    // Looks for: <rootfile full-path="OEBPS/content.opf" .../>
    private fun parseOpfPathFromContainer(xml: String): String? {
        val parser = Xml.newPullParser()
        parser.setInput(xml.reader())
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "rootfile") {
                val path = parser.getAttributeValue(null, "full-path")
                if (path != null) return path
            }
            event = parser.next()
        }
        return null
    }

    // ---- .opf parsing ----
    // Strategy:
    // 1. Look for <meta name="cover" content="ID"/> then find manifest item id=ID -> href
    // 2. Fallback: manifest item with properties="cover-image" -> href
    // 3. Fallback: first manifest item whose media-type starts with image/
    private fun parseCoverHrefFromOpf(xml: String): String? {
        val parser = Xml.newPullParser()
        parser.setInput(xml.reader())

        var coverId: String? = null
        val idToHref = HashMap<String, String>()
        var propertiesCoverHref: String? = null
        var firstImageHref: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "meta" -> {
                        val name = parser.getAttributeValue(null, "name")
                        if (name == "cover") {
                            coverId = parser.getAttributeValue(null, "content")
                        }
                    }
                    "item" -> {
                        val id = parser.getAttributeValue(null, "id")
                        val href = parser.getAttributeValue(null, "href")
                        val mediaType = parser.getAttributeValue(null, "media-type")
                        val properties = parser.getAttributeValue(null, "properties")
                        if (id != null && href != null) {
                            idToHref[id] = href
                            if (properties != null && properties.contains("cover-image")) {
                                propertiesCoverHref = href
                            }
                            if (firstImageHref == null && mediaType?.startsWith("image/") == true) {
                                firstImageHref = href
                            }
                        }
                    }
                }
            }
            event = parser.next()
        }

        return coverId?.let { idToHref[it] } ?: propertiesCoverHref ?: firstImageHref
    }

    // ---- zip helpers (SAF content Uri -> InputStream -> ZipInputStream) ----
    private fun readZipEntryAsText(context: Context, uri: Uri, entryPath: String): String? {
        return readZipEntryAsBytes(context, uri, entryPath)?.toString(Charsets.UTF_8)
    }

    private fun readZipEntryAsBytes(context: Context, uri: Uri, entryPath: String): ByteArray? {
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name.equals(entryPath, ignoreCase = true) ||
                        entry.name.endsWith("/$entryPath", ignoreCase = true)
                    ) {
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var len = zis.read(buffer)
                        while (len > 0) {
                            out.write(buffer, 0, len)
                            len = zis.read(buffer)
                        }
                        return out.toByteArray()
                    }
                    entry = zis.nextEntry
                }
            }
        }
        return null
    }
}
