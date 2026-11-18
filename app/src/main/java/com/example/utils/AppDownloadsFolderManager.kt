package com.example.utils

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class SavedDownloadResult(
  val uri: Uri?,
  val displayPath: String,
  val fileName: String,
  val savedInPublicDownloads: Boolean
)

data class AppFolderFile(
  val id: String,
  val uriString: String,
  val displayName: String,
  val category: String,
  val subfolder: String,
  val mimeType: String,
  val sizeBytes: Long,
  val modifiedMillis: Long,
  val source: String
)

data class AppFolderSnapshot(
  val folderDisplayPath: String,
  val linkedTreeActive: Boolean,
  val isPublicDownloadsApiSupported: Boolean,
  val files: List<AppFolderFile>
)

object AppDownloadsFolderManager {
  const val APP_FOLDER_NAME = "بقاله العزي"
  const val SUB_BACKUPS = "النسخ_الاحتياطي"
  const val SUB_PDFS = "الفواتير_PDF"
  const val SUB_IMAGES = "صور_الفواتير"
  const val SUB_REPORTS = "التقارير_والملفات"

  private const val GUIDE_FILE_NAME = "دليل_مجلد_بقاله_العزي.txt"

  fun getPublicDisplayPath(subfolder: String = ""): String {
    return if (subfolder.isBlank()) {
      "Download/$APP_FOLDER_NAME"
    } else {
      "Download/$APP_FOLDER_NAME/$subfolder"
    }
  }

  fun hasValidLinkedTreePermission(context: Context): Uri? {
    val raw = StorePreferences.getSettings(context).linkedFolderUri
    if (raw.isBlank()) return null
    val treeUri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
    val hasPerm = context.contentResolver.persistedUriPermissions.any {
      it.uri == treeUri && it.isReadPermission && it.isWritePermission
    }
    return if (hasPerm) treeUri else null
  }

  suspend fun ensureFolderInitialized(context: Context) = withContext(Dispatchers.IO) {
    val existing = listFiles(context)
    if (existing.files.isEmpty()) {
      val readme = buildString {
        appendLine("مجلد بقالة العزي الخاص بالتطبيق")
        appendLine("======================================")
        appendLine("المسار: Download/$APP_FOLDER_NAME")
        appendLine("- $SUB_BACKUPS : ملفات النسخ الاحتياطي (JSON)")
        appendLine("- $SUB_PDFS : فواتير PDF المحفوظة")
        appendLine("- $SUB_IMAGES : صور الفواتير والإيصالات الحرارية")
        appendLine("- $SUB_REPORTS : التقارير المالية والملفات المصدرة")
      }.toByteArray(Charsets.UTF_8)
      runCatching {
        saveBytes(
          context = context,
          subfolder = "",
          fileName = GUIDE_FILE_NAME,
          mimeType = "text/plain",
          bytes = readme,
          overwriteExisting = true
        )
      }
    }
  }

  fun saveFile(
    context: Context,
    subfolder: String,
    sourceFile: File,
    displayName: String = sourceFile.name,
    mimeType: String,
    overwriteExisting: Boolean = true
  ): SavedDownloadResult? {
    if (!sourceFile.exists() || !sourceFile.isFile) return null
    val bytes = sourceFile.readBytes()
    return saveBytes(context, subfolder, displayName, mimeType, bytes, overwriteExisting)
  }

