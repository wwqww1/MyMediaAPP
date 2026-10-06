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

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mymedia.app.ui.theme.Primary

/** SAF 文件名 */
fun getFileName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && c.moveToFirst()) {
            val n = c.getString(idx)
            if (!n.isNullOrBlank()) return n
        }
    }
    return uri.lastPathSegment ?: "文件"
}

@Composable
fun TopBar(title: String, onBack: () -> Unit, enabled: Boolean = true) {
    Row(
        modifier = Modifier.fillMaxWidth().displayCutoutPadding().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 返回键始终可用
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Primary)
        }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
    }
}
