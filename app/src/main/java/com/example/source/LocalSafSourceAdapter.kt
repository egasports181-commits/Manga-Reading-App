package com.example.source

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.data.preferences.AppPreferencesRepository
import com.example.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.zip.ZipInputStream

class LocalSafSourceAdapter(
    private val context: Context,
    private val prefsRepo: AppPreferencesRepository
) : MangaSourceAdapter {

    override val id: String = "local_saf"
    override val name: String = "Local Storage (SAF)"
    override val language: String = "local"
    override val category: String = "On-Device Archives"
    override val baseUrl: String = "content://local"
    override val supportsFilters: Boolean = false
    override val supportsLogin: Boolean = false

    override suspend fun isAvailable(): Boolean {
        val treeUri = prefsRepo.preferences.first().localSafSourceTreeUri
        return treeUri.isNotBlank() && runCatching {
            DocumentFile.fromTreeUri(context, Uri.parse(treeUri))?.exists() == true
        }.getOrDefault(false)
    }

    override suspend fun getPopular(page: Int, filters: SearchFilters): SourcePagedResult =
        scanFolder(query = "", page = page)

    override suspend fun getLatest(page: Int, filters: SearchFilters): SourcePagedResult =
        scanFolder(query = "", page = page)

    override suspend fun search(query: String, page: Int, filters: SearchFilters): SourcePagedResult =
        scanFolder(query = query, page = page)

    private suspend fun scanFolder(query: String, page: Int): SourcePagedResult = withContext(Dispatchers.IO) {
        if (page > 1) return@withContext SourcePagedResult(emptyList(), false)
        val uriStr = prefsRepo.preferences.first().localSafSourceTreeUri
        if (uriStr.isBlank()) return@withContext SourcePagedResult(emptyList(), false)
        val root = DocumentFile.fromTreeUri(context, Uri.parse(uriStr))
            ?: return@withContext SourcePagedResult(emptyList(), false)

        val items = mutableListOf<SourceMangaItem>()
        for (entry in root.listFiles()) {
            val title = entry.name ?: continue
            if (query.isNotBlank() && !title.contains(query, ignoreCase = true)) continue

            val isArchive = title.endsWith(".cbz", true) || title.endsWith(".zip", true)
            if (entry.isDirectory || isArchive) {
                val cleanTitle = title.substringBeforeLast(".")
                val cover = if (isArchive) {
                    findFirstZipImageUri(entry.uri)?.let { "cbz://${Uri.encode(entry.uri.toString())}/${Uri.encode(it)}" }.orEmpty()
                } else {
                    findFirstFolderImage(entry)?.uri?.toString().orEmpty()
                }
                items.add(
                    SourceMangaItem(
                        sourceId = id,
                        sourceName = name,
                        mangaKey = entry.uri.toString(),
                        title = cleanTitle,
                        coverUrl = cover,
                        author = "Local Archive",
                        description = "Stored on device via Storage Access Framework.",
                        genres = listOf("Local"),
                        status = MangaStatus.UNKNOWN,
                        language = "local"
                    )
                )
            }
        }
        SourcePagedResult(items.sortedBy { it.title }, hasNextPage = false)
    }

    override suspend fun getMangaDetails(mangaKey: String): SourceMangaItem = withContext(Dispatchers.IO) {
        val doc = DocumentFile.fromTreeUri(context, Uri.parse(mangaKey))
            ?: DocumentFile.fromSingleUri(context, Uri.parse(mangaKey))
        val nameTitle = doc?.name?.substringBeforeLast(".") ?: "Local Manga"
        SourceMangaItem(
            sourceId = id,
            sourceName = name,
            mangaKey = mangaKey,
            title = nameTitle,
            author = "Local Storage",
            description = "Local manga folder or CBZ archive accessed through Android Storage Access Framework.",
            genres = listOf("Local"),
            status = MangaStatus.UNKNOWN,
            language = "local"
        )
    }

    override suspend fun getChapters(mangaKey: String): List<SourceChapter> = withContext(Dispatchers.IO) {
        val uri = Uri.parse(mangaKey)
        val doc = DocumentFile.fromTreeUri(context, uri)
            ?: DocumentFile.fromSingleUri(context, uri)
            ?: return@withContext emptyList()

        if (!doc.isDirectory) {
            return@withContext listOf(
                SourceChapter(
                    chapterKey = "archive::$mangaKey",
                    name = doc.name?.substringBeforeLast(".") ?: "Chapter 1",
                    chapterNumber = 1.0,
                    scanlator = "Local CBZ"
                )
            )
        }

        val chapters = mutableListOf<SourceChapter>()
        val children = doc.listFiles().sortedBy { it.name.orEmpty() }
        var idx = 1
        var hasLooseImages = false
        for (child in children) {
            val childName = child.name ?: continue
            if (child.isDirectory || childName.endsWith(".cbz", true) || childName.endsWith(".zip", true)) {
                chapters.add(
                    SourceChapter(
                        chapterKey = child.uri.toString(),
                        name = childName.substringBeforeLast("."),
                        chapterNumber = idx.toDouble(),
                        scanlator = "Local"
                    )
                )
                idx++
            } else if (isImageFile(childName)) {
                hasLooseImages = true
            }
        }
        if (hasLooseImages && chapters.isEmpty()) {
            chapters.add(
                SourceChapter(
                    chapterKey = mangaKey,
                    name = "Chapter 1",
                    chapterNumber = 1.0,
                    scanlator = "Local Folder"
                )
            )
        }
        chapters.reversed()
    }

    override suspend fun getChapterPages(chapterKey: String): List<SourcePage> = withContext(Dispatchers.IO) {
        val actualUriStr = chapterKey.removePrefix("archive::")
        val uri = Uri.parse(actualUriStr)
        val doc = DocumentFile.fromTreeUri(context, uri)
            ?: DocumentFile.fromSingleUri(context, uri)
            ?: return@withContext emptyList()

        if (doc.isDirectory) {
            return@withContext doc.listFiles()
                .filter { isImageFile(it.name.orEmpty()) }
                .sortedBy { it.name.orEmpty() }
                .mapIndexed { index, file ->
                    SourcePage(index = index, imageUrl = file.uri.toString(), isLocalFile = true)
                }
        }

        // CBZ / ZIP archive
        val entries = mutableListOf<String>()
        context.contentResolver.openInputStream(uri)?.use { stream ->
            ZipInputStream(stream).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && isImageFile(entry.name)) {
                        entries.add(entry.name)
                    }
                    entry = zis.nextEntry
                }
            }
        }
        entries.sorted().mapIndexed { index, entryName ->
            SourcePage(
                index = index,
                imageUrl = "cbz://${Uri.encode(actualUriStr)}/${Uri.encode(entryName)}",
                isLocalFile = true
            )
        }
    }

    private fun findFirstFolderImage(folder: DocumentFile): DocumentFile? {
        for (child in folder.listFiles().sortedBy { it.name.orEmpty() }) {
            if (!child.isDirectory && isImageFile(child.name.orEmpty())) return child
            if (child.isDirectory) {
                val nested = findFirstFolderImage(child)
                if (nested != null) return nested
            }
        }
        return null
    }

    private fun findFirstZipImageUri(uri: Uri): String? {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ZipInputStream(stream).use { zis ->
                    val images = mutableListOf<String>()
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && isImageFile(entry.name)) {
                            images.add(entry.name)
                        }
                        entry = zis.nextEntry
                    }
                    images.sorted().firstOrNull()
                }
            }
        }.getOrNull()
    }

    private fun isImageFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
            lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".gif")
    }
}
