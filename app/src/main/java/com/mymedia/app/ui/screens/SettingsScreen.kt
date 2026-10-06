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

@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.mymedia.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import com.mymedia.app.ui.theme.AppPrefs
import com.mymedia.app.ui.theme.ThemeMode
import com.mymedia.app.utils.OutputStore
import com.mymedia.app.utils.TaskManager
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit
) {
    val context = LocalContext.current
    // 动态读取当前版本号（不再硬编码）
    val versionName = remember(context) {
        try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (_: Throwable) { "?" }
    }
    val versionCode = remember(context) {
        try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        } catch (_: Throwable) { 0 }
    }
    // 并发数恢复可调（0 = 无限制）
    var concurrent by remember { mutableIntStateOf(AppPrefs.getConcurrent(context)) }
    TaskManager.concurrentLimit = if (concurrent <= 0) Int.MAX_VALUE else concurrent
    // 输出目录：授权 URI + 目录显示名
    var outUri by remember { mutableStateOf(OutputStore.getTreeUri(context)) }
    var outName by remember { mutableStateOf(OutputStore.displayName(context)) }
    val dirPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                OutputStore.setTreeUri(context, uri)
                outUri = uri
                outName = OutputStore.displayName(context)
            } catch (e: Exception) {
                // 部分 ROM 不返回持久授权，仅本次有效
            }
        }
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).displayCutoutPadding()
    ) {
        Text("设置", style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp))

        Column(
            Modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // 主题
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Star, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("外观", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(8.dp))
                    ThemeMode.entries.forEach { mode ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                AppPrefs.setThemeMode(context, mode)
                                onThemeChange(mode)
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = themeMode == mode,
                                onClick = {
                                    AppPrefs.setThemeMode(context, mode)
                                    onThemeChange(mode)
                                }
                            )
                            Text(mode.label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // 并发数（恢复可调，用 FilterChip 选择，避免输入框"1改5变15"的 bug）
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("同时处理任务数", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        listOf(1, 2, 3, 5, 0).forEach { n ->
                            FilterChip(
                                selected = concurrent == n,
                                onClick = {
                                    concurrent = n
                                    AppPrefs.setConcurrent(context, n)
                                    TaskManager.concurrentLimit = if (n <= 0) Int.MAX_VALUE else n
                                },
                                label = { Text(if (n == 0) "无限制" else "$n") }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // 输出位置
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Check, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("输出位置", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (outName != null) "保存到：${outName}（文件管理器可见）" else "默认：app 私有目录（文件管理器隐藏）",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { dirPicker.launch(null) },
                            modifier = Modifier.weight(1f)
                        ) { Text("选择目录") }
                        if (outUri != null) {
                            OutlinedButton(
                                onClick = {
                                    OutputStore.setTreeUri(context, null)
                                    outUri = null
                                    outName = null
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("恢复默认") }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // 关于
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Info, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("关于", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(8.dp))
                    // 版本号 + versionCode 一起显示
                    Text("我的多媒体 v$versionName（$versionCode）", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
