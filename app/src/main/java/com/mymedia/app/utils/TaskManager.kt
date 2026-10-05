package com.mymedia.app.utils

import com.mymedia.app.ui.theme.AppPrefs

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mymedia.app.model.MediaTask
import com.mymedia.app.model.TaskState
import com.mymedia.app.model.TaskType
import kotlin.jvm.JvmStatic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 任务队列中枢：并发限制 + 暂停/取消 + 进度回调 + 历史持久化
 */
object TaskManager {
    val tasks = mutableStateListOf<MediaTask>()
    // 并发无限制（用户要求，移除 1~10 限制）
    var concurrentLimit by mutableIntStateOf(Int.MAX_VALUE)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var nextId = 1L
    private var restored = false  // 内存内只恢复一次 pending
    private var runningCount = 0
    private val handlePool = Array(128) { false }
    private val taskByHandle = ConcurrentHashMap<Int, MediaTask>()

    /** C 层 JNI 进度回调入口 */
    @JvmStatic
    fun nativeProgress(handle: Int, percent: Int) {
        val t = taskByHandle[handle] ?: return
        if (percent > t.progress.intValue) {
            t.progress.intValue = percent.coerceIn(0, 99)
        }
    }

    // ── 提交 ──
    fun submit(
        context: Context,
        type: TaskType,
        inputUris: List<Uri>,
        outputName: String,
        params: Map<String, String> = emptyMap()
    ): MediaTask {
        val names = inputUris.map { getDisplayName(context, it) }
        val task = MediaTask(nextId++, type, names, outputName, params, inputUris)
        tasks.add(0, task)
        scope.launch { runTask(context, task) }
        persistPendingTasks()  // 持久化新 pending 任务（重启恢复用）
        return task
    }

    // ── 控制 ──
    fun pauseTask(task: MediaTask) {
        if (task.state.value != TaskState.RUNNING) return
        task.pause()
        if (task.handle >= 0) FFmpegBridge.taskControl(task.handle, pause = true)
    }

    fun resumeTask(task: MediaTask) {
        if (task.state.value != TaskState.PAUSED) return
        task.resume()
        if (task.handle >= 0) FFmpegBridge.taskControl(task.handle)
    }

    fun cancelTask(task: MediaTask) {
        if (task.state.value == TaskState.DONE || task.state.value == TaskState.FAILED) return
        task.state.value = TaskState.CANCELLED
        if (task.handle >= 0) FFmpegBridge.taskControl(task.handle, cancel = true)
    }

    /** 从列表移除任务（用户手动删除已完成/已失败/已取消的记录） */
    fun removeTask(task: MediaTask) {
        // 若还在运行，先取消 C 层任务并回收 handle
        if (task.state.value == TaskState.RUNNING || task.state.value == TaskState.PAUSED ||
            task.state.value == TaskState.QUEUED || task.state.value == TaskState.PREPARING) {
            cancelTask(task)
        }
        // 释放 handle 槽位
        val h = task.handle
        if (h in 0 until handlePool.size && handlePool[h]) {
            synchronized(handlePool) { handlePool[h] = false }
        }
        taskByHandle.remove(h)
        // 清理缓存目录 + 从列表移除
        cacheDirOf(task)?.deleteRecursively()
        tasks.remove(task)
        persistHistory()
    }

    /** 供 Kotlin 层 MediaCodec 循环调用：暂停阻塞等待、取消抛异常 */
    fun checkpoint(task: MediaTask?) {
        if (task == null) return
        if (task.state.value == TaskState.PAUSED) {
            while (task.state.value == TaskState.PAUSED) Thread.sleep(120)
        }
        if (task.state.value == TaskState.CANCELLED) {
            throw CancellationException("task cancelled")
        }
    }

