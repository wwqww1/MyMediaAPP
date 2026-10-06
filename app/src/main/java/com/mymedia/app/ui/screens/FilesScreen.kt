/*
 * 我的多媒体 - MyMediaApp
 * Copyright (C) 2026  谈水君
 *
 * 本程序是自由软件：在 GNU GPL v3 或（您选择）更高版本下发布。
 * 详细信息请参阅 GNU General Public License。
 *
 * 本程序按"原样"提供，不提供任何明示或暗示的保证。
 * 详见 GNU General Public License。
 *
 * 您应已收到 GNU General Public License 的副本；
 * 如果没有，请参阅 <https://www.gnu.org/licenses/>。
 */

@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.mymedia.app.ui.screens

import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.widget.VideoView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mymedia.app.utils.MediaSeparator
import com.mymedia.app.utils.OutputStore
import com.mymedia.app.utils.ShareUtil
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.compose.ui.viewinterop.AndroidView

/** 图片扩展名：预览走图片显示（不误判为视频） */
private val imageExts = setOf("gif", "jpg", "jpeg", "png", "webp", "bmp")
/** 文本扩展名：预览显示文本内容 */
private val textExts = setOf("txt", "log", "md", "json", "ini", "csv", "srt", "xml", "html", "htm", "js", "kt")

/** 文件大小单位换算（B / KB / MB / GB） */
private fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale.US, "%.2f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1000 -> String.format(Locale.US, "%.1f KB", bytes / 1000.0)
    else -> "$bytes B"
}

/**
 * 预览类型探测（与扩展名互补，防止内容与扩展名不符的文件误判）：
 * 用 MediaExtractor 读真实轨道，返回 "video"/"audio"/""（无法探测）。
 * 封面图等 image 轨不算 video（image/jpeg/png 等）（与 C 层 find_stream 的 attached_pic 处理一致）。
 */
private fun probeFileKind(file: File): String = runCatching {
    val ex = MediaExtractor()
    try {
        ex.setDataSource(file.absolutePath)
        var v = false; var a = false
        for (i in 0 until ex.trackCount) {
            val m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (m.startsWith("video/")) v = true
            if (m.startsWith("audio/")) a = true
        }
        when { v -> "video"; a -> "audio"; else -> "" }
    } finally {
        ex.release()
    }
}.getOrDefault("")

/** 预览分发类型：扩展名优先（图片/文本），音视频用真实轨道探测兜底 */
private fun fileKind(file: File): String {
    val ext = file.extension.lowercase()
    if (ext in imageExts) return "image"
    if (ext in textExts) return "text"
    val probed = probeFileKind(file)
    return if (probed == "video" || probed == "audio") probed else "video"
}

@Composable
fun FilesScreen() {
    val context = LocalContext.current
    var refreshKey by remember { mutableIntStateOf(0) }
    val files = remember(refreshKey) { listOutputFiles() }
    var selected by remember { mutableStateOf<File?>(null) }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).displayCutoutPadding()
    ) {
        Text("文件", style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp))
        val syncDir = OutputStore.displayName(context)
        Text(
            if (syncDir != null) "工作目录：${MediaSeparator.getOutputDir().absolutePath}\n已同步到：$syncDir（系统文件管理器可见）"
            else MediaSeparator.getOutputDir().absolutePath,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(start = 20.dp, end = 20.dp)
        )

        if (files.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.AutoMirrored.Filled.List, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(12.dp))
                Text("还没有产出文件", color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                items(files, key = { it.absolutePath }) { f ->
                    FileRow(f) {
                        selected = f
                    }
                    Spacer(Modifier.height(8.dp))
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }

    selected?.let { file ->
        FileActionDialog(file, onDismiss = { selected = null }, onChanged = { refreshKey++ })
    }
}

