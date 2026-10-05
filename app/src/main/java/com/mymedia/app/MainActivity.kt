@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.mymedia.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mymedia.app.ui.screens.ToolEntry
import com.mymedia.app.ui.screens.ToolList
import com.mymedia.app.model.TaskType
import com.mymedia.app.ui.screens.*
import com.mymedia.app.ui.theme.AppPrefs
import com.mymedia.app.ui.theme.MyMediaTheme
import com.mymedia.app.ui.theme.ThemeMode
import com.mymedia.app.utils.MediaSeparator
import com.mymedia.app.utils.OutputStore
import com.mymedia.app.utils.TaskManager
import java.io.File

enum class MainTab(
    val label: String,
    val icon: ImageVector,          // 未选中（描边）
    val selectedIcon: ImageVector   // 选中（实心）
) {
    HOME("主页", Icons.Outlined.Home, Icons.Filled.Home),
    TASKS("任务", Icons.AutoMirrored.Outlined.List, Icons.AutoMirrored.Filled.List),
    FILES("文件", Icons.Outlined.Folder, Icons.Filled.Folder),
    SETTINGS("设置", Icons.Outlined.Settings, Icons.Filled.Settings)
}

class MainActivity : ComponentActivity() {
    /** 是否已授权输出目录（驱动「引导页 ↔ 主界面」切换） */
    private val dirGranted = mutableStateOf(false)

    // SAF 选目录（首次启动由引导页触发，引导选 Download/我的多媒体）
    private val dirPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                OutputStore.setTreeUri(this, uri)
                dirGranted.value = true
                Toast.makeText(this, "已设置输出目录：${uri.lastPathSegment}", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "选目录失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashHandler()
        TaskManager.init(this)
        enableEdgeToEdge()
        dirGranted.value = OutputStore.getTreeUri(this) != null
        // 未授权输出目录时，先显示引导页（而不是直接弹系统文件夹选择器）
        setContent {
            var themeMode by remember { mutableStateOf(AppPrefs.getThemeMode(this)) }
            MyMediaTheme(themeMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (!dirGranted.value) {
                        WelcomeScreen(onPickDir = { dirPickerLauncher.launch(null) })
                    } else {
                        AppRoot(
                            themeMode = themeMode,
                            onThemeChange = { themeMode = it }
                        )
                    }
                }
            }
        }
    }

    /**
     * 全局崩溃处理器：任何未捕获异常 → 写 crash.txt（输出目录 + 内部目录双写）。
     * 用于定位 MKV 闪退等 JNI 崩溃（配合 C 层日志定位 FFmpeg 错误码）。
     */
    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sb = buildString {
                    append("time=").append(System.currentTimeMillis()).append('\n')
                    append("thread=").append(t.name).append('\n')
                    append(Log.getStackTraceString(e))
                }
                val targets = listOf(
                    File(MediaSeparator.getOutputDir(), "crash.txt"),
                    File(filesDir, "crash.txt")
                )
                targets.forEach { f ->
                    runCatching {
                        f.parentFile?.mkdirs()
                        f.writeText(sb.toString())
                    }
                }
            }
            prev?.uncaughtException(t, e)
        }
    }
}

