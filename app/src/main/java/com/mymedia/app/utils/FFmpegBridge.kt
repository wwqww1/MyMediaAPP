package com.mymedia.app.utils

/**
 * FFmpeg JNI bridge - 软编保底 + 高级功能
 * 支持：音频/视频转换、GIF、拼接、抽帧、裁剪、任务暂停/取消
 */
object FFmpegBridge {
    private var loaded = false
    var loadError: String? = null
        private set

    init {
        // 显式按依赖顺序加载：libmymedia.so 链接 avcodec/avformat 等，
        // 老设备/部分 Android 版本不自动解析依赖库，逐个先加载，失败可定位到具体库
        // 修复：移除 avdevice（C 层未使用，且新 CMake 未链接 → 缺失导致
        // System.loadLibrary 链断 → libmymedia.so 未加载 → 添加任务闪退）
        val libs = listOf(
            "avutil", "swresample", "swscale", "avcodec", "avformat", "mymedia"
        )
        var loadSuccess = true
        for (lib in libs) {
            try {
                System.loadLibrary(lib)
            } catch (e: Throwable) {
                loadError = "加载 $lib 失败: ${e.message}"
                loaded = false
                loadSuccess = false
                break
            }
        }
        if (loadSuccess) loaded = true
    }

    fun isAvailable(): Boolean = loaded && nativeIsAvailable()

    /** 设置 C 层日志文件路径（同时写 logcat + 文件） */
    fun setLogFile(path: String) {
        if (loaded) nativeSetLogFile(path)
    }

    /** Kotlin 层日志写入 C 层日志文件（与 FFmpeg 日志同一文件，便于整体排查） */
    fun log(msg: String) {
        if (!loaded) return
        try {
            nativeLog(msg)
        } catch (e: Throwable) {
            // 旧版 libmymedia.so 无 nativeLog 符号，忽略（不影响主流程）
        }
    }

    fun convertAudio(input: String, output: String, codec: String, kv: String = "", handle: Int = -1): Int =
        nativeConvertAudio(input, output, codec, kv.ifEmpty { null }, handle)

    fun convertVideo(input: String, output: String, codec: String, kv: String = "", handle: Int = -1): Int =
        nativeConvertVideo(input, output, codec, kv.ifEmpty { null }, handle)

    /** 多文件拼接（视频或音频，统一转码后合并） */
    fun concat(inputs: Array<String>, output: String, handle: Int = -1): Int =
        nativeConcat(inputs, output, handle)

    /** 抽帧：指定时间点（us）截取一帧输出 JPEG */
    fun snapshot(input: String, output: String, timeUs: Long, handle: Int = -1): Int =
        nativeSnapshot(input, output, timeUs, handle)

    fun taskControl(handle: Int, cancel: Boolean = false, pause: Boolean = false) {
        if (handle >= 0) nativeTaskControl(handle, if (cancel) 1 else 0, if (pause) 1 else 0)
    }

    private external fun nativeIsAvailable(): Boolean
    private external fun nativeSetLogFile(path: String)
    private external fun nativeLog(msg: String)
    private external fun nativeConvertAudio(input: String, output: String, codec: String, kv: String?, handle: Int): Int
    private external fun nativeConvertVideo(input: String, output: String, codec: String, kv: String?, handle: Int): Int
    private external fun nativeConcat(inputs: Array<String>, output: String, handle: Int): Int
    private external fun nativeSnapshot(input: String, output: String, timeUs: Long, handle: Int): Int
    private external fun nativeTaskControl(handle: Int, cancel: Int, pause: Int)
}