private fun listOutputFiles(): List<File> {
    val dir = MediaSeparator.getOutputDir()
    return (dir.listFiles() ?: emptyArray())
        .filter { it.isFile }
        .sortedByDescending { it.lastModified() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(file: File, onClick: () -> Unit) {
    val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    val sizeText = formatSize(file.length())
    Card(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(fileIcon(file.extension), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(file.name, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("$sizeText · ${fmt.format(Date(file.lastModified()))}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            Icon(Icons.Filled.MoreVert, null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}

private fun fileIcon(ext: String): androidx.compose.ui.graphics.vector.ImageVector = when (ext.lowercase()) {
    "mp3", "m4a", "ogg", "flac", "wav", "aac" -> Icons.Filled.PlayArrow
    "gif" -> Icons.Filled.Face
    "jpg", "jpeg", "png" -> Icons.Filled.Favorite
    else -> Icons.Filled.PlayArrow
}

@Composable
private fun FileActionDialog(file: File, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    var showRename by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showPreview by remember { mutableStateOf(false) }

    if (showPreview) {
        FilePreviewDialog(file, onDismiss = { showPreview = false })
    } else if (showRename) {
        RenameDialog(file, onDismiss = { showRename = false }, onRenamed = { showRename = false; onChanged() })
    } else if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除文件？") },
            text = { Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(onClick = {
                    file.delete()
                    showDeleteConfirm = false
                    onDismiss()
                    onChanged()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") } }
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Description, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("大小：${formatSize(file.length())}", style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("位置：${file.parent}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(16.dp))
                    // 操作按钮 2x2 网格（等宽圆角，不再堆叠）
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ActionButton("预览", Icons.Filled.PlayArrow, null, Modifier.weight(1f)) { showPreview = true }
                        ActionButton("分享", Icons.Filled.Share, null, Modifier.weight(1f)) { ShareUtil.shareFile(context, file); onDismiss() }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ActionButton("重命名", Icons.Filled.Edit, null, Modifier.weight(1f)) { showRename = true }
                        ActionButton("删除", Icons.Filled.Delete, MaterialTheme.colorScheme.error, Modifier.weight(1f)) { showDeleteConfirm = true }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
        )
    }
}

/** 对话框操作按钮：等宽圆角卡片样式（参考 DeepSeek 简洁风格） */
@Composable
private fun ActionButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(46.dp),
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = tint ?: MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Text(text, color = tint ?: MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
    }
}

/** 预览分发：图片→图片显示；文本→文本内容；音视频→播放（真实轨道探测，防内容与扩展名不符误判） */
@Composable
private fun FilePreviewDialog(file: File, onDismiss: () -> Unit) {
    when (fileKind(file)) {
        "image" -> ImagePreviewDialog(file, onDismiss)
        "text" -> TextPreviewDialog(file, onDismiss)
        else -> VideoPlayerDialog(file, onDismiss)
    }
}

/** 图片预览（gif/jpg/png/webp/bmp 等，不走 VideoView，避免“无法播放视频”卡死） */
@Composable
private fun ImagePreviewDialog(file: File, onDismiss: () -> Unit) {
    val bmp = remember(file) {
        runCatching {
            // 采样解码：大图先读尺寸，按最大边 2048 降采样，防 OOM
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            val maxDim = 2048
            while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) sample *= 2
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Text("无法显示图片", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

/** 文本预览（txt/log/md/json 等；超大文件只读前 64KB 防卡死） */
@Composable
private fun TextPreviewDialog(file: File, onDismiss: () -> Unit) {
    val content = remember(file) {
        runCatching {
            val maxBytes = 64 * 1024
            if (file.length() > maxBytes) {
                // 流式读取前 64KB（readBytes 整读大文件会 OOM）
                val buf = ByteArray(maxBytes)
                val n = java.io.RandomAccessFile(file, "r").use { it.read(buf) }
                String(buf, 0, n.coerceAtLeast(0), Charsets.UTF_8) + "\n\n…（文件过大，仅显示前 64KB）"
            } else {
                file.readText(Charsets.UTF_8)
            }
        }.getOrElse { "无法读取文件：${it.message}" }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Text(
                content,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

/** 产出文件预览：视频/音频播放 + 播放/暂停 + 进度条 */
@Composable
private fun VideoPlayerDialog(file: File, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var vv by remember { mutableStateOf<VideoView?>(null) }
    var mp by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(true) }
    var position by remember { mutableIntStateOf(0) }
    var duration by remember { mutableIntStateOf(0) }
    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    // 音频文件不显示黑屏视频区域；用 MediaPlayer（VideoView 对纯音频 prepared 不触发，进度条永不显示）
    val audioExts = setOf("mp3", "m4a", "wav", "flac", "ogg", "aac")
    val isAudio = file.extension.lowercase() in audioExts

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                if (!isAudio) {
                    AndroidView(
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                setVideoURI(uri)
                                start()
                                setOnCompletionListener { it.seekTo(0) }
                            }.also { vv = it }
                        },
                        modifier = Modifier.fillMaxWidth().height(240.dp).background(Color.Black)
                    )
                } else {
                    // 音频：MediaPlayer（setOnPreparedListener 触发后能读到 duration）
                    Box(
                        Modifier.fillMaxWidth().height(110.dp).background(Color(0xFF1E1F24)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(56.dp))
                        Text("音频预览", color = Color.White.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp))
                    }
                    DisposableEffect(Unit) {
                        val player = MediaPlayer().apply {
                            setDataSource(context, uri)
                            setOnPreparedListener { it ->
                                duration = it.duration
                                if (playing) it.start()
                            }
                            setOnErrorListener { _, _, _ ->
                                playing = false
                                true
                            }
                            setOnCompletionListener { playing = false }
                            prepareAsync()
                        }
                        mp = player
                        onDispose { runCatching { player.release() } }
                    }
                }
                // 轮询进度：兼容 vv(视频) 和 mp(音频)
                LaunchedEffect(vv, mp) {
                    while (true) {
                        delay(100)
                        val v = vv
                        val m = mp
                        if (v != null) {
                            position = v.currentPosition
                            if (v.duration > 0) duration = v.duration
                            if (!v.isPlaying && playing) v.start()
                        } else if (m != null) {
                            runCatching { position = m.currentPosition }
                            runCatching { if (m.duration > 0) duration = m.duration }
                            runCatching { if (!m.isPlaying && playing) m.start() }
                        }
                    }
                }
                LaunchedEffect(playing, vv, mp) {
                    vv?.let { if (playing) it.start() else it.pause() }
                    if (mp != null) runCatching { if (playing) mp?.start() else mp?.pause() }
                }
                DisposableEffect(Unit) {
                    onDispose {
                        vv?.stopPlayback()
                        runCatching { mp?.release() }
                    }
                }
                if (duration > 0) {
                    Slider(
                        value = position.toFloat().coerceIn(0f, duration.toFloat()),
                        onValueChange = {
                            vv?.seekTo(it.toInt())
                            runCatching { mp?.seekTo(it.toInt()) }
                        },
                        valueRange = 0f..duration.toFloat(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = {
                        playing = !playing
                        vv?.let { if (playing) it.start() else it.pause() }
                        runCatching { if (playing) mp?.start() else mp?.pause() }
                    }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                        Text(if (playing) "暂停" else "播放", style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        String.format(Locale.US, "%02d:%02d", position / 60000, (position / 1000) % 60),
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun RenameDialog(file: File, onDismiss: () -> Unit, onRenamed: () -> Unit) {
    var name by remember { mutableStateOf(file.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val newName = name.trim()
                if (newName.isNotEmpty() && newName != file.name) {
                    val target = File(file.parentFile, newName)
                    if (!target.exists()) {
                        file.renameTo(target)
                        onRenamed()
                    }
                }
                onDismiss()
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