    // ── 执行 ──
    private suspend fun runTask(context: Context, task: MediaTask) {
        try {
            awaitSlot(task)
            task.state.value = TaskState.PREPARING
            // 日志：任务类型 + 输入文件名列表 + 参数
            FFmpegBridge.log(
                "task#${task.id} ${task.type} start, inputs=[${task.inputNames.joinToString(", ")}] " +
                        "out=${task.outputName} params=${task.params.entries.joinToString(",") { "${it.key}=${it.value}" }}"
            )
            // SAF Uri → cache 文件（FFmpeg/MediaCodec 都要文件路径）
            val cacheDir = File(context.cacheDir, "task_${task.id}")
            cacheDir.mkdirs()
            task.inputs.clear()
            for (uri in task.inputUris) {
                checkpoint(task)
                val idx = task.inputs.size
                val src = copyUriToFile(context, uri, File(cacheDir, "in_$idx"))
                task.inputs.add(src.absolutePath)
                val name = task.inputNames.getOrNull(idx) ?: uri.lastPathSegment
                val size = if (src.length() >= 1_000_000) String.format(java.util.Locale.US, "%.1fMB", src.length() / 1_000_000.0)
                else if (src.length() >= 1000) "${src.length() / 1000}KB" else "${src.length()}B"
                FFmpegBridge.log("task#${task.id} in_$idx: $name ($size)")
            }
            // 输入已拷贝完成，此时才把真实缓存路径写入 pending（submit 时 inputs 为空）
            persistPendingTasks()
            task.state.value = TaskState.RUNNING
            task.handle = allocHandle()
            if (task.handle >= 0) taskByHandle[task.handle] = task
            val out = execute(context, task)
            if (task.state.value == TaskState.CANCELLED) {
                // 已取消，静默结束
            } else if (out != null) {
                // 先同步到用户目录（若有授权），再标记完成，避免"已完成但文件还没出现"
                if (OutputStore.getTreeUri(context) != null) {
                    val synced = OutputStore.saveFile(context, File(out))
                    if (synced != null) {
                        FFmpegBridge.log("task#${task.id} synced to user dir: $synced")
                    } else {
                        FFmpegBridge.log("task#${task.id} sync to user dir FAILED, kept at $out")
                    }
                }
                task.state.value = TaskState.DONE
                task.outputPath.value = out
                FFmpegBridge.log("task#${task.id} DONE out=$out")
                ShareUtil.scanFile(context, File(out))
            } else {
                task.state.value = TaskState.FAILED
                FFmpegBridge.log("task#${task.id} FAILED lastError=${task.lastError ?: "null"}")
                task.error.value = if (!FFmpegBridge.isAvailable()) {
                    "FFmpeg 引擎未加载：${FFmpegBridge.loadError ?: "libmymedia.so 缺失，请重新构建"}"
                } else {
                    "处理失败：${task.lastError ?: "未知错误"}（详细日志见「文件」页 log.txt）"
                }
            }
        } catch (e: CancellationException) {
            task.state.value = TaskState.CANCELLED
        } catch (e: Exception) {
            if (task.state.value != TaskState.CANCELLED) {
                task.state.value = TaskState.FAILED
                task.lastError = e.message
                task.error.value = "处理失败：${e.message}"
            }
        } finally {
            if (task.handle >= 0) {
                FFmpegBridge.taskControl(task.handle)
                releaseHandle(task.handle)
                taskByHandle.remove(task.handle)
                task.handle = -1
            }
            releaseSlot()
            cacheDirOf(task)?.deleteRecursively()
            persistHistory()
            // 任务终态后延迟 5 秒从列表移除（用户可见完成状态，随后自动进历史，
            // 避免与历史记录重复显示；列表只保留进行中的任务）
            // 注意：tasks 是 Compose 快照列表，必须在主线程移除
            if (task.state.value == TaskState.DONE || task.state.value == TaskState.FAILED ||
                task.state.value == TaskState.CANCELLED) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    delay(5000)
                    tasks.remove(task)
                }
                persistHistory()
            }
        }
    }

    private fun cacheDirOf(task: MediaTask): File? {
        val first = task.inputs.firstOrNull() ?: return null
        return File(first).parentFile
    }

    private fun execute(context: Context, task: MediaTask): String? {
        return try {
            executeInner(context, task)
        } catch (e: Exception) {
            task.lastError = e.message
            null
        }
    }

    /** FFmpeg 返回码记录：非 0 时记录错误码 */
    private fun ffRet(task: MediaTask, ret: Int, out: String?): String? {
        if (ret == 0) return out
        task.lastError = "FFmpeg 错误码 $ret"
        return null
    }

    private fun executeInner(context: Context, task: MediaTask): String? {
        val outDir = MediaSeparator.getOutputDir()
        val handle = task.handle
        return when (task.type) {
            TaskType.SEPARATE_AUDIO -> {
                // 用户要求：分离音频输出 MP3（FFmpeg 软编转码）
                val out = Naming.uniqueFile(outDir, task.outputName)
                val ret = FFmpegBridge.convertAudio(task.inputs[0], out.absolutePath, "mp3", "", handle)
                ffRet(task, ret, out.absolutePath)
            }
            TaskType.SEPARATE_VIDEO -> {
                // 零重编码复制视频轨
                MediaSeparator.extractVideoOnly(
                    context, File(task.inputs[0]), task.outputName, task
                ) { task.progress.intValue = it }
            }
            TaskType.AUDIO_CONVERT -> MediaConverter.convertAudioFfmpeg(
                context, File(task.inputs[0]), task.outputName,
                task.params["codec"] ?: "aac", task
            )
            TaskType.VIDEO_CONVERT -> MediaConverter.convertVideoSmart(
                context, File(task.inputs[0]), task.outputName,
                task.params["codec"] ?: "h264", task
            )
            TaskType.TRIM -> MediaConverter.trimMedia(
                context, File(task.inputs[0]), task.outputName, task
            )
            TaskType.CONCAT -> {
                val out = Naming.uniqueFile(outDir, task.outputName)
                val ret = FFmpegBridge.concat(task.inputs.toTypedArray(), out.absolutePath, handle)
                ffRet(task, ret, out.absolutePath)
            }
            TaskType.GIF -> {
                val out = Naming.uniqueFile(outDir, task.outputName)
                val fps = task.params["fps"] ?: "10"
                // GIF 限制尺寸（默认 480，见 ToolConfigScreen），否则 1080p 体积巨大图库卡死
                val maxw = task.params["maxw"] ?: "480"
                val maxh = task.params["maxh"] ?: "480"
                val ret = FFmpegBridge.convertVideo(task.inputs[0], out.absolutePath, "gif",
                    "gif=1;fps=$fps;maxw=$maxw;maxh=$maxh", handle)
                ffRet(task, ret, out.absolutePath)
            }
            TaskType.SNAPSHOT -> {
                val out = Naming.uniqueFile(outDir, task.outputName)
                val timeUs = task.params["timeUs"]?.toLongOrNull() ?: 0L
                val ret = FFmpegBridge.snapshot(task.inputs[0], out.absolutePath, timeUs, handle)
                ffRet(task, ret, out.absolutePath)
            }
        }
    }

    // ── 并发控制 ──
    private suspend fun awaitSlot(task: MediaTask) {
        while (true) {
            if (task.state.value == TaskState.CANCELLED) throw CancellationException()
            synchronized(this) {
                if (runningCount < concurrentLimit) {
                    runningCount++
                    return
                }
            }
            delay(300)
        }
    }

    private fun releaseSlot() {
        synchronized(this) {
            if (runningCount > 0) runningCount--
        }
    }

    // ── handle 池 ──
    private fun allocHandle(): Int {
        synchronized(this) {
            for (i in handlePool.indices) {
                if (!handlePool[i]) {
                    handlePool[i] = true
                    return i
                }
            }
            return -1
        }
    }

    private fun releaseHandle(h: Int) {
        synchronized(this) {
            if (h in handlePool.indices) handlePool[h] = false
        }
    }

    // ── 工具 ──
    private fun getDisplayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) {
                val n = c.getString(idx)
                if (!n.isNullOrBlank()) return n
            }
        }
        return uri.lastPathSegment ?: "文件"
    }

    private fun copyUriToFile(context: Context, uri: Uri, dest: File): File {
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().buffered(256 * 1024).use { out ->
                val buf = ByteArray(256 * 1024)
                var n: Int
                while (input.read(buf).also { n = it } > 0) out.write(buf, 0, n)
            }
        } ?: throw IllegalStateException("无法读取所选文件")
        return dest
    }

    // ── 历史持久化（最近 50 条）──
    private fun persistHistory() {
        runCatching {
            val ctx = appContext ?: return
            val prefs = ctx.getSharedPreferences("task_history", Context.MODE_PRIVATE)
            val list = (org.json.JSONArray(prefs.getString("history", "[]") ?: "[]")).let { arr ->
                buildList {
                    for (i in 0 until arr.length()) add(arr.getJSONObject(i))
                }.toMutableList()
            }
            // 修复重复：按 id 去重（此前每次 persistHistory 都把当前
            // DONE/FAILED 任务重复塞入，任务列表 + 历史各显示一份）
            val existingIds = list.mapNotNull { it.optLong("id", -1L) }.toMutableSet()
            tasks.filter { it.state.value == TaskState.DONE || it.state.value == TaskState.FAILED }
                .forEach { t ->
                    if (t.id in existingIds) return@forEach
                    list.add(0, org.json.JSONObject().apply {
                        put("id", t.id); put("type", t.type.name)
                        put("output", t.outputName); put("state", t.state.value.name)
                        put("time", System.currentTimeMillis())
                    })
                    existingIds.add(t.id)
                }
            val trimmed = list.take(50)
            prefs.edit().putString("history", org.json.JSONArray(trimmed).toString()).apply()
            // 任务终态后从 pending 移除（persistPendingTasks 已存过 pending）
            prefs.edit().remove("pending").apply()
        }
    }

    // 持久化所有未完成任务（QUEUED/RUNNING/PAUSED/PREPARING）
    // APP 重启时通过 restorePendingTasks 自动重新提交
    private fun persistPendingTasks() {
        runCatching {
            val ctx = appContext ?: return
            val prefs = ctx.getSharedPreferences("task_history", Context.MODE_PRIVATE)
            val arr = org.json.JSONArray()
            tasks.filter {
                val s = it.state.value
                s == TaskState.QUEUED || s == TaskState.RUNNING ||
                s == TaskState.PAUSED || s == TaskState.PREPARING
            }.forEach { task ->
                val obj = org.json.JSONObject()
                obj.put("id", task.id)
                obj.put("type", task.type.name)
                val inputsArr = org.json.JSONArray()
                task.inputs.forEach { inputsArr.put(it) }
                obj.put("inputs", inputsArr)
                obj.put("outputName", task.outputName)
                val paramsObj = org.json.JSONObject()
                task.params.forEach { (k, v) -> paramsObj.put(k, v) }
                obj.put("params", paramsObj)
                arr.put(obj)
            }
            prefs.edit().putString("pending", arr.toString()).apply()
        }
    }

    // APP 启动时恢复未完成任务（handle 已失效，从头重跑）
    private fun restorePendingTasks() {
        val ctx = appContext ?: return
        val prefs = ctx.getSharedPreferences("task_history", Context.MODE_PRIVATE)
        val json = prefs.getString("pending", "[]") ?: "[]"
        val arr = runCatching { org.json.JSONArray(json) }.getOrNull() ?: return
        if (arr.length() == 0) return
        var needDispatch = false
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            try {
                val type = TaskType.valueOf(obj.getString("type"))
                val inputsArr = obj.getJSONArray("inputs")
                val inputs = (0 until inputsArr.length()).map { inputsArr.getString(it) }
                val outputName = obj.getString("outputName")
                val paramsObj = obj.getJSONObject("params")
                val params = mutableMapOf<String, String>()
                paramsObj.keys().forEach { params[it] = paramsObj.getString(it) }
                // 检查输入文件还在（Android 可能清理 /data/data/.../cache）
                val inputsExist = inputs.all { java.io.File(it).exists() }
                // 用原 ID（避免 nextId++ 重新分配）
                val task = MediaTask(obj.getLong("id"), type, inputs.map { getDisplayName(ctx, android.net.Uri.parse(it)) },
                    outputName, params, inputs.map { android.net.Uri.parse(it) })
                task.handle = -1
                if (inputsExist) {
                    task.state.value = TaskState.QUEUED
                    needDispatch = true
                    scope.launch { runTask(ctx, task) }
                } else {
                    task.state.value = TaskState.FAILED
                    task.error.value = "重启后输入文件已丢失（系统清理缓存），请重新添加任务"
                }
                tasks.add(task)
            } catch (e: Throwable) {
                android.util.Log.e("TaskManager", "restorePending failed", e)
            }
        }
        // 清掉已恢复的 pending
        prefs.edit().remove("pending").apply()
    }

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        // 并发数从设置读取（0 = 无限制）
        val n = AppPrefs.getConcurrent(appContext!!)
        concurrentLimit = if (n <= 0) Int.MAX_VALUE else n
        // Android 11+ 无法直接写公共下载目录（requestLegacyExternalStorage 仅 API 29 生效），
        // 统一输出到 app 私有外部目录：全版本可写，app 内「文件」页管理 + 分享
        val dir = File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            "我的多媒体"
        )
        if (!dir.exists()) dir.mkdirs()
        MediaSeparator.setOutputDir(dir)
        // C 层日志落盘：输出目录/log.txt（app 文件页可见）
        FFmpegBridge.setLogFile(File(dir, "log.txt").absolutePath)
        if (!restored) { restored = true; restorePendingTasks() }  // APP 启动恢复未完成任务
    }
}
