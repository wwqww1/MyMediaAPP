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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mymedia.app.model.MediaTask
import com.mymedia.app.model.TaskState
import com.mymedia.app.utils.ShareUtil
import com.mymedia.app.utils.TaskManager
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TasksScreen() {
    val context = LocalContext.current
    val tasks = TaskManager.tasks

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).displayCutoutPadding()
    ) {
        Text("任务", style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp))

        // 任务列表为空时也显示历史记录（此前 tasks 空直接走"暂无任务"，历史被吞）
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            if (tasks.isEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.AutoMirrored.Filled.List, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(56.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("暂无进行中的任务", color = MaterialTheme.colorScheme.outline)
                        Text("去「主页」选择工具开始吧", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
            } else {
                items(tasks, key = { it.id }) { task ->
                    TaskCard(context, task)
                    Spacer(Modifier.height(10.dp))
                }
            }
            item { HistorySection(context) }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun TaskCard(context: Context, task: MediaTask) {
    val state = task.state.value
    val progress = task.progress.intValue

    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(task.type.label, style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                StateChip(state)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                task.outputName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            task.inputNames.take(3).forEach { n ->
                Text("← $n", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            if (state == TaskState.RUNNING || state == TaskState.PAUSED || state == TaskState.QUEUED || state == TaskState.PREPARING) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = {
                        if (state == TaskState.QUEUED || state == TaskState.PREPARING) 0f
                        else if (state == TaskState.PAUSED) progress / 100f
                        else progress / 100f
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    when (state) {
                        TaskState.QUEUED -> "排队中…"
                        TaskState.PREPARING -> "准备中…"
                        TaskState.PAUSED -> "已暂停 $progress%"
                        else -> "$progress%"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            if (state == TaskState.FAILED) {
                Spacer(Modifier.height(6.dp))
                Text(task.error.value ?: "处理失败", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state) {
                    TaskState.RUNNING -> {
                        SmallBtn("暂停") { TaskManager.pauseTask(task) }
                        SmallBtn("取消", danger = true) { TaskManager.cancelTask(task) }
                    }
                    TaskState.PAUSED -> {
                        SmallBtn("继续", Icons.Filled.PlayArrow) { TaskManager.resumeTask(task) }
                        SmallBtn("取消", Icons.Filled.Close, danger = true) { TaskManager.cancelTask(task) }
                    }
                    TaskState.QUEUED, TaskState.PREPARING -> {
                        SmallBtn("取消", Icons.Filled.Close, danger = true) { TaskManager.cancelTask(task) }
                    }
                    TaskState.DONE -> {
                        task.outputPath.value?.let { path ->
                            val f = File(path)
                            SmallBtn("分享", Icons.Filled.Share) { ShareUtil.shareFile(context, f) }
                            SmallBtn("移除", Icons.Filled.Delete, danger = true) { TaskManager.removeTask(task) }
                        }
                    }
                    TaskState.FAILED -> {
                        SmallBtn("移除", Icons.Filled.Delete, danger = true) { TaskManager.removeTask(task) }
                    }
                    // CANCELLED 也支持移除
                    TaskState.CANCELLED -> {
                        SmallBtn("移除", Icons.Filled.Delete, danger = true) { TaskManager.removeTask(task) }
                    }
                    else -> {}
                }
            }
        }
    }
}

@Composable
private fun StateChip(state: TaskState) {
    val (bg, fg) = when (state) {
        TaskState.QUEUED, TaskState.PREPARING -> Color(0xFFEDF0F4) to Color(0xFF5A6779)
        TaskState.RUNNING -> Color(0xFFE6EDF9) to Color(0xFF2F4FB5)
        TaskState.PAUSED -> Color(0xFFF6F0E2) to Color(0xFF8A6414)
        TaskState.DONE -> Color(0xFFE1EFE7) to Color(0xFF1F6B44)
        TaskState.FAILED -> Color(0xFFF7E8E8) to Color(0xFFA32B2B)
        TaskState.CANCELLED -> Color(0xFFECEEF1) to Color(0xFF5A6779)
    }
    Box(Modifier.background(bg, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(state.label, style = MaterialTheme.typography.labelSmall, color = fg)
    }
}

@Composable
private fun SmallBtn(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    OutlinedButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(14.dp),
                tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(4.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium,
            color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun HistorySection(context: Context) {
    // 历史刷新——任务列表变化或末任务状态变化时刷新；
    // 同时每 3 秒轮询一次（任务终态延迟 5 秒移除后历史需自动补显）
    val lastState = TaskManager.tasks.firstOrNull()?.state?.value
    var history by remember(TaskManager.tasks.size, lastState) { mutableStateOf(loadHistory(context)) }
    LaunchedEffect(TaskManager.tasks.size) {
        while (true) {
            delay(3000)
            history = loadHistory(context)
        }
    }

    if (history.isEmpty()) return
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("历史记录", style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        TextButton(onClick = {
            clearHistory(context)
            history = emptyList()
        }) { Text("清空", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error) }
    }
    Spacer(Modifier.height(6.dp))
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            history.forEachIndexed { i, h ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(h.first, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(h.second, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                    IconButton(onClick = {
                        deleteHistoryAt(context, i)
                        history = loadHistory(context)
                    }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Close, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(14.dp))
                    }
                }
                if (i < history.size - 1) {
                    HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

private fun loadHistory(context: Context): List<Pair<String, String>> {
    return runCatching {
        val prefs = context.getSharedPreferences("task_history", Context.MODE_PRIVATE)
        val arr = org.json.JSONArray(prefs.getString("history", "[]") ?: "[]")
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val time = fmt.format(Date(o.optLong("time")))
                add(o.optString("output", "未知") to "$time · ${o.optString("state", "")}")
            }
        }
    }.getOrDefault(emptyList())
}

private fun deleteHistoryAt(context: Context, index: Int) {
    val prefs = context.getSharedPreferences("task_history", Context.MODE_PRIVATE)
    val arr = org.json.JSONArray(prefs.getString("history", "[]") ?: "[]")
    if (index in 0 until arr.length()) arr.remove(index)
    prefs.edit().putString("history", arr.toString()).apply()
}

private fun clearHistory(context: Context) {
    context.getSharedPreferences("task_history", Context.MODE_PRIVATE)
        .edit().remove("history").apply()
}
