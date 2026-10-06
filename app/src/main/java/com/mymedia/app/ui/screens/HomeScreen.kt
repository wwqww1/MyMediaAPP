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

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mymedia.app.model.TaskType
import com.mymedia.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 工具入口 */
data class ToolEntry(
    val type: TaskType,
    val icon: ImageVector,
    val title: String,
    val desc: String,
    val accent: Color
)

/** 图标语义与功能对齐；accent 为该工具主色 */
val ToolList = listOf(
    ToolEntry(TaskType.SEPARATE_AUDIO, Icons.Filled.MusicNote, "分离音频", "提取音轨为 MP3", Color(0xFF4F63D2)),
    ToolEntry(TaskType.SEPARATE_VIDEO, Icons.Filled.Movie, "分离视频", "提取纯视频轨道", Color(0xFF6B5BC9)),
    ToolEntry(TaskType.AUDIO_CONVERT, Icons.Filled.SwapHoriz, "音频转换", "MP3 / AAC / WAV / FLAC", Color(0xFF3D6FCB)),
    ToolEntry(TaskType.VIDEO_CONVERT, Icons.Filled.Autorenew, "视频转换", "H.264 / H.265 / GIF", Color(0xFF2E86B8)),
    ToolEntry(TaskType.TRIM, Icons.Filled.ContentCut, "时间裁剪", "截取视频 / 音频片段", Color(0xFF5A64C0)),
    ToolEntry(TaskType.CONCAT, Icons.Filled.CallMerge, "音视频拼接", "多段合并为一个文件", Color(0xFF2F8298)),
    ToolEntry(TaskType.SNAPSHOT, Icons.Filled.PhotoCamera, "视频截图", "抽取某一帧保存", Color(0xFF4A76AB))
)

/** 读取最近处理记录（与任务页共用 task_history） */
private fun loadRecentHistory(context: Context, limit: Int = 6): List<Pair<String, String>> {
    return runCatching {
        val prefs = context.getSharedPreferences("task_history", Context.MODE_PRIVATE)
        val arr = org.json.JSONArray(prefs.getString("history", "[]") ?: "[]")
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        buildList {
            for (i in arr.length() - 1 downTo 0) {
                if (size >= limit) break
                val o = arr.getJSONObject(i)
                val time = fmt.format(Date(o.optLong("time")))
                add(o.optString("output", "未知") to "$time · ${o.optString("state", "")}")
            }
        }
    }.getOrDefault(emptyList())
}

@Composable
fun HomeScreen(onToolClick: (TaskType) -> Unit) {
    val context = LocalContext.current
    val recent = remember { loadRecentHistory(context) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .displayCutoutPadding()
    ) {
        // 手机单栏（宽屏由 AppRoot 的左右分栏接管，这里只在窄屏显示）
        val gridColumns = if (maxWidth >= 600.dp) 3 else 2
        val maxContent = 620.dp
        val contentWidth = if (maxWidth > maxContent) maxContent else maxWidth

        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(contentWidth)
                .align(Alignment.TopCenter)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
        ) {
            Spacer(Modifier.height(14.dp))
            Text(
                "我的多媒体",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "音视频处理工具箱",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))

            ToolGrid(columns = gridColumns, onToolClick = onToolClick)
            if (recent.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                RecentPanel(recent)
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

/** 工具网格：按列数铺排，最后一列补齐占位 */
@Composable
private fun ToolGrid(columns: Int, onToolClick: (TaskType) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        ToolList.chunked(columns).forEach { rowItems ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                rowItems.forEach { tool ->
                    ToolCard(tool, Modifier.weight(1f)) { onToolClick(tool.type) }
                }
                repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** 最近任务面板（宽屏右栏 / 窄屏底部） */
@Composable
private fun RecentPanel(recent: List<Pair<String, String>>) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.History, null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "最近处理",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(10.dp))
            if (recent.isEmpty()) {
                Text(
                    "还没有处理记录。选一个上面的工具，处理完的文件会出现在这里。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                recent.forEachIndexed { i, (name, meta) ->
                    Text(
                        name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (i < recent.size - 1) Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

@Composable
private fun ToolCard(tool: ToolEntry, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Box(
                Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(tool.accent),
                contentAlignment = Alignment.Center
            ) {
                Icon(tool.icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text(
                tool.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                tool.desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
        }
    }
}
