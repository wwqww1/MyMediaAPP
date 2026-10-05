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
