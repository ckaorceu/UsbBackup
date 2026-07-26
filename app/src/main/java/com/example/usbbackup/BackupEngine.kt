package com.example.usbbackup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * 备份进度回调
 */
interface BackupProgressListener {
    fun onFileStart(name: String, index: Int, total: Int)
    fun onFileCopied(bytes: Long)
    fun onSkipped(name: String)
    fun onError(name: String, message: String)
    fun onFinished(copied: Int, skipped: Int, failed: Int, totalBytes: Long)
}

/**
 * 备份引擎：将内部存储的文件夹增量备份到 U 盘（通过 SAF 树 Uri 表示）
 */
class BackupEngine(private val context: Context) {

    @Volatile
    var cancelled = false

    /**
     * 执行备份
     * @param sourceDir  内部存储源目录 (File)
     * @param destTreeUri U 盘目标目录的 tree Uri (通过 ACTION_OPEN_DOCUMENT_TREE 获得)
     */
    fun backup(
        sourceDir: File,
        destTreeUri: Uri,
        listener: BackupProgressListener
    ) {
        val destRoot = DocumentFile.fromTreeUri(context, destTreeUri)
            ?: throw IllegalStateException("无法访问目标目录，请重新选择 U 盘")

        // 收集所有待备份文件
        val allFiles = mutableListOf<File>()
        collectFiles(sourceDir, allFiles)
        val total = allFiles.size

        var copied = 0
        var skipped = 0
        var failed = 0
        var totalBytes = 0L

        for ((index, file) in allFiles.withIndex()) {
            if (cancelled) break

            val relativePath = file.relativeTo(sourceDir).path
            listener.onFileStart(relativePath, index + 1, total)

            try {
                // 确保目标子目录存在
                val destFile = ensureDestFile(destRoot, file, sourceDir)

                if (destFile == null) {
                    listener.onError(relativePath, "无法创建目标文件")
                    failed++
                    continue
                }

                // 增量判断：目标已存在且大小相同则跳过
                if (destFile.exists() && destFile.length() == file.length()) {
                    listener.onSkipped(relativePath)
                    skipped++
                    continue
                }

                // 执行复制
                val bytes = copyFile(file, destFile)
                totalBytes += bytes
                copied++
                listener.onFileCopied(bytes)

            } catch (e: Exception) {
                listener.onError(relativePath, e.message ?: "未知错误")
                failed++
            }
        }

        listener.onFinished(copied, skipped, failed, totalBytes)
    }

    /**
     * 递归收集所有文件
     */
    private fun collectFiles(dir: File, out: MutableList<File>) {
        dir.listFiles()?.forEach { f ->
            if (f.isDirectory) {
                collectFiles(f, out)
            } else {
                out.add(f)
            }
        }
    }

    /**
     * 在目标树中按相对路径创建/获取对应的 DocumentFile
     */
    private fun ensureDestFile(destRoot: DocumentFile, file: File, sourceRoot: File): DocumentFile? {
        val rel = file.relativeTo(sourceRoot)
        val parts = rel.path.split(File.separator).filter { it.isNotEmpty() }

        var current = destRoot
        // 创建中间目录
        for (i in 0 until parts.size - 1) {
            val dirName = parts[i]
            current = current.findFile(dirName)
                ?: current.createDirectory(dirName)
                ?: return null
        }

        // 创建或获取目标文件
        val fileName = parts.last()
        val existing = current.findFile(fileName)
        if (existing != null) return existing

        val mimeType = getMimeType(fileName)
        return current.createFile(mimeType, fileName)
    }

    /**
     * 通过 ContentResolver 流式复制文件
     */
    private fun copyFile(source: File, dest: DocumentFile): Long {
        val destUri = dest.uri
        var bytesCopied = 0L

        context.contentResolver.openOutputStream(destUri, "w")?.use { output ->
            source.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var len: Int
                while (input.read(buffer).also { len = it } != -1) {
                    if (cancelled) break
                    output.write(buffer, 0, len)
                    bytesCopied += len
                }
                output.flush()
            }
        } ?: throw IllegalStateException("无法写入目标文件")

        return bytesCopied
    }

    private fun getMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "txt" -> "text/plain"
            "json" -> "application/json"
            "xml" -> "text/xml"
            "html" -> "text/html"
            else -> "application/octet-stream"
        }
    }
}
