package com.example.hibari.bookmarks

import com.example.hibari.data.BookmarkEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * Parses Netscape Bookmark HTML format (exported by Chrome, Firefox, Edge).
 *
 * Format overview:
 *   <DL><p>
 *     <DT><H3>Folder Name</H3>
 *     <DL><p>
 *       <DT><A HREF="https://..." ADD_DATE="...">Title</A>
 *     </DL><p>
 *   </DL><p>
 *
 * We parse the token stream with a simple stack-based folder tracker.
 * No external XML library is used — the HTML is not well-formed XML,
 * so we rely on regex-based line scanning.
 */
class NetscapeHtmlImporter {

    data class ImportResult(
        val imported: Int,
        val skipped: Int,
        val errors: List<String>,
    )

    private val reAnchor = Regex("""<A\s+[^>]*HREF="([^"]+)"[^>]*>([^<]*)</A>""", RegexOption.IGNORE_CASE)
    private val reFolder = Regex("""<H3[^>]*>([^<]+)</H3>""", RegexOption.IGNORE_CASE)
    private val reDlOpen = Regex("""<DL""", RegexOption.IGNORE_CASE)
    private val reDlClose = Regex("""</DL""", RegexOption.IGNORE_CASE)

    suspend fun import(
        stream: InputStream,
        repo: BookmarkRepository,
        duplicatePolicy: DuplicatePolicy = DuplicatePolicy.SKIP,
    ): ImportResult = withContext(Dispatchers.IO) {
        val entities = mutableListOf<BookmarkEntity>()
        val errors = mutableListOf<String>()

        // folderStack: list of (folderId | null) where null = root
        val folderStack = ArrayDeque<Long?>()
        folderStack.addLast(null) // root level

        // pendingFolderName is set when we see <H3>…</H3> before <DL>
        var pendingFolderName: String? = null
        // Map from folder depth index to the DB id we inserted
        val folderIds = mutableMapOf<Int, Long>()
        var position = 0

        stream.bufferedReader(Charsets.UTF_8).forEachLine { rawLine ->
            val line = rawLine.trim()

            // Check folder open
            if (reDlOpen.containsMatchIn(line)) {
                if (pendingFolderName != null) {
                    val parentId = folderStack.lastOrNull()
                    val folderEntity = BookmarkEntity(
                        title = pendingFolderName!!,
                        url = null,
                        isFolder = true,
                        parentFolderId = parentId,
                        position = position++,
                    )
                    entities.add(folderEntity)
                    // We don't have a real id yet (no DB insert here), use list index as placeholder
                    folderStack.addLast(-(entities.size.toLong())) // negative = placeholder index
                    pendingFolderName = null
                } else {
                    folderStack.addLast(folderStack.lastOrNull())
                }
                return@forEachLine
            }

            // Check folder close
            if (reDlClose.containsMatchIn(line)) {
                if (folderStack.size > 1) folderStack.removeLast()
                return@forEachLine
            }

            // Check folder name
            val folderMatch = reFolder.find(line)
            if (folderMatch != null) {
                pendingFolderName = folderMatch.groupValues[1].htmlDecode()
                return@forEachLine
            }

            // Check bookmark link
            val anchorMatch = reAnchor.find(line)
            if (anchorMatch != null) {
                val href = anchorMatch.groupValues[1].trim()
                val title = anchorMatch.groupValues[2].trim().htmlDecode().ifBlank { href }
                if (href.startsWith("http://") || href.startsWith("https://")) {
                    entities.add(
                        BookmarkEntity(
                            title = title,
                            url = href,
                            isFolder = false,
                            parentFolderId = null, // resolved below
                            position = position++,
                        ),
                    )
                }
            }
        }

        // Simple flat import: resolve folder structure is complex without real IDs.
        // For M1: import as flat list under root, preserving titles.
        // Full hierarchical import is a M4+ enhancement.
        val flatEntities = entities
            .filter { !it.isFolder }
            .map { it.copy(parentFolderId = null) }

        var imported = 0
        var skipped = 0
        flatEntities.forEach { entity ->
            try {
                if (duplicatePolicy == DuplicatePolicy.SKIP && entity.url != null &&
                    repo.isBookmarked(entity.url)
                ) {
                    skipped++
                } else {
                    repo.importAll(listOf(entity))
                    imported++
                }
            } catch (e: Exception) {
                errors.add("${entity.url}: ${e.message}")
            }
        }

        ImportResult(imported = imported, skipped = skipped, errors = errors)
    }

    enum class DuplicatePolicy { SKIP, OVERWRITE }

    private fun String.htmlDecode(): String =
        replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
}
