/*
 * 我的多媒体 - MyMediaApp
 * Copyright (C) 2026  谭水军
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

package com.mymedia.app.ui.screens

import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.media.MediaFormat
import android.net.Uri
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mymedia.app.model.TaskType
import com.mymedia.app.utils.MediaConverter
import com.mymedia.app.utils.Naming
import com.mymedia.app.utils.TaskManager
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * 工具配置页：选文件 + 参数 → 添加任务 → 跳转任务页
 * 规则：除拼接外单选（一次一个任务）；拼接多选（列表可删可排序）
 */
@Composable
fun ToolConfigScreen(type: TaskType, onBack: () -> Unit, onSubmitted: () -> Unit) {
    val context = LocalContext.current
    val entry = ToolList.first { it.type == type }
    var submitting by remember { mutableStateOf(false) }
    var submitError by remember { mutableStateOf<String?>(null) }

    var uris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var names by remember { mutableStateOf<List<String>>(emptyList()) }
    var fileType by remember { mutableStateOf("") }   // "video"/"audio"/"none"
    var hasAudio by remember { mutableStateOf(true) }  // 输入是否有音轨（视频分离后可能无音轨）
    val multi = type == TaskType.CONCAT

    // 参数状态
    var audioFmt by remember { mutableStateOf(MediaConverter.AudioFormat.MP3) }
    var videoFmt by remember { mutableStateOf(MediaConverter.VideoFormat.MP4) }
    var trimStart by remember { mutableStateOf("") }
    var trimEnd by remember { mutableStateOf("") }
    // 多区间裁剪：每项 (起点秒, 终点秒)，支持多个区间合并输出
    val trimRanges = remember { androidx.compose.runtime.mutableStateListOf("0" to "") }  // 默认全选区间（0 到结尾），TrimTimeline 直接显示色块+手柄
    // 当前媒体时长（秒）：选中文件后读取，用于 RangeSlider 图形化选区间
    var mediaDurationSec by remember { mutableStateOf(0f) }
    var gifFps by remember { mutableStateOf(15) }     // 默认 15fps
    var gifSize by remember { mutableStateOf(720) }    // GIF 画质（最长边像素，0=原画）
    var snapTime by remember { mutableStateOf("") }

    // 预览（裁剪/截图用）
    var previewMs by remember { mutableStateOf(0) }
    var previewPlaying by remember { mutableStateOf(true) }

    val singlePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val t = MediaConverter.probeType(context, uri)
            if (!typeAllows(type, t)) {
                submitError = typeRequireText(type)
                return@rememberLauncherForActivityResult
            }
            submitError = null
            uris = listOf(uri)
            names = listOf(getFileName(context, uri))
            fileType = t
            // 视频文件探测音轨（分离视频后无音轨，压缩/转码输出将无声）
            hasAudio = if (t == "video") MediaConverter.hasAudioTrack(context, uri) else true
        }
    }
    val multiPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { list ->
        if (list.isNotEmpty()) {
            // 逐个校验：拼接允许视频或音频，但同一任务内需一致
            val types = list.map { MediaConverter.probeType(context, it) }
            if (types.any { it == "none" }) {
                submitError = "存在无法识别的文件，请检查格式"
                return@rememberLauncherForActivityResult
            }
            if (types.distinct().size > 1) {
                submitError = "请勿混选视频和音频"
                return@rememberLauncherForActivityResult
            }
            // 追加校验：与列表中已有文件的类型保持一致
            val newType = types.first()
            if (fileType.isNotEmpty() && fileType != newType) {
                submitError = "请勿混选视频和音频"
                return@rememberLauncherForActivityResult
            }
            // 追加到列表（拼接支持分批添加；允许同一文件重复添加，
            // 用于“同一个片段重复拼接 N 遍”的循环拼接场景）
            submitError = null
            uris = uris + list
            names = names + list.map { getFileName(context, it) }
            fileType = newType
        }
    }

    // 选中文件后读取媒体时长（TRIM 用，RangeSlider 范围）
    LaunchedEffect(uris) {
        if (type == TaskType.TRIM && uris.isNotEmpty()) {
            mediaDurationSec = runCatching {
                context.contentResolver.openFileDescriptor(uris[0], "r")?.use { pfd ->
                    val ex = MediaExtractor()
                    try {
                        ex.setDataSource(pfd.fileDescriptor)
                        // 取所有轨道的最大有效时长，避免首个轨道没有 duration 导致时间轴范围为 0。
                        var durUs = 0L
                        for (i in 0 until ex.trackCount) {
                            val fmt = ex.getTrackFormat(i)
                            durUs = maxOf(durUs, fmt.getLong(MediaFormat.KEY_DURATION, 0L))
                        }
                        durUs.toFloat() / 1_000_000f
                    } finally {
                        ex.release()
                    }
                } ?: 0f
            }.getOrDefault(0f)
        }
    }

    fun pick() {
        when (type) {
            TaskType.AUDIO_CONVERT -> if (multi) multiPicker.launch(arrayOf("audio/*", "video/*", "application/ogg"))
                else singlePicker.launch(arrayOf("audio/*", "video/*", "application/ogg"))
            TaskType.TRIM -> if (multi) multiPicker.launch(arrayOf("video/*", "audio/*", "application/ogg"))
                else singlePicker.launch(arrayOf("video/*", "audio/*", "application/ogg"))
            TaskType.CONCAT -> multiPicker.launch(arrayOf("video/*", "audio/*", "application/ogg"))
            else -> if (multi) multiPicker.launch(arrayOf("video/*"))
                else singlePicker.launch(arrayOf("video/*"))
        }
    }

    // 拼接列表操作
    fun moveUp(i: Int) {
        if (i <= 0) return
        val u = uris.toMutableList(); val n = names.toMutableList()
        u[i] = u[i - 1].also { u[i - 1] = u[i] }; n[i] = n[i - 1].also { n[i - 1] = n[i] }
        uris = u; names = n
    }
    fun moveDown(i: Int) {
        if (i >= uris.size - 1) return
        moveUp(i + 1)
    }
    fun removeAt(i: Int) {
        val u = uris.toMutableList(); val n = names.toMutableList()
        u.removeAt(i); n.removeAt(i)
        uris = u; names = n
    }

    fun submit() {
        if (uris.isEmpty()) return
        /* 音频提取/音频转换任务 + 无音轨视频 → 直接拦截（转 m4a 失败根因：
         * 分离视频无音轨，C 层 find_stream 返回 -1，用户看到裸错误码） */
        if ((type == TaskType.SEPARATE_AUDIO || type == TaskType.AUDIO_CONVERT) &&
            fileType == "video" && !hasAudio) {
            submitError = "该视频无音轨，无法提取/转换音频（请用原视频）"
            return
        }
        if (type == TaskType.CONCAT) {
            if (uris.size < 2) { submitError = "拼接至少需要 2 个文件"; return }
            TaskManager.submit(context, type, uris, Naming.concatName(names, if (fileType == "audio") "mp3" else "mp4"), emptyMap())
            onSubmitted()
            return
        }
        val name = names[0]
        val outputName: String
        val params = mutableMapOf<String, String>()
        when (type) {
            TaskType.SEPARATE_AUDIO ->
                outputName = Naming.outputNameFor(type, name, "", "mp3")
            TaskType.SEPARATE_VIDEO ->
                outputName = Naming.outputNameFor(type, name, "", "mp4")
            TaskType.AUDIO_CONVERT -> {
                params["codec"] = audioFmt.codec
                outputName = Naming.outputNameFor(type, name, audioFmt.label.substringBefore(' ').substringBefore('('), audioFmt.ext)
            }
            TaskType.VIDEO_CONVERT -> {
                params["codec"] = videoFmt.codec
                // GIF 输出：附带画质/帧率参数
                if (videoFmt == MediaConverter.VideoFormat.GIF) {
                    params["fps"] = gifFps.toString()
                    params["maxw"] = gifSize.toString()
                    params["maxh"] = gifSize.toString()
                }
                outputName = Naming.outputNameFor(type, name, videoFmt.label, videoFmt.ext)
            }
            TaskType.TRIM -> {
                // 多区间：先解析 → 排序 → 合并重叠区间（修复重叠导致的时间错乱）
                val ranges = trimRanges.mapNotNull { (s, e) ->
                    if (s.isBlank() && e.isBlank()) return@mapNotNull null
                    val ss = (s.toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0)
                    val to = (e.toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0)
                    if (to > 0 && to <= ss) return@mapNotNull null  // 无效区间（终点≤起点）丢弃
                    ss to to
                }.sortedBy { it.first }
                val merged = mutableListOf<Pair<Double, Double>>()
                for (r in ranges) {
                    if (merged.isEmpty()) { merged.add(r); continue }
                    val last = merged.last()
                    if (r.first <= last.second || last.second == 0.0) {
                        // 重叠/相邻：合并（终点取较大值；0=到结尾则保留 0）
                        merged[merged.size - 1] = last.first to
                                (if (last.second == 0.0 || r.second == 0.0) 0.0 else maxOf(last.second, r.second))
                    } else merged.add(r)
                }
                val parts = mutableListOf<String>()
                merged.forEach { (ss, to) ->
                    parts += "ss=${(ss * 1_000_000).toLong()}"
                    parts += "to=${(to * 1_000_000).toLong()}"
                }
                if (parts.isNotEmpty()) params["trim"] = parts.joinToString(";")
                // 音频输入输出 .m4a（AAC），视频输出 .mp4（修复音频被存成视频文件）
                val trimExt = if (fileType == "audio") "m4a" else "mp4"
                outputName = Naming.outputNameFor(type, name, "", trimExt)
            }
            TaskType.GIF -> {
                params["fps"] = gifFps.toString()
                /* 画质可选（360p/480p/720p/1080P/4K/原画）。maxw/maxh=0 表示不缩放（原分辨率）。
                 * 原画 GIF 是 256 色无损格式、体积很大，图库加载慢，请按需选择。 */
                params["maxw"] = gifSize.toString()
                params["maxh"] = gifSize.toString()
                outputName = Naming.outputNameFor(type, name, "", "gif")
            }
            TaskType.SNAPSHOT -> {
                params["timeUs"] = ((snapTime.toDoubleOrNull() ?: 0.0) * 1_000_000).toLong().toString()
                outputName = Naming.outputNameFor(type, name, "", "jpg")
            }
            else -> return
        }
        TaskManager.submit(context, type, listOf(uris[0]), outputName, params)
        onSubmitted()
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
    ) {
        TopBar(entry.title, onBack)

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 选文件卡片
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(entry.icon, null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    if (names.isEmpty()) {
                        Text(
                            when (type) {
                                TaskType.CONCAT -> "选择多个视频或音频（按顺序拼接）"
                                TaskType.TRIM -> "选择视频或音频"
                                TaskType.AUDIO_CONVERT -> "选择音频（或视频）"
                                else -> entry.desc
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                    } else {
                        // 已选文件：显示文件名（单选显示 1 个，拼接由下方列表展示）
                        if (type != TaskType.CONCAT) {
                            Column(Modifier.fillMaxWidth()) {
                                names.forEachIndexed { idx, n ->
                                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(n, style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (idx == 0) {
                                            Spacer(Modifier.weight(1f))
                                            Text(fileType, style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.outline)
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { pick() }, modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)) {
                        Icon(Icons.Filled.Create, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when {
                                names.isEmpty() -> "选择文件"
                                type == TaskType.CONCAT -> "添加文件（可多次追加）"
                                else -> "重新选择"
                            }
                        )
                    }
                }
            }

            // 拼接列表（可删除/排序）
            if (type == TaskType.CONCAT && names.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text("拼接顺序（${names.size} 个）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        names.forEachIndexed { i, n ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${i + 1}.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                                Spacer(Modifier.width(6.dp))
                                Text(n, style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f), maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                                IconButton(onClick = { moveUp(i) }, enabled = i > 0, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Filled.KeyboardArrowUp, "上移", tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(16.dp))
                                }
                                IconButton(onClick = { moveDown(i) }, enabled = i < names.size - 1, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Filled.KeyboardArrowDown, "下移", tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(16.dp))
                                }
                                IconButton(onClick = { removeAt(i) }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Filled.Close, "删除", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }

            // 参数区（按类型）
            if (names.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                when (type) {
                    TaskType.AUDIO_CONVERT -> FormatSelector(
                        "目标格式",
                        MediaConverter.AudioFormat.entries.map { it.label },
                        MediaConverter.AudioFormat.entries.indexOf(audioFmt),
                        onSelect = { audioFmt = MediaConverter.AudioFormat.entries[it] }
                    )

                    TaskType.VIDEO_CONVERT -> {
                        Column(Modifier.fillMaxWidth()) {
                            FormatSelector(
                                "目标格式",
                                MediaConverter.VideoFormat.entries.map { it.label },
                                MediaConverter.VideoFormat.entries.indexOf(videoFmt),
                                onSelect = { videoFmt = MediaConverter.VideoFormat.entries[it] }
                            )
                            // 选 GIF 输出时，额外显示画质 / 帧率
                            if (videoFmt == MediaConverter.VideoFormat.GIF) {
                                Spacer(Modifier.height(12.dp))
                                FormatSelector(
                                    "画质（最长边）",
                                    listOf("360p", "480p", "720p", "1080P", "4K", "原画"),
                                    listOf(360, 480, 720, 1080, 2160, 0).indexOf(gifSize).coerceAtLeast(0),
                                    onSelect = { gifSize = listOf(360, 480, 720, 1080, 2160, 0)[it] }
                                )
                                Spacer(Modifier.height(12.dp))
                                FormatSelector(
                                    "帧率",
                                    listOf("10 fps", "15 fps", "20 fps", "25 fps", "30 fps", "60 fps"),
                                    listOf(10, 15, 20, 25, 30, 60).indexOf(gifFps).coerceAtLeast(0),
                                    onSelect = { gifFps = listOf(10, 15, 20, 25, 30, 60)[it] }
                                )
                            }
                        }
                    }

                    TaskType.TRIM -> {
                        // 视频/音频均可预览（音频为黑屏播放，时间可读）
                        PreviewPanel(
                            uris[0], previewMs, previewPlaying,
                            { previewMs = it }, { previewPlaying = it },
                            ranges = trimRanges.mapNotNull { (s, e) ->
                                if (s.isBlank() && e.isBlank()) null
                                else (s.toDoubleOrNull() ?: 0.0).toFloat() to (e.toDoubleOrNull() ?: 0.0).toFloat()
                            },
                            onRangesChange = { newRanges ->
                                // 时间轴手柄拖动回写到 trimRanges（String 格式）
                                trimRanges.clear()
                                newRanges.forEach { (s, e) ->
                                    trimRanges.add(
                                        String.format(Locale.US, "%.2f", s) to
                                                String.format(Locale.US, "%.2f", e)
                                    )
                                }
                            },
                            onRangeClick = { (s, e) ->
                                // 点击时间轴区间 → 把数字填进第一个区间输入框
                                if (trimRanges.isNotEmpty()) {
                                    trimRanges[0] = String.format(Locale.US, "%.2f", s) to
                                            String.format(Locale.US, "%.2f", e)
                                }
                            }
                        )
                        Spacer(Modifier.height(8.dp))
                        // 裁剪区间列表：支持多区间合并输出（RangeSlider 图形化 + 数字输入双模式）
                        trimRanges.forEachIndexed { i, range ->
                            if (mediaDurationSec > 0f) {
                                // 图形化区间：拖动双滑块选择（value 与输入框双向同步）
                                val s = (range.first.toDoubleOrNull() ?: 0.0).toFloat()
                                    .coerceIn(0f, mediaDurationSec)
                                val e = (range.second.toDoubleOrNull() ?: mediaDurationSec.toDouble()).toFloat()
                                    .coerceIn(s, mediaDurationSec)
                                RangeSlider(
                                    value = s..e,
                                    onValueChange = { r ->
                                        trimRanges[i] = String.format(Locale.US, "%.2f", r.start) to
                                                String.format(Locale.US, "%.2f", r.endInclusive)
                                    },
                                    valueRange = 0f..mediaDurationSec,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    "区间 ${i + 1}：${String.format(Locale.US, "%.1f", s)}s ~ ${String.format(Locale.US, "%.1f", e)}s（总长 ${String.format(Locale.US, "%.1f", mediaDurationSec)}s）",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("${i + 1}", style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.outline, modifier = Modifier.width(18.dp))
                                OutlinedTextField(
                                    value = range.first,
                                    onValueChange = { trimRanges[i] = it.filter { c -> c.isDigit() || c == '.' } to range.second },
                                    placeholder = { Text("起点(秒)") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    textStyle = MaterialTheme.typography.bodySmall
                                )
                                Spacer(Modifier.width(6.dp))
                                OutlinedTextField(
                                    value = range.second,
                                    onValueChange = { trimRanges[i] = range.first to it.filter { c -> c.isDigit() || c == '.' } },
                                    placeholder = { Text("终点(秒)") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    textStyle = MaterialTheme.typography.bodySmall
                                )
                                Spacer(Modifier.width(4.dp))
                                IconButton(onClick = { if (trimRanges.size > 1) trimRanges.removeAt(i) }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Filled.Close, "删除区间", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { if (trimRanges.size < 8) trimRanges.add("" to "") }, modifier = Modifier.weight(1f)) {
                                Text("添加区间", style = MaterialTheme.typography.labelMedium)
                            }
                            OutlinedButton(onClick = {
                                val i = trimRanges.lastIndex
                                trimRanges[i] = String.format(Locale.US, "%.2f", previewMs / 1000.0) to trimRanges[i].second
                            }, modifier = Modifier.weight(1f)) {
                                Text("设为起点", style = MaterialTheme.typography.labelMedium)
                            }
                            OutlinedButton(onClick = {
                                val i = trimRanges.lastIndex
                                trimRanges[i] = trimRanges[i].first to String.format(Locale.US, "%.2f", previewMs / 1000.0)
                            }, modifier = Modifier.weight(1f)) {
                                Text("设为终点", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("支持多个区间，合并输出为一个文件；终点留空=到结尾",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }

                    TaskType.GIF -> {
                        Column(Modifier.fillMaxWidth()) {
                            FormatSelector(
                                "画质（最长边）",
                                listOf("360p", "480p", "720p", "1080P", "4K", "原画"),
                                listOf(360, 480, 720, 1080, 2160, 0).indexOf(gifSize).coerceAtLeast(0),
                                onSelect = { gifSize = listOf(360, 480, 720, 1080, 2160, 0)[it] }
                            )
                            Spacer(Modifier.height(12.dp))
                            FormatSelector(
                                "帧率",
                                listOf("10 fps", "15 fps", "20 fps", "25 fps", "30 fps", "60 fps"),
                                listOf(10, 15, 20, 25, 30, 60).indexOf(gifFps).coerceAtLeast(0),
                                onSelect = { gifFps = listOf(10, 15, 20, 25, 30, 60)[it] }
                            )
                        }
                    }

                    TaskType.SNAPSHOT -> {
                        if (fileType == "video") {
                            PreviewPanel(uris[0], previewMs, previewPlaying, { previewMs = it }, { previewPlaying = it })
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    snapTime = String.format(Locale.US, "%.2f", previewMs / 1000.0)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("截取当前帧（${String.format(Locale.US, "%.2f", previewMs / 1000.0)}s）", style = MaterialTheme.typography.labelMedium) }
                            Spacer(Modifier.height(8.dp))
                        }
                        TimeInput("截图时间点（秒，可精确到 0.01）", snapTime) { snapTime = it }
                    }

                    else -> {}
                }
            }

            Spacer(Modifier.height(20.dp))

            if (names.isNotEmpty()) {
                submitError?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = {
                        submitting = true
                        submit()
                        // 校验未通过时 submit() 会提前 return（不跳转），必须复位，
                        // 否则按钮会永久停在灰色禁用态（enabled = !submitting）
                        submitting = false
                    },
                    enabled = !submitting,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Filled.Add, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("添加任务")
                }
                Spacer(Modifier.height(4.dp))
                Text("添加后自动前往「任务」页，可暂停/取消",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

/** 该工具允许的媒体类型 */
private fun typeAllows(type: TaskType, t: String): Boolean = when (type) {
    TaskType.SEPARATE_AUDIO, TaskType.SEPARATE_VIDEO, TaskType.VIDEO_CONVERT,
    TaskType.GIF, TaskType.SNAPSHOT -> t == "video"
    TaskType.AUDIO_CONVERT -> t == "video" || t == "audio"
    TaskType.TRIM, TaskType.CONCAT -> t == "video" || t == "audio"
}

private fun typeRequireText(type: TaskType): String = when (type) {
    TaskType.SEPARATE_AUDIO, TaskType.SEPARATE_VIDEO, TaskType.VIDEO_CONVERT,
    TaskType.GIF, TaskType.SNAPSHOT -> "该工具需要视频文件，请重新选择"
    TaskType.AUDIO_CONVERT -> "该文件不是有效的音频/视频"
    else -> "该文件不是有效的音视频文件"
}

/** 时间轴：区间色块 + 手柄拖动（左右/整体）+ 进度线 + 点击 seek（参考 HTML 音频编辑器） */
@Composable
private fun TrimTimeline(
    durationMs: Int,
    positionMs: Int,
    ranges: List<Pair<Float, Float>>,   // 裁剪区间（秒）
    onSeek: (Int) -> Unit,
    onRangesChange: (List<Pair<Float, Float>>) -> Unit = {},
    onRangeClick: (Pair<Float, Float>) -> Unit = {},   // 点击区间回写数字到输入框
    modifier: Modifier = Modifier
) {
    val barH = 10.dp
    val radius = 6.dp
    val handleW = 10.dp
    // DrawScope 内不能调用 Composable，颜色在 Composable 作用域提前取
    val trackBg = MaterialTheme.colorScheme.surfaceVariant
    val trackPrimary = MaterialTheme.colorScheme.primary
    // 拖动状态：0=无 1=seek 2=左 4=整体 3=右
    var dragMode by remember { mutableStateOf(0) }
    var dragIdx by remember { mutableStateOf(-1) }
    var dragStartX by remember { mutableStateOf(0f) }
    var dragStartRanges by remember { mutableStateOf(listOf<Pair<Float, Float>>()) }
    // 手势协程的 key 必须稳定：拖动过程中 ranges 会随 onRangesChange 每帧变化，
    // 若把 ranges 放进 pointerInput 的 key，变化一次就会取消并重启手势协程，
    // 表现就是「只能拖一点点、不跟手」。改用 rememberUpdatedState 在协程内读最新值。
    val curDurationMs by rememberUpdatedState(durationMs)
    val curRanges by rememberUpdatedState(ranges)
    val curOnSeek by rememberUpdatedState(onSeek)
    val curOnRangesChange by rememberUpdatedState(onRangesChange)
    val curOnRangeClick by rememberUpdatedState(onRangeClick)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .pointerInput(Unit) {
                val handleWPx = handleW.toPx()
                // 不缓存 width：PointerInputScope.size 每次读取都是当前布局尺寸，
                // 缓存后横竖屏切换会导致时间轴坐标错位
                fun secToX(s: Float): Float {
                    val d = curDurationMs / 1000f
                    return if (d > 0f) (s / d * size.width).coerceIn(0f, size.width.toFloat()) else 0f
                }
                // hit test：返回 (mode, idx)；mode 1=seek, 2=左, 3=右, 4=整体
                fun hit(x: Float): Pair<Int, Int> {
                    curRanges.forEachIndexed { i, (s, e) ->
                        val sx = secToX(s)
                        val ex = secToX(if (e > 0f) e else curDurationMs / 1000f)
                        if (ex - sx <= handleWPx * 2) {
                            if (x >= sx - handleWPx && x <= ex + handleWPx) return 2 to i
                        } else {
                            if (kotlin.math.abs(x - sx) <= handleWPx) return 2 to i
                            if (kotlin.math.abs(x - ex) <= handleWPx) return 3 to i
                            if (x > sx + handleWPx && x < ex - handleWPx) return 4 to i
                        }
                    }
                    return 1 to -1
                }
                detectHorizontalDragGestures(
                    onDragStart = { off ->
                        if (curDurationMs <= 0) return@detectHorizontalDragGestures
                        val (m, i) = hit(off.x)
                        if (m == 1) {
                            // 空白：seek（必须置 dragMode=1，否则 onDrag 里的 dragMode==1 分支永不成立，
                            // 表现就是「按下跳一下、之后不跟手」）
                            curOnSeek((off.x / size.width * curDurationMs).toInt().coerceIn(0, curDurationMs))
                            dragMode = 1; dragIdx = -1
                        } else {
                            // 区间/手柄：单击回写数字 + 设置拖动状态
                            dragMode = m; dragIdx = i; dragStartX = off.x
                            dragStartRanges = curRanges.map { it.first to it.second }
                            // 只在拖动第 1 个区间时回填输入框；多区间时若按 dragIdx 回填会改写第 1 个区间的数字
                            if (i == 0) curOnRangeClick(curRanges[0])
                        }
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        if (curDurationMs <= 0) return@detectHorizontalDragGestures
                        val curDurSec = curDurationMs / 1000f
                        if (dragMode == 1 || dragIdx < 0) {
                            if (dragMode == 1)
                                curOnSeek((change.position.x / size.width * curDurationMs).toInt().coerceIn(0, curDurationMs))
                            return@detectHorizontalDragGestures
                        }
                        val dx = change.position.x - dragStartX
                        val ds = if (curDurSec > 0f) dx / size.width * curDurSec else 0f
                        when (dragMode) {
                            2 -> {
                                // 拖左端
                                val orig = dragStartRanges.getOrNull(dragIdx) ?: return@detectHorizontalDragGestures
                                val newS = (orig.first + ds).coerceIn(0f, orig.second - 0.05f)
                                val nl = dragStartRanges.toMutableList()
                                nl[dragIdx] = newS to orig.second
                                curOnRangesChange(nl)
                            }
                            3 -> {
                                // 拖右端
                                val orig = dragStartRanges.getOrNull(dragIdx) ?: return@detectHorizontalDragGestures
                                val newE = (orig.second + ds).coerceIn(orig.first + 0.05f, curDurSec)
                                val nl = dragStartRanges.toMutableList()
                                nl[dragIdx] = orig.first to newE
                                curOnRangesChange(nl)
                            }
                            4 -> {
                                // 整体平移
                                val orig = dragStartRanges.getOrNull(dragIdx) ?: return@detectHorizontalDragGestures
                                val len = orig.second - orig.first
                                val ns = (orig.first + ds).coerceIn(0f, curDurSec - len)
                                val nl = dragStartRanges.toMutableList()
                                nl[dragIdx] = ns to (ns + len)
                                curOnRangesChange(nl)
                            }
                        }
                    },
                    onDragEnd = { dragMode = 0; dragIdx = -1 },
                    onDragCancel = { dragMode = 0; dragIdx = -1 }
                )
            }
    ) {
        // 轨道背景
        drawRoundRect(
            color = trackBg,
            topLeft = Offset(0f, (size.height - barH.toPx()) / 2),
            size = Size(size.width, barH.toPx()),
            cornerRadius = CornerRadius(radius.toPx())
        )
        // 区间色块 + 左右手柄竖条
        val durSec = if (durationMs > 0) durationMs / 1000f else 0f
        val handleWPx = handleW.toPx()
        val padV = 4.dp.toPx()
        if (durSec > 0f) ranges.forEach { (s, e) ->
            val sx = (s / durSec * size.width).coerceIn(0f, size.width)
            val ex = ((if (e > 0f) e else durSec) / durSec * size.width).coerceIn(0f, size.width)
            if (ex > sx) {
                val midY = (size.height - barH.toPx()) / 2
                drawRoundRect(
                    color = trackPrimary.copy(alpha = 0.45f),
                    topLeft = Offset(sx, midY),
                    size = Size(ex - sx, barH.toPx()),
                    cornerRadius = CornerRadius(radius.toPx())
                )
                drawRect(color = trackPrimary,
                    topLeft = Offset(sx - handleWPx / 2, midY - padV / 2),
                    size = Size(handleWPx, barH.toPx() + padV))
                drawRect(color = trackPrimary,
                    topLeft = Offset(ex - handleWPx / 2, midY - padV / 2),
                    size = Size(handleWPx, barH.toPx() + padV))
            }
        }
        // 播放进度线
        if (durationMs > 0) {
            val px = (positionMs.toFloat() / durationMs * size.width).coerceIn(0f, size.width)
            drawLine(color = trackPrimary, start = Offset(px, 0f),
                end = Offset(px, size.height), strokeWidth = 2.dp.toPx())
        }
    }
}

/** 视频预览面板：播放/暂停 + 进度条拖动 + 0.01s 时间显示（统一用 MediaPlayer） */
@Composable
private fun PreviewPanel(
    uri: Uri,
    currentMs: Int,
    playing: Boolean,
    onTime: (Int) -> Unit,
    onPlaying: (Boolean) -> Unit,
    ranges: List<Pair<Float, Float>> = emptyList(),
    onRangesChange: (List<Pair<Float, Float>>) -> Unit = {},
    onRangeClick: (Pair<Float, Float>) -> Unit = {},
    isAudio: Boolean = false
) {
    val context = LocalContext.current
    var mp by remember { mutableStateOf<MediaPlayer?>(null) }
    var tv by remember { mutableStateOf<TextureView?>(null) }
    var durationMs by remember { mutableIntStateOf(0) }
    /* Independent fallback for duration: on some devices/containers MediaPlayer
       fails to report duration, which hides the whole timeline (drag bar vanishes). */
    var retrieverDurationMs by remember(uri) { mutableIntStateOf(0) }
    var playerError by remember(uri) { mutableStateOf(false) }   // 该文件打不开（格式/权限）
    /* 视频显示宽高比（竖屏适配）。探测含旋转修正，0f=未获取到 */
    var aspect by remember(uri) { mutableStateOf(0f) }
    LaunchedEffect(uri) { aspect = if (isAudio) 0f else MediaConverter.probeVideoAspect(context, uri) }
    LaunchedEffect(uri) {
        retrieverDurationMs = runCatching {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(context, uri)
                (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L).toInt()
            } finally { runCatching { mmr.release() } }
        }.getOrDefault(0)
    }
    val currentPlaying by rememberUpdatedState(playing)

    /* 统一 MediaPlayer：视频/音频播放（音频无画面，用图标占位） */
    DisposableEffect(uri) {
        playerError = false
        durationMs = 0
        val player = MediaPlayer()
        /* setDataSource 可能抛（权限被收回 / 非法 URI）；抛出去会让整块界面崩掉，
         * 这里兜住并标记为「打不开」，让时间轴/按钮至少不消失得莫名其妙 */
        val ok = runCatching {
            player.setDataSource(context, uri)
            player.setOnPreparedListener { p ->
                durationMs = p.duration
                if (currentPlaying) try { p.start() } catch (_: Throwable) {}
            }
            player.setOnErrorListener { _, _, _ -> playerError = true; onPlaying(false); true }
            player.setOnCompletionListener { onPlaying(false) }
            player.prepareAsync()
        }.isSuccess
        if (ok) mp = player else { playerError = true; runCatching { player.release() } }
        onDispose { runCatching { player.release() } }
    }
    LaunchedEffect(tv, mp) {
        if (!isAudio && tv != null && mp != null) {
            try { mp?.setSurface(Surface(tv!!.surfaceTexture)) } catch (_: Throwable) {}
        }
    }
    LaunchedEffect(uri) {
        while (true) {
            delay(100)
            mp?.let {
                runCatching { onTime(it.currentPosition) }
                runCatching { if (it.duration > 0) durationMs = it.duration }
                runCatching { if (!it.isPlaying && currentPlaying) it.start() }
            }
        }
    }
    LaunchedEffect(playing, mp) {
        runCatching { if (playing) mp?.start() else mp?.pause() }
    }

    /* 视频区 TextureView 显示；音频：图标占位
     * 竖屏适配：视频按探测宽高比自适应高度（有比例用 aspectRatio，
     * 无比例回退 200dp，音频固定 110dp） */
    val maxH = 420
    val previewModifier =
        if (isAudio) Modifier.fillMaxWidth().height(110.dp)
        else if (aspect > 0f) Modifier.fillMaxWidth().aspectRatio(aspect).heightIn(max = maxH.dp)
        else Modifier.fillMaxWidth().height(200.dp)
    Box(previewModifier.clip(RoundedCornerShape(12.dp))
        .background(androidx.compose.ui.graphics.Color.Black), contentAlignment = Alignment.Center) {
        if (!isAudio) {
            AndroidView(factory = { ctx -> TextureView(ctx).also { tv = it } },
                modifier = Modifier.fillMaxSize())
            if (playerError) {
                Text(
                    "无法预览该文件（读取失败或格式不支持）",
                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        } else {
            Icon(Icons.Filled.PlayArrow, null, tint = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.size(56.dp))
        }
    }
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = {
            val target = !playing
            onPlaying(target)
            runCatching { if (target) mp?.start() else mp?.pause() }
        }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
            Text(if (playing) "暂停" else "播放", style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.width(10.dp))
        Text(
            String.format(Locale.US, "%02d:%02d.%02d", currentMs / 60000, (currentMs / 1000) % 60, (currentMs % 1000) / 10),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
    val timelineDurationMs = if (durationMs > 0) durationMs else retrieverDurationMs
    if (timelineDurationMs > 0) {
        TrimTimeline(
            durationMs = timelineDurationMs,
            positionMs = currentMs,
            ranges = ranges,
            onSeek = { ms -> runCatching { mp?.seekTo(ms) }; Unit },
            onRangesChange = onRangesChange,
            onRangeClick = onRangeClick
        )
    }
}

@Composable
private fun FormatSelector(title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            options.forEachIndexed { i, label ->
                Row(Modifier.fillMaxWidth().clickable { onSelect(i) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = i == selected, onClick = { onSelect(i) })
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun TimeInput(title: String, value: String, onChange: (String) -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = value,
                onValueChange = { onChange(it.filter { c -> c.isDigit() || c == '.' }) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
