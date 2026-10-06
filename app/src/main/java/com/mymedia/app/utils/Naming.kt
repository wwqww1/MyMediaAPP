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

package com.mymedia.app.utils

import com.mymedia.app.model.TaskType
import java.io.File

/**
 * 自动命名：统一「原名-功能后缀.扩展名」，同名自动加 (1)(2)
 */
object Naming {

    fun baseName(name: String): String =
        name.substringBeforeLast('.').ifBlank { name }

    fun extOf(name: String): String =
        name.substringAfterLast('.', "")

    /** 输出文件重命名工具：按任务类型生成文件名 */
    fun outputNameFor(type: TaskType, inputName: String, sub: String, ext: String): String {
        val base = baseName(inputName)
        return when (type) {
            TaskType.SEPARATE_AUDIO -> "${base}-分离音频.$ext"
            TaskType.SEPARATE_VIDEO -> "${base}-分离视频.$ext"
            TaskType.AUDIO_CONVERT -> "${base}-转$sub.$ext"
            TaskType.VIDEO_CONVERT -> "${base}-转$sub.$ext"
            TaskType.TRIM -> "${base}-裁剪.$ext"
            TaskType.CONCAT -> "拼接_${base}.$ext"
            TaskType.GIF -> "${base}-动图.$ext"
            TaskType.SNAPSHOT -> "${base}-截图.$ext"
        }
    }

    /** 拼接任务多输入时的名字：取第一个文件名 + "等N个" */
    fun concatName(inputNames: List<String>, ext: String): String {
        val first = baseName(inputNames.firstOrNull() ?: "视频")
        return if (inputNames.size > 1) "${first}等${inputNames.size}个拼接.$ext" else "${first}-拼接.$ext"
    }

    /** 同名冲突自动追加 (1)(2) */
    fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = baseName(name)
        val ext = extOf(name)
        var i = 1
        while (true) {
            f = File(dir, if (ext.isEmpty()) "$base($i)" else "$base($i).$ext")
            if (!f.exists()) return f
            i++
        }
    }
}
