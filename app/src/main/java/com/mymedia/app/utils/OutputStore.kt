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

package com.mymedia.app.utils

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * 输出目录管理：SAF 树授权，用户可指定任意目录（如 Download/我的多媒体）。
 * 任务产物先写入 app 私有目录（FFmpeg 需要真实路径），完成后拷贝到授权目录，
 * 文件管理器直接可见。未授权时保持私有目录不变。
 */
object OutputStore {
    private const val PREFS = "output_store"
    private const val KEY_TREE_URI = "tree_uri"

    /** 当前授权的目录 URI（未设置返回 null） */
    fun getTreeUri(context: Context): Uri? {
        val s = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val u = s.getString(KEY_TREE_URI, null) ?: return null
        return Uri.parse(u)
    }

    /** 保存授权目录 URI（调用方须先 takePersistableUriPermission） */
    fun setTreeUri(context: Context, uri: Uri?) {
        val s = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ed = s.edit()
        if (uri == null) ed.remove(KEY_TREE_URI) else ed.putString(KEY_TREE_URI, uri.toString())
        ed.apply()
    }

    /** 目录显示名（如"我的多媒体"）；未设置返回 null */
    fun displayName(context: Context): String? {
        val uri = getTreeUri(context) ?: return null
        val df = DocumentFile.fromTreeUri(context, uri)
        return df?.name ?: uri.lastPathSegment?.trimStart(':')?.substringAfterLast('/')
    }

    /** 输出文件拷贝到授权目录；返回 content:// URI 字符串；失败返回 null */
    fun saveFile(context: Context, src: File): String? {
        val uri = getTreeUri(context) ?: return null
        val tree = DocumentFile.fromTreeUri(context, uri) ?: return null
        if (!tree.canWrite()) return null
        val mime = when (src.extension.lowercase()) {
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/mp4"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "ogg" -> "audio/ogg"
            "gif" -> "image/gif"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            else -> "application/octet-stream"
        }
        var doc = tree.findFile(src.name)
        if (doc == null) doc = tree.createFile(mime, src.name) ?: return null
        return try {
            context.contentResolver.openOutputStream(doc.uri, "w")?.use { out ->
                src.inputStream().use { inp -> inp.copyTo(out) }
            } ?: return null
            doc.uri.toString()
        } catch (e: Exception) {
            null
        }
    }
}
