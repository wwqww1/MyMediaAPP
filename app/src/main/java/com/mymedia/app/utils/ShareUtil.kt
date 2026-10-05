package com.mymedia.app.utils

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/** 文件分享 & 媒体库扫描 */
object ShareUtil {

    /** 分享单个文件（FileProvider 已配置 Download/我的多媒体 路径） */
    fun shareFile(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase())
            ?: "application/octet-stream"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享文件"))
    }

    /** 让系统媒体库识别新文件（相册可见） */
    fun scanFile(context: Context, file: File) {
        runCatching {
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
        }
    }
}