  fun saveBytes(
    context: Context,
    subfolder: String,
    fileName: String,
    mimeType: String,
    bytes: ByteArray,
    overwriteExisting: Boolean = true
  ): SavedDownloadResult {
    val safeName = sanitizeFileName(fileName)
    val cleanSub = subfolder.trim('/').trim()

    // 1. Prefer user-linked SAF folder if granted (works across reinstalls & all Android versions)
    hasValidLinkedTreePermission(context)?.let { treeUri ->
      runCatching {
        saveToSafTree(context, treeUri, cleanSub, safeName, mimeType, bytes, overwriteExisting)?.let { uri ->
          return SavedDownloadResult(
            uri = uri,
            displayPath = "${getPublicDisplayPath(cleanSub)}/$safeName",
            fileName = safeName,
            savedInPublicDownloads = true
          )
        }
      }
    }

    // 2. Zero-permission Scoped Storage MediaStore.Downloads on Android 10+ (API 29+)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      runCatching {
        saveToMediaStoreDownloads(context, cleanSub, safeName, mimeType, bytes, overwriteExisting)?.let { uri ->
          return SavedDownloadResult(
            uri = uri,
            displayPath = "${getPublicDisplayPath(cleanSub)}/$safeName",
            fileName = safeName,
            savedInPublicDownloads = true
          )
        }
      }
    }

    // 3. Fallback to app-scoped external Downloads folder (zero permissions on API 24-28 & test environments)
    val localFile = saveToAppExternalDownloads(context, cleanSub, safeName, bytes)
    val uri = runCatching {
      FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", localFile)
    }.getOrElse { Uri.fromFile(localFile) }

    return SavedDownloadResult(
      uri = uri,
      displayPath = localFile.absolutePath,
      fileName = safeName,
      savedInPublicDownloads = false
    )
  }

  fun listFiles(context: Context): AppFolderSnapshot {
    val results = mutableListOf<AppFolderFile>()
    val seenKeys = mutableSetOf<String>()

    val linkedTree = hasValidLinkedTreePermission(context)
    if (linkedTree != null) {
      runCatching {
        listFromSafTree(context, linkedTree).forEach { file ->
          val key = "${file.subfolder}/${file.displayName}"
          if (seenKeys.add(key)) {
            results.add(file)
          }
        }
      }
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      runCatching {
        listFromMediaStoreDownloads(context).forEach { file ->
          val key = "${file.subfolder}/${file.displayName}"
          if (seenKeys.add(key)) {
            results.add(file)
          }
        }
      }
    }

    listFromAppExternalDownloads(context).forEach { file ->
      val key = "${file.subfolder}/${file.displayName}"
      if (seenKeys.add(key)) {
        results.add(file)
      }
    }

    results.sortByDescending { it.modifiedMillis }

    return AppFolderSnapshot(
      folderDisplayPath = getPublicDisplayPath(),
      linkedTreeActive = linkedTree != null,
      isPublicDownloadsApiSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
      files = results
    )
  }

  fun openFile(context: Context, uriString: String, mimeType: String): Boolean {
    return try {
      val uri = resolveReadableUri(context, uriString)
      val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType.ifBlank { "*/*" })
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(Intent.createChooser(intent, "فتح الملف").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      })
      true
    } catch (_: Exception) {
      false
    }
  }

  fun shareFile(context: Context, uriString: String, mimeType: String, title: String): Boolean {
    return try {
      val uri = resolveReadableUri(context, uriString)
      WhatsAppHelper.shareFile(
        context = context,
        fileUri = uri,
        mimeType = mimeType.ifBlank { "*/*" },
        message = title,
        preferWhatsApp = false
      )
    } catch (_: Exception) {
      false
    }
  }

  fun deleteFile(context: Context, uriString: String): Boolean {
    return try {
      val uri = Uri.parse(uriString)
      if (uri.scheme == "file") {
        val path = uri.path ?: return false
        return File(path).delete()
      }
      if (DocumentsContract.isDocumentUri(context, uri)) {
        return DocumentsContract.deleteDocument(context.contentResolver, uri)
      }
      context.contentResolver.delete(uri, null, null) > 0
    } catch (_: Exception) {
      false
    }
  }

  fun resolveReadableUri(context: Context, uriString: String): Uri {
    val parsed = Uri.parse(uriString)
    if (parsed.scheme == "file") {
      val file = File(parsed.path ?: "")
      return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
    return parsed
  }

  fun buildSuggestedDownloadsInitialUri(): Uri? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      Uri.parse("content://com.android.externalstorage.documents/document/primary:Download")
    } else {
      null
    }
  }

  @RequiresApi(Build.VERSION_CODES.Q)
  private fun saveToMediaStoreDownloads(
    context: Context,
    subfolder: String,
    fileName: String,
    mimeType: String,
    bytes: ByteArray,
    overwriteExisting: Boolean
  ): Uri? {
    val resolver = context.contentResolver
    val relativePath = if (subfolder.isBlank()) {
      "${Environment.DIRECTORY_DOWNLOADS}/$APP_FOLDER_NAME"
    } else {
      "${Environment.DIRECTORY_DOWNLOADS}/$APP_FOLDER_NAME/$subfolder"
    }

    if (overwriteExisting) {
      val existingUri = findMediaStoreEntry(context, relativePath, fileName)
      if (existingUri != null) {
        val updated = runCatching {
          resolver.openOutputStream(existingUri, "wt")?.use { it.write(bytes) } ?: error("No stream")
          val updateValues = ContentValues().apply {
            put(MediaStore.Downloads.IS_PENDING, 0)
            put(MediaStore.Downloads.DATE_MODIFIED, System.currentTimeMillis() / 1000L)
          }
          resolver.update(existingUri, updateValues, null, null)
          existingUri
        }.getOrNull()
        if (updated != null) return updated
        runCatching { resolver.delete(existingUri, null, null) }
      }
    }

    val values = ContentValues().apply {
      put(MediaStore.Downloads.DISPLAY_NAME, fileName)
      put(MediaStore.Downloads.MIME_TYPE, mimeType)
      put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
      put(MediaStore.Downloads.IS_PENDING, 1)
    }

    val insertedUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
    return try {
      resolver.openOutputStream(insertedUri, "w")?.use { it.write(bytes) }
        ?: error("تعذر فتح مجرى الكتابة في مجلد التنزيلات")
      val done = ContentValues().apply {
        put(MediaStore.Downloads.IS_PENDING, 0)
      }
      resolver.update(insertedUri, done, null, null)
      insertedUri
    } catch (e: Exception) {
      runCatching { resolver.delete(insertedUri, null, null) }
      null
    }
  }

  @RequiresApi(Build.VERSION_CODES.Q)
  private fun findMediaStoreEntry(context: Context, relativePath: String, fileName: String): Uri? {
    val resolver = context.contentResolver
    val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.RELATIVE_PATH)
    val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
    val args = arrayOf(fileName, "$relativePath%")
    resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, args, null)?.use { cursor ->
      if (cursor.moveToFirst()) {
        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
        return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
      }
    }
    return null
  }

  @RequiresApi(Build.VERSION_CODES.Q)
  private fun listFromMediaStoreDownloads(context: Context): List<AppFolderFile> {
    val resolver = context.contentResolver
    val projection = arrayOf(
      MediaStore.Downloads._ID,
      MediaStore.Downloads.DISPLAY_NAME,
      MediaStore.Downloads.MIME_TYPE,
      MediaStore.Downloads.SIZE,
      MediaStore.Downloads.DATE_MODIFIED,
      MediaStore.Downloads.RELATIVE_PATH
    )
    val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
    val args = arrayOf("%$APP_FOLDER_NAME%")
    val sortOrder = "${MediaStore.Downloads.DATE_MODIFIED} DESC"

    val items = mutableListOf<AppFolderFile>()
    resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, args, sortOrder)?.use { cursor ->
      val idxId = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
      val idxName = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
      val idxMime = cursor.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE)
      val idxSize = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
      val idxMod = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)
      val idxPath = cursor.getColumnIndexOrThrow(MediaStore.Downloads.RELATIVE_PATH)

      while (cursor.moveToNext()) {
        val id = cursor.getLong(idxId)
        val name = cursor.getString(idxName) ?: continue
        val mime = cursor.getString(idxMime) ?: inferMimeType(name)
        val size = cursor.getLong(idxSize)
        val rawMod = cursor.getLong(idxMod)
        val modMillis = if (rawMod in 1L..9_999_999_999L) rawMod * 1000L else rawMod
        val relPath = cursor.getString(idxPath) ?: ""
        val sub = extractSubfolderFromRelativePath(relPath)
        val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)

        items.add(
          AppFolderFile(
            id = "ms_$id",
            uriString = uri.toString(),
            displayName = name,
            category = classifyCategory(sub, name, mime),
            subfolder = sub,
            mimeType = mime,
            sizeBytes = size,
            modifiedMillis = modMillis,
            source = "MEDIASTORE"
          )
        )
      }
    }
    return items
  }

  private fun saveToSafTree(
    context: Context,
    treeUri: Uri,
    subfolder: String,
    fileName: String,
    mimeType: String,
    bytes: ByteArray,
    overwriteExisting: Boolean
  ): Uri? {
    val appRootDocId = ensureSafAppRootFolder(context, treeUri) ?: return null
    val targetDirDocId = if (subfolder.isBlank()) {
      appRootDocId
    } else {
      ensureSafChildDir(context, treeUri, appRootDocId, subfolder) ?: return null
    }
    val targetDirUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, targetDirDocId)

    if (overwriteExisting) {
      val existingFileDocId = findSafChildDocId(context, treeUri, targetDirDocId, fileName, wantDir = false)
      if (existingFileDocId != null) {
        val existingUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, existingFileDocId)
        val ok = runCatching {
          context.contentResolver.openOutputStream(existingUri, "wt")?.use { it.write(bytes) } != null
        }.getOrDefault(false)
        if (ok) return existingUri
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, existingUri) }
      }
    }

    val createdUri = DocumentsContract.createDocument(context.contentResolver, targetDirUri, mimeType, fileName)
      ?: return null
    context.contentResolver.openOutputStream(createdUri, "w")?.use { it.write(bytes) }
      ?: error("تعذر الكتابة في المجلد المرتبط")
    return createdUri
  }

  private fun listFromSafTree(context: Context, treeUri: Uri): List<AppFolderFile> {
    val appRootDocId = findSafAppRootFolder(context, treeUri) ?: return emptyList()
    val out = mutableListOf<AppFolderFile>()
    collectSafChildren(context, treeUri, appRootDocId, "", out)
    return out
  }

  private fun collectSafChildren(
    context: Context,
    treeUri: Uri,
    parentDocId: String,
    currentSub: String,
    out: MutableList<AppFolderFile>
  ) {
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
    val projection = arrayOf(
      DocumentsContract.Document.COLUMN_DOCUMENT_ID,
      DocumentsContract.Document.COLUMN_DISPLAY_NAME,
      DocumentsContract.Document.COLUMN_MIME_TYPE,
      DocumentsContract.Document.COLUMN_SIZE,
      DocumentsContract.Document.COLUMN_LAST_MODIFIED
    )
    context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
      val idxId = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
      val idxName = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
      val idxMime = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
      val idxSize = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
      val idxMod = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

      while (cursor.moveToNext()) {
        val docId = cursor.getString(idxId) ?: continue
        val name = cursor.getString(idxName) ?: continue
        val mime = cursor.getString(idxMime) ?: inferMimeType(name)
        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
          val nextSub = if (currentSub.isBlank()) name else "$currentSub/$name"
          collectSafChildren(context, treeUri, docId, nextSub, out)
        } else {
          val size = cursor.getLong(idxSize)
          val mod = cursor.getLong(idxMod)
          val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
          out.add(
            AppFolderFile(
              id = "saf_${docId.hashCode()}",
              uriString = docUri.toString(),
              displayName = name,
              category = classifyCategory(currentSub, name, mime),
              subfolder = currentSub,
              mimeType = mime,
              sizeBytes = size,
              modifiedMillis = mod,
              source = "SAF"
            )
          )
        }
      }
    }
  }

  private fun findSafAppRootFolder(context: Context, treeUri: Uri): String? {
    val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
    val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
    val rootName = querySafDisplayName(context, rootDocUri)
    if (rootName == APP_FOLDER_NAME) return treeDocId
    return findSafChildDocId(context, treeUri, treeDocId, APP_FOLDER_NAME, wantDir = true)
  }

  private fun ensureSafAppRootFolder(context: Context, treeUri: Uri): String? {
    val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
    val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
    val rootName = querySafDisplayName(context, rootDocUri)
    if (rootName == APP_FOLDER_NAME) return treeDocId
    return ensureSafChildDir(context, treeUri, treeDocId, APP_FOLDER_NAME)
  }

  private fun ensureSafChildDir(context: Context, treeUri: Uri, parentDocId: String, dirName: String): String? {
    findSafChildDocId(context, treeUri, parentDocId, dirName, wantDir = true)?.let { return it }
    val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
    val created = DocumentsContract.createDocument(
      context.contentResolver,
      parentUri,
      DocumentsContract.Document.MIME_TYPE_DIR,
      dirName
    ) ?: return null
    return DocumentsContract.getDocumentId(created)
  }

  private fun findSafChildDocId(
    context: Context,
    treeUri: Uri,
    parentDocId: String,
    targetName: String,
    wantDir: Boolean
  ): String? {
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
    val projection = arrayOf(
      DocumentsContract.Document.COLUMN_DOCUMENT_ID,
      DocumentsContract.Document.COLUMN_DISPLAY_NAME,
      DocumentsContract.Document.COLUMN_MIME_TYPE
    )
    context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
      val idxId = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
      val idxName = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
      val idxMime = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
      while (cursor.moveToNext()) {
        val name = cursor.getString(idxName) ?: continue
        val mime = cursor.getString(idxMime) ?: ""
        val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
        if (name == targetName && isDir == wantDir) {
          return cursor.getString(idxId)
        }
      }
    }
    return null
  }

  private fun querySafDisplayName(context: Context, docUri: Uri): String? {
    return runCatching {
      context.contentResolver.query(
        docUri,
        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
        null,
        null,
        null
      )?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
      }
    }.getOrNull()
  }

  private fun getAppExternalRootDir(context: Context): File {
    val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
      ?: File(context.filesDir, "downloads")
    return File(base, APP_FOLDER_NAME).apply { mkdirs() }
  }

  private fun saveToAppExternalDownloads(
    context: Context,
    subfolder: String,
    fileName: String,
    bytes: ByteArray
  ): File {
    val root = getAppExternalRootDir(context)
    val targetDir = if (subfolder.isBlank()) root else File(root, subfolder).apply { mkdirs() }
    val outFile = File(targetDir, fileName)
    FileOutputStream(outFile).use { it.write(bytes) }
    return outFile
  }

  private fun listFromAppExternalDownloads(context: Context): List<AppFolderFile> {
    val root = getAppExternalRootDir(context)
    if (!root.exists()) return emptyList()
    val out = mutableListOf<AppFolderFile>()
    fun walk(dir: File, sub: String) {
      dir.listFiles()?.forEach { f ->
        if (f.isDirectory) {
          val nextSub = if (sub.isBlank()) f.name else "$sub/${f.name}"
          walk(f, nextSub)
        } else if (f.isFile && f.length() > 0) {
          val mime = inferMimeType(f.name)
          out.add(
            AppFolderFile(
              id = "loc_${f.absolutePath.hashCode()}",
              uriString = Uri.fromFile(f).toString(),
              displayName = f.name,
              category = classifyCategory(sub, f.name, mime),
              subfolder = sub,
              mimeType = mime,
              sizeBytes = f.length(),
              modifiedMillis = f.lastModified(),
              source = "APP_LOCAL"
            )
          )
        }
      }
    }
    walk(root, "")
    return out
  }

  private fun extractSubfolderFromRelativePath(relPath: String): String {
    val normalized = relPath.trim('/').replace('\\', '/')
    val idx = normalized.indexOf(APP_FOLDER_NAME)
    if (idx < 0) return ""
    return normalized.substring(idx + APP_FOLDER_NAME.length).trim('/')
  }

  internal fun classifyCategory(subfolder: String, fileName: String, mimeType: String): String {
    val lower = fileName.lowercase()
    return when {
      subfolder == SUB_BACKUPS || lower.endsWith(".json") -> "BACKUP"
      subfolder == SUB_PDFS && !lower.startsWith("report_") -> "PDF"
      subfolder == SUB_IMAGES || mimeType.startsWith("image/") || lower.endsWith(".png") || lower.endsWith(".jpg") -> "IMAGE"
      subfolder == SUB_REPORTS || lower.startsWith("report_") || lower.endsWith(".txt") || lower.endsWith(".csv") -> "REPORT"
      lower.endsWith(".pdf") -> "PDF"
      else -> "OTHER"
    }
  }

  private fun inferMimeType(fileName: String): String {
    val lower = fileName.lowercase()
    return when {
      lower.endsWith(".json") -> "application/json"
      lower.endsWith(".pdf") -> "application/pdf"
      lower.endsWith(".png") -> "image/png"
      lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
      lower.endsWith(".txt") -> "text/plain"
      else -> "application/octet-stream"
    }
  }

  private fun sanitizeFileName(raw: String): String {
    val cleaned = raw.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
    return cleaned.ifBlank { "file_${System.currentTimeMillis()}" }
  }
}
