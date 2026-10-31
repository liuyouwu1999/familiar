package dev.liuyouwu.familiar.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.liuyouwu.familiar.core.llm.model.ContentBlock
import dev.liuyouwu.familiar.core.llm.model.Role
import dev.liuyouwu.familiar.data.repository.ChatRepository
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * 自 ChatViewModel 纯移动拆出（P5 T5）：会话内容进出通道——导出（HTML/JSON/Markdown
 * 负载）与附件装载（图片压缩/文件抽取/视频抽帧，IO 线程解码）。ChatViewModel 经构造
 * 注入 UI 状态回写通道（selectedAttachments/videoFrameCount/error/currentModel）与
 * 依赖，行为与拆分前逐行等价（诊断日志沿用原 "ChatViewModel" tag）。
 */
internal class ExportController(
    private val scope: CoroutineScope,
    private val context: Application,
    private val chatRepository: ChatRepository,
    private val currentModel: StateFlow<String>,
    private val error: MutableStateFlow<String?>,
    private val selectedAttachments: MutableStateFlow<List<AttachmentInfo>>,
    private val videoFrameCount: MutableStateFlow<Int>,
) {

    /**
     * 构建导出串（审计 P2-16）：轻量投影不含 base64 大字段，序列化在 IO 线程，
     * 超长会话导出不卡主线程。
     * @return (payload, 安全文件名标题)；会话不存在返回 null
     */
    suspend fun buildExportPayload(conversationId: Long, format: String): Pair<String, String>? {
        val conversation = chatRepository.getConversationById(conversationId) ?: return null
        val title = conversation.title.ifBlank { "对话" }
        val rows = chatRepository.getMessagesForExport(conversationId)
        val payload = withContext(Dispatchers.IO) {
            if (format == "json") {
                dev.liuyouwu.familiar.util.ConversationExporter.toJson(title, currentModel.value, rows)
            } else {
                dev.liuyouwu.familiar.util.ConversationExporter.toMarkdown(title, currentModel.value, rows)
            }
        }
        // 标题可能带 / \ 等文件名非法字符，统一替换为下划线
        val safeTitle = title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return payload to safeTitle
    }

    /**
     * 导出 HTML（审计 P2-16：拼接与写盘在 IO 线程，仅 startActivity 回主线程）。
     * T5 起块模型消费：getAgentMessages 读时规范化，思考在 Reasoning 块不进正文
     * （stripThinkingTags 字符串清洗随旧链退役）；工具回执行不导出（与 UI 同口径，
     * 回执已配对进 assistant 工具卡片）。
     */
    fun exportConversationHtml(conversationId: Long) {
        scope.launch {
            // 拼接与写盘移到 IO 线程（审计 P2-16），仅 startActivity 回主线程
            val htmlFile = withContext(Dispatchers.IO) {
                val messages = chatRepository.getAgentMessages(conversationId).first()
                val sb = StringBuilder()
                sb.append("<html><head><meta charset='utf-8'><style>")
                sb.append("body{font-family:sans-serif;max-width:800px;margin:0 auto;padding:20px;}")
                sb.append(".msg{margin:10px 0;padding:10px;border-radius:8px;}")
                sb.append(".user{background:#e3f2fd;}.assistant{background:#f5f5f5;}")
                sb.append(".sender{font-weight:bold;margin-right:6px;}")
                sb.append("</style></head><body>")
                for (msg in messages) {
                    if (msg.role == Role.TOOL_RESULT) continue
                    val cls = if (msg.role == Role.USER) "user" else "assistant"
                    val plain = msg.blocks.filterIsInstance<ContentBlock.Text>()
                        .joinToString("\n") { it.text }
                    val escaped = plain.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                        .replace("\n", "<br>")
                    val senderName = msg.sender?.name?.takeIf { it.isNotBlank() }
                    val sender = senderName?.let {
                        "<span class='sender'>${it.replace("&", "&amp;").replace("<", "&lt;")}</span>"
                    } ?: ""
                    sb.append("<div class='msg $cls'>$sender$escaped</div>")
                }
                sb.append("</body></html>")

                val fileName =
                    "chat_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.html"
                val file = File(context.cacheDir, fileName)
                file.writeText(sb.toString())
                file
            }
            try {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    htmlFile
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/html"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                // createChooser 返回新 Intent，不继承原 intent 的 flags——
                // Application context 启动必须把 NEW_TASK 加在 chooser 上
                val chooser = Intent.createChooser(intent, "Export Chat")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
            } catch (e: Exception) {
                error.value = "导出失败：${(e.message ?: "未知错误").lineSequence().firstOrNull().orEmpty().take(60)}"
            }
        }
    }

    // ===== Attachments =====
    // 图片解码 / PDF解析 / 视频抽帧开销大，全部放 IO 线程，避免选附件时 UI 冻结

    fun addImageAttachment(uri: Uri) {
        scope.launch {
            val (base64, fileName) = withContext(Dispatchers.IO) {
                val b64 = uriToCompressedBase64(context, uri, maxWidth = 1024, quality = 80)
                b64 to getFileName(context, uri)
            }
            val current = selectedAttachments.value.toMutableList()
            current.add(
                AttachmentInfo(
                    uri = uri, type = AttachmentType.IMAGE,
                    displayName = fileName, base64 = base64
                )
            )
            selectedAttachments.value = current
        }
    }

    fun addFileAttachment(uri: Uri) {
        scope.launch {
            val (content, fileName) = withContext(Dispatchers.IO) {
                readFileContent(context, uri) to getFileName(context, uri)
            }
            val current = selectedAttachments.value.toMutableList()
            current.add(
                AttachmentInfo(
                    uri = uri, type = AttachmentType.FILE,
                    displayName = fileName, fileTextContent = content
                )
            )
            selectedAttachments.value = current
        }
    }

    fun addVideoAttachment(uri: Uri) {
        scope.launch {
            val (frames, fileName) = withContext(Dispatchers.IO) {
                extractVideoFrames(context, uri) to getFileName(context, uri)
            }
            val current = selectedAttachments.value.toMutableList()
            current.add(
                AttachmentInfo(
                    uri = uri, type = AttachmentType.VIDEO,
                    displayName = fileName, videoFrames = frames
                )
            )
            selectedAttachments.value = current
        }
    }

    fun removeAttachment(attachment: AttachmentInfo) {
        // 引用相等匹配：内容相同的重复附件只删用户点中的那一个
        val index = selectedAttachments.value.indexOfFirst { it === attachment }
        if (index >= 0) {
            selectedAttachments.value = selectedAttachments.value.toMutableList().apply {
                removeAt(index)
            }
        }
    }

    // ===== 附件工具（均在 Dispatchers.IO 上调用）=====

    /** 读取前检查文件大小，防 OOM */
    private fun isTooLarge(context: Context, uri: Uri, maxBytes: Long): Boolean {
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && cursor.moveToFirst()) {
                    val size = cursor.getLong(idx)
                    size > 0 && size > maxBytes
                } else false
            } ?: false
        }.getOrDefault(false)
    }

    private fun uriToBase64(context: Context, uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return ""
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    private fun uriToCompressedBase64(
        context: Context,
        uri: Uri,
        maxWidth: Int = 1024,
        quality: Int = 80
    ): String {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { decodeBitmap(it, bounds) }
            val sample = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / (maxWidth * 2))
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = context.contentResolver.openInputStream(uri)?.use {
                decodeBitmap(it, opts)
            } ?: return uriToBase64(context, uri)
            val scaled = if (bitmap.width > maxWidth) {
                val ratio = maxWidth.toFloat() / bitmap.width
                Bitmap.createScaledBitmap(
                    bitmap, maxWidth, (bitmap.height * ratio).toInt().coerceAtLeast(1), true
                )
            } else bitmap
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            val b64 = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            b64
        } catch (e: Exception) {
            Log.e("ChatViewModel", "compress image failed", e)
            ""
        } catch (e: OutOfMemoryError) {
            Log.e("ChatViewModel", "compress image OOM", e)
            ""
        }
    }

    private fun decodeBitmap(input: InputStream, opts: BitmapFactory.Options): Bitmap? =
        BitmapFactory.decodeStream(input, null, opts)

    private fun getFileName(context: Context, uri: Uri): String {
        var name: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                name = cursor.getString(idx)
            }
        }
        return name ?: uri.lastPathSegment ?: "attachment"
    }

    private fun readFileContent(context: Context, uri: Uri): String {
        val fileName = getFileName(context, uri).lowercase()
        if (isTooLarge(context, uri, maxBytes = 10L * 1024 * 1024)) {
            return "[文件超过 10MB，已跳过内容提取]"
        }
        return when {
            fileName.endsWith(".pdf") -> {
                context.contentResolver.openInputStream(uri)?.use { parsePdf(it) } ?: ""
            }
            fileName.endsWith(".docx") -> {
                context.contentResolver.openInputStream(uri)?.use { parseDocx(it) } ?: ""
            }
            else -> {
                context.contentResolver.openInputStream(uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                } ?: ""
            }
        }
    }

    private fun parseDocx(inputStream: InputStream): String {
        // docx = zip 包内的 word/document.xml，抽掉 XML 标签取正文
        return try {
            ZipInputStream(inputStream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == "word/document.xml") {
                        val xml = zip.readBytes().toString(Charsets.UTF_8)
                        // 段落结束换行，再去掉其余标签
                        val withBreaks = xml.replace("</w:p>", "\n")
                            .replace("<w:br/>", "\n")
                            .replace(Regex("<[^>]+>"), "")
                            .replace("&amp;", "&")
                            .replace("&lt;", "<")
                            .replace("&gt;", ">")
                            .replace("&quot;", "\"")
                            .replace("&apos;", "'")
                        return withBreaks.trim()
                    }
                    entry = zip.nextEntry
                }
                ""
            }
        } catch (e: Exception) {
            Log.e("ChatViewModel", "parseDocx failed", e)
            ""
        }
    }

    private fun parsePdf(inputStream: InputStream): String {
        return try {
            com.tom_roush.pdfbox.pdmodel.PDDocument.load(inputStream).use { doc ->
                com.tom_roush.pdfbox.text.PDFTextStripper().getText(doc)
            }
        } catch (e: Exception) {
            Log.e("ChatViewModel", "parsePdf failed", e)
            ""
        }
    }

    private fun extractVideoFrames(context: Context, uri: Uri): List<String> {
        val retriever = MediaMetadataRetriever()
        val frames = mutableListOf<String>()
        try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: return frames
            val count = videoFrameCount.value.coerceIn(1, 50)
            for (i in 0 until count) {
                val timeUs = durationMs * 1000L * i / count
                val frame =
                    retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: continue
                val scaled = if (frame.width > 640) {
                    val ratio = 640f / frame.width
                    Bitmap.createScaledBitmap(
                        frame, 640, (frame.height * ratio).toInt().coerceAtLeast(1), true
                    )
                } else frame
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
                frames.add(
                    android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
                )
                if (scaled !== frame) scaled.recycle()
                frame.recycle()
            }
        } catch (e: Exception) {
            Log.e("ChatViewModel", "extractVideoFrames failed", e)
        } catch (e: OutOfMemoryError) {
            Log.e("ChatViewModel", "extractVideoFrames OOM", e)
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
        return frames
    }
}
