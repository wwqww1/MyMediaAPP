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

package com.mymedia.app.model

import android.net.Uri
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf

/** 任务状态 */
enum class TaskState(val label: String) {
    QUEUED("排队中"), PREPARING("准备中"), RUNNING("处理中"),
    PAUSED("已暂停"), DONE("已完成"), FAILED("失败"), CANCELLED("已取消")
}

/** 任务类型 */
enum class TaskType(val label: String) {
    SEPARATE_AUDIO("分离音频"),
    SEPARATE_VIDEO("分离视频"),
    AUDIO_CONVERT("音频转换"),
    VIDEO_CONVERT("视频转换"),
    TRIM("时间裁剪"),
    CONCAT("音视频拼接"),
    GIF("视频转GIF"),
    SNAPSHOT("视频截图")
}

/**
 * 单个处理任务。所有字段可变状态，供 Compose 观察。
 */
class MediaTask(
    val id: Long,
    val type: TaskType,
    val inputNames: List<String>,
    val outputName: String,
    val params: Map<String, String> = emptyMap(),
    val inputUris: List<Uri> = emptyList()
) {
    /** 复制到 cache 后的实际输入文件路径（按序） */
    val inputs = mutableListOf<String>()

    val state = mutableStateOf(TaskState.QUEUED)
    val progress = mutableIntStateOf(0)
    val outputPath = mutableStateOf<String?>(null)
    val error = mutableStateOf<String?>(null)

    /** 真实失败原因（错误码/异常消息），用于错误提示 */
    var lastError: String? = null

    /** 分配给 C 层的控制句柄（-1 = 未分配） */
    var handle: Int = -1

    fun pause() { if (state.value == TaskState.RUNNING) state.value = TaskState.PAUSED }
    fun resume() { if (state.value == TaskState.PAUSED) state.value = TaskState.RUNNING }
}
