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

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.mymedia.app.R

/**
 * 首次启动引导页：在申请输出目录权限前，先告诉用户「为什么需要选目录、选哪个」，
 * 避免一进 App 直接弹系统文件夹选择器让人不知所措。
 */
@Composable
fun WelcomeScreen(onPickDir: () -> Unit) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        val wide = maxWidth > 600.dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(Modifier.height(if (wide) 24.dp else 8.dp))

            // App 图标（直接复用应用自身的启动图标，不另外画一个）
            val appContext = LocalContext.current
            val appIcon = remember {
                runCatching {
                    ContextCompat.getDrawable(appContext, R.mipmap.ic_launcher)
                        ?.toBitmap(240, 240)
                        ?.asImageBitmap()
                }.getOrNull()
            }
            if (appIcon != null) {
                Image(
                    bitmap = appIcon,
                    contentDescription = null,
                    modifier = Modifier
                        .size(88.dp)
                        .clip(RoundedCornerShape(24.dp))
                )
            } else {
                Box(
                    Modifier
                        .size(88.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.VideoLibrary, null, tint = Color.White, modifier = Modifier.size(44.dp))
                }
            }

            Spacer(Modifier.height(22.dp))
            Text(
                "欢迎使用 我的多媒体",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "音视频处理工具箱",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(32.dp))

            // 说明卡片
            Card(
                Modifier.widthIn(max = 520.dp).fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(22.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.CreateNewFolder, null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "先选择一个输出文件夹",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "处理完成的文件会保存到这个文件夹。请挑一个你好找的位置，" +
                            "比如「下载 / 我的多媒体」。授权后随时可以在「设置」里更换。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = MaterialTheme.typography.bodyMedium.lineHeight
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            Button(
                onClick = onPickDir,
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    "选择输出目录",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(Modifier.height(14.dp))
            Text(
                "需要存储权限才能保存文件，权限仅用于读写你选择的文件夹",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}