@Composable
fun AppRoot(
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit
) {
    var tab by remember { mutableStateOf(MainTab.HOME) }
    var toolConfig by remember { mutableStateOf<TaskType?>(null) }

    // 工具配置页打开时，系统返回键 = 关闭配置页回主页
    androidx.activity.compose.BackHandler(enabled = toolConfig != null) {
        toolConfig = null
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp      // 宽屏 / 横屏 / 平板 → 微信式左右分栏
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                // ── 左侧栏：工具列表 + 底部导航 ──
                SidePanel(
                    tab = tab,
                    selectedTool = toolConfig,
                    onTabChange = {
                        tab = it
                        // 切换到任务/文件/设置时必须退出工具配置页，避免右栏残留旧配置。
                        if (it != MainTab.HOME) toolConfig = null
                    },
                    onToolClick = {
                        tab = MainTab.HOME
                        toolConfig = it
                    },
                    modifier = Modifier.width(320.dp).fillMaxHeight()
                )
                // 分隔线
                Box(
                    Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
                // ── 右侧内容区（宽屏无 Scaffold，需自行避让状态栏/刘海/导航栏）──
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                ) {
                    val current = toolConfig
                    when {
                        tab == MainTab.HOME && current != null -> key(current) {
                            ToolConfigScreen(
                                type = current,
                                onBack = { toolConfig = null },
                                onSubmitted = { toolConfig = null; tab = MainTab.TASKS }
                            )
                        }
                        tab == MainTab.HOME -> EmptyDetail()
                        tab == MainTab.TASKS -> TasksScreen()
                        tab == MainTab.FILES -> FilesScreen()
                        else -> SettingsScreen(themeMode, onThemeChange)
                    }
                }
            }
        } else {
            // ── 手机：底部导航 + 全屏内容 ──
            Scaffold(
                bottomBar = {
                    if (toolConfig == null) {
                        NavigationBar {
                            MainTab.entries.forEach { t ->
                                NavigationBarItem(
                                    selected = tab == t,
                                    onClick = {
                                        tab = t
                                        if (t != MainTab.HOME) toolConfig = null
                                    },
                                    icon = { Icon(if (tab == t) t.selectedIcon else t.icon, t.label) },
                                    label = { Text(t.label) }
                                )
                            }
                        }
                    }
                }
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    val current = toolConfig
                    if (current != null) {
                        key(current) {
                            ToolConfigScreen(
                                type = current,
                                onBack = { toolConfig = null },
                                onSubmitted = { toolConfig = null; tab = MainTab.TASKS }
                            )
                        }
                    } else {
                        when (tab) {
                            MainTab.HOME -> HomeScreen(onToolClick = { toolConfig = it })
                            MainTab.TASKS -> TasksScreen()
                            MainTab.FILES -> FilesScreen()
                            MainTab.SETTINGS -> SettingsScreen(themeMode, onThemeChange)
                        }
                    }
                }
            }
        }
    }
}

/** 宽屏左栏：标题 + 工具列表 + 底部导航（微信 / 钉钉式） */
@Composable
private fun SidePanel(
    tab: MainTab,
    selectedTool: TaskType?,
    onTabChange: (MainTab) -> Unit,
    onToolClick: (TaskType) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        // 标题区
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "我的多媒体",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.weight(1f))
            Text(
                "工具箱",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 工具列表（可滚动）
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp)
        ) {
            ToolList.forEach { tool ->
                SideToolRow(
                    tool = tool,
                    selected = tab == MainTab.HOME && selectedTool == tool.type,
                    onClick = {
                        onTabChange(MainTab.HOME)
                        onToolClick(tool.type)
                    }
                )
                Spacer(Modifier.height(2.dp))
            }
            Spacer(Modifier.height(10.dp))
        }

        // 底部导航（微信式：图标 + 文字）
        Box(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                MainTab.entries.forEach { t ->
                    val active = tab == t
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onTabChange(t) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            if (active) t.selectedIcon else t.icon, t.label,
                            tint = if (active) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            t.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (active) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** 左栏中的单个工具行（列表式，仿会话列表） */
@Composable
private fun SideToolRow(tool: ToolEntry, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(Brush.linearGradient(listOf(tool.accent, tool.accent.copy(alpha = 0.72f)))),
            contentAlignment = Alignment.Center
        ) {
            Icon(tool.icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                tool.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Text(
                tool.desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 宽屏右栏未选中工具时的空状态（仿微信未打开会话） */
@Composable
private fun EmptyDetail() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.Movie, null,
                tint = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.size(72.dp)
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "从左侧选择一个工具开始",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "处理完成的文件会保存到你设置的输出目录",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}
