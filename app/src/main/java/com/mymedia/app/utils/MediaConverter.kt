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

import android.content.Context
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.Surface
import com.mymedia.app.model.MediaTask
import java.io.File
import java.nio.ByteBuffer

/**
 * 格式转换 & 高级处理 — 任务系统执行入口
 * 硬编（MediaCodec）优先 + FFmpeg 软编兜底；音频转换/视频转码/GIF/裁剪走 FFmpeg
 */
object MediaConverter {

    enum class AudioFormat(val mime: String, val ext: String, val label: String, val codec: String) {
        AAC("audio/mp4a-latm", "m4a", "AAC (.m4a)", "aac"),
        MP3("audio/mpeg", "mp3", "MP3 (.mp3)", "mp3"),
        WAV("audio/wav", "wav", "WAV (.wav)", "wav"),
        FLAC("audio/flac", "flac", "FLAC (.flac)", "flac")
        // 不提供 OGG：vorbis 编码有电音问题
    }

    enum class VideoFormat(val mime: String, val ext: String, val label: String, val codec: String) {
        MP4("video/avc", "mp4", "MP4 H.264", "h264"),
        H265("video/hevc", "mp4", "MP4 H.265", "h265"),
        GIF("image/gif", "gif", "GIF 动图", "gif"),
        M4A("audio/mp4a-latm", "m4a", "M4A 音频", "aac")  // 视频抽音轨转 m4a（AAC）
        // 不提供 AVI/TS/MPEG（不常用、兼容性差）；WebM 需 libvpx 外部库
    }

    // ═══════════════ 任务系统入口 ═══════════════

    /** 音频转换（FFmpeg 软编） */
    fun convertAudioFfmpeg(
        ctx: Context, input: File, outputName: String, codec: String, task: MediaTask?
    ): String? {
        val out = Naming.uniqueFile(MediaSeparator.getOutputDir(), outputName)
        val ret = FFmpegBridge.convertAudio(
            input.absolutePath, out.absolutePath, codec, kvOf(task), task?.handle ?: -1
        )
        if (ret == 0) return out.absolutePath
        task?.lastError = "FFmpeg 错误码 $ret"
        return null
    }

    /** 视频转换（H264/H265 硬编优先，其余 FFmpeg） */
    fun convertVideoSmart(
        ctx: Context, input: File, outputName: String, codec: String, task: MediaTask?
    ): String? {
        val out = Naming.uniqueFile(MediaSeparator.getOutputDir(), outputName)
        if (codec == "aac") {
            // 视频抽音轨转 m4a（C 层 do_audio_convert，自动从输入文件找音频流）
            val ret = FFmpegBridge.convertAudio(
                input.absolutePath, out.absolutePath, "aac", kvOf(task), task?.handle ?: -1
            )
            if (ret == 0) return out.absolutePath
            task?.lastError = "FFmpeg 错误码 $ret"
            return null
        }
        if (codec == "gif") {
            // GIF 动图：C 层 do_gif_convert（PAL8 + 自适应调色板 + fps 抽帧，gif=1 触发）
            // 画质/帧率从任务参数读取，默认 720px / 15fps
            val fps = task?.params?.get("fps") ?: "15"
            val maxw = task?.params?.get("maxw") ?: "720"
            val maxh = task?.params?.get("maxh") ?: "720"
            val kv = kvOf(task) + "gif=1;fps=$fps;maxw=$maxw;maxh=$maxh"
            val ret = FFmpegBridge.convertVideo(
                input.absolutePath, out.absolutePath, "gif", kv, task?.handle ?: -1
            )
            if (ret == 0) return out.absolutePath
            task?.lastError = "FFmpeg 错误码 $ret"
            return null
        }
        if (codec == "h264" || codec == "h265") {
            val mime = if (codec == "h264") "video/avc" else "video/hevc"
            val r = transcode(ctx, input, out.absolutePath, mime, false, task) { p ->
                task?.progress?.intValue = p
            }
            if (r != null) return r
        }
        // FFmpeg 软编兜底：libx264 未编译，用 mpeg4（.mp4 容器，兼容性可接受）
        // MKV 目标传 mpeg4 + .mkv 扩展名 → C 层自动选 matroska 容器
        val ffCodec = if (codec == "h264" || codec == "h265") "mpeg4" else codec
        val ret = FFmpegBridge.convertVideo(
            input.absolutePath, out.absolutePath, ffCodec, kvOf(task), task?.handle ?: -1
        )
        if (ret == 0) return out.absolutePath
        task?.lastError = "FFmpeg 错误码 $ret"
        return null
    }

    /** 时间裁剪（ss/to 单位 us，自动识别音视频） */
    fun trimMedia(
        ctx: Context, input: File, outputName: String, task: MediaTask?
    ): String? {
        val out = Naming.uniqueFile(MediaSeparator.getOutputDir(), outputName)
        // 多区间 kv（ss=x;to=y;ss=...;to=...），兼容旧单区间 ss/to 参数
        val kv = task?.params?.get("trim") ?: buildString {
            val ss = task?.params?.get("ss")?.toLongOrNull() ?: 0L
            val to = task?.params?.get("to")?.toLongOrNull() ?: 0L
            if (ss > 0) append("ss=$ss;")
            if (to > 0) append("to=$to;")
        }
        val handle = task?.handle ?: -1
        val ret = if (hasVideoTrack(input)) {
            // libx264 未编译，软编用 mpeg4（mp4 容器）
            FFmpegBridge.convertVideo(input.absolutePath, out.absolutePath, "mpeg4", kv, handle)
        } else {
            FFmpegBridge.convertAudio(input.absolutePath, out.absolutePath, "aac", kv, handle)
        }
        if (ret == 0) return out.absolutePath
        task?.lastError = "FFmpeg 错误码 $ret"
        return null
    }

    /** 是否含视频轨（决定裁剪/调整走哪条路径） */
    fun hasVideoTrack(input: File): Boolean {
        // 音频扩展名快速判断——FLAC/MP3 内嵌封面会被 MediaExtractor
        // 当作 video 轨（与 probeType 同款修复），纯音频直接返回 false，
        // 避免 ADJUST/TRIM 对音频误走视频路径（输出 .mp4、变速逻辑错乱）
        val fn = input.name.lowercase()
        if (fn.endsWith(".flac") || fn.endsWith(".mp3") || fn.endsWith(".wav") ||
            fn.endsWith(".ogg") || fn.endsWith(".m4a") || fn.endsWith(".aac") ||
            fn.endsWith(".opus") || fn.endsWith(".wma") || fn.endsWith(".ape")) return false
        return runCatching {
            val ex = MediaExtractor()
            ex.setDataSource(input.absolutePath)
            val r = (0 until ex.trackCount).any {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            ex.release()
            r
        }.getOrDefault(false)
    }

    /** 探测媒体类型（SAF uri）："video" / "audio" / "none" */
    fun probeType(context: Context, uri: Uri): String {
        /* 扩展名快速判断——FLAC/MP3 等内嵌封面会被 MediaExtractor 当作
         * video 轨（部分 ROM），导致纯音频文件误判为 video（ADJUST 输出 .mp4 根因）。
         * 音频扩展名直接判定，不依赖轨道探测。 */
        val fn = uri.lastPathSegment?.lowercase() ?: ""
        if (fn.endsWith(".flac") || fn.endsWith(".mp3") || fn.endsWith(".wav") ||
            fn.endsWith(".ogg") || fn.endsWith(".m4a") || fn.endsWith(".aac") ||
            fn.endsWith(".opus")) return "audio"
        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val ex = MediaExtractor()
                ex.setDataSource(pfd.fileDescriptor)
                var v = false; var a = false
                for (i in 0 until ex.trackCount) {
                    val m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                    if (m.startsWith("video/")) v = true
                    if (m.startsWith("audio/")) a = true
                }
                ex.release()
                when { v -> "video"; a -> "audio"; else -> "none" }
            } ?: "none"
        }.getOrDefault("none")
    }

    /** 检测文件是否有音频轨（视频被"分离视频"后无音轨，压缩/转码输出将无声） */
    fun hasAudioTrack(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val ex = MediaExtractor()
            try {
                ex.setDataSource(pfd.fileDescriptor)
                (0 until ex.trackCount).any {
                    ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                }
            } finally { ex.release() }
        } ?: true
    }.getOrDefault(true)

    /** 探测视频显示宽高比（含旋转修正，竖屏 90°/270° 交换宽高）。
     * 返回 w/h 的浮点比例，非视频或失败返回 0f（调用方回退默认高度）。 */
    fun probeVideoAspect(context: Context, uri: Uri): Float = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val ex = MediaExtractor()
            try {
                ex.setDataSource(pfd.fileDescriptor)
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                    if (!mime.startsWith("video/")) continue
                    val w = f.getInteger(MediaFormat.KEY_WIDTH)
                    val h = f.getInteger(MediaFormat.KEY_HEIGHT)
                    if (w <= 0 || h <= 0) return@runCatching 0f
                    var ratio = w.toFloat() / h
                    val rot = if (f.containsKey(MediaFormat.KEY_ROTATION)) f.getInteger(MediaFormat.KEY_ROTATION) else 0
                    if (rot == 90 || rot == 270) ratio = 1f / ratio
                    return@runCatching ratio.coerceIn(0.2f, 5f)
                }
                ex.release()
                0f
            } catch (e: Exception) {
                try { ex.release() } catch (_: Exception) {}
                throw e
            }
        } ?: 0f
    }.getOrDefault(0f)

    private fun kvOf(task: MediaTask?): String {
        if (task == null) return ""
        return buildString {
            task.params["ss"]?.let { if (it.toLongOrNull() ?: 0 > 0) append("ss=$it;") }
            task.params["to"]?.let { if (it.toLongOrNull() ?: 0 > 0) append("to=$it;") }
        }
    }

    // ═══════════════ MediaCodec 硬编转码核心 ═══════════════

    private fun transcode(
        ctx: Context, input: File, outPath: String,
        targetMime: String, isAudio: Boolean, task: MediaTask?, onProgress: (Int) -> Unit
    ): String? {
        var extractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var inputSurface: Surface? = null   // Surface 直连用

        return try {
            extractor = MediaExtractor()
            extractor.setDataSource(input.absolutePath)

            // 找视频轨 + 音频轨（视频转码时音频零重编码复制，防止输出无声）
            var trackIdx = -1
            var srcFmt: MediaFormat? = null
            var aTrack = -1
            var aFmt: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (isAudio && m.startsWith("audio/")) { trackIdx = i; srcFmt = f; break }
                if (!isAudio) {
                    if (m.startsWith("video/") && trackIdx == -1) { trackIdx = i; srcFmt = f }
                    if (m.startsWith("audio/") && aTrack == -1) { aTrack = i; aFmt = f }
                }
            }
            if (trackIdx == -1) error("文件中无${if (isAudio) "音频" else "视频"}轨道")

            extractor.selectTrack(trackIdx)
            val srcMime = srcFmt!!.getString(MediaFormat.KEY_MIME)!!
            val durationUs = runCatching { srcFmt.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L)

            /* Surface direct mode MUST use COLOR_FormatSurface, otherwise
             * some chips (e.g. Huawei) accept the config but interpret the color
             * layout wrongly -> purple/green/blue corruption. Keep a ByteBuffer
             * variant for fallback. */
            /* 取源真实帧率，避免源格式缺 KEY_FRAME_RATE 时输出被写成 30fps */
            val srcFps = detectSourceFps(input, srcFmt!!)
            val encFmtSurface = buildEncoderFormat(srcFmt!!, targetMime, isAudio, surfaceInput = !isAudio, srcFps = srcFps)
            val encFmtByte = buildEncoderFormat(srcFmt!!, targetMime, isAudio, surfaceInput = false, srcFps = srcFps)
            /* 修复 H265 输出灰紫色：
             * 此前用 ByteBuffer 模式手写 I420 给编码器，而编码器设的是
             * COLOR_FormatYUV420Flexible——芯片实际期望布局多为 NV12，
             * I420 数据被按 NV12 解释 → UV 错位 → 灰紫/偏色（H265 尤其明显）。
             * 改为 Surface 直连：解码输出直接渲染到编码器输入 Surface，
             * 色彩空间转换与 pts 全由系统（GPU）处理，零拷贝且颜色一定正确。
             * Surface 创建/配置失败时回退 ByteBuffer 模式（兼容个别老芯片）。 */
            var useSurface = false
            try {
                encoder = MediaCodec.createEncoderByType(targetMime)
                encoder.configure(encFmtSurface, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = encoder.createInputSurface()
                encoder.start()
                decoder = MediaCodec.createDecoderByType(srcMime)
                decoder.configure(srcFmt, inputSurface, null, 0)
                decoder.start()
                useSurface = true
            } catch (e: Throwable) {
                // 回退：ByteBuffer 模式（原逻辑）
                runCatching { encoder?.stop() }
                runCatching { encoder?.release() }
                runCatching { inputSurface?.release() }
                encoder = null; inputSurface = null; decoder = null
                decoder = MediaCodec.createDecoderByType(srcMime)
                decoder.configure(srcFmt, null, null, 0)
                decoder.start()
                encoder = MediaCodec.createEncoderByType(targetMime)
                encoder.configure(encFmtByte, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoder.start()
            }

            // 提升为非空局部变量，消除「可变可空变量的 smart cast 不可能」空安全警告
            val dec: MediaCodec = decoder ?: throw IllegalStateException("解码器初始化失败")
            val enc: MediaCodec = encoder ?: throw IllegalStateException("编码器初始化失败")

            val rotation = if (isAudio) 0 else MediaSeparator.readRotationFromPath(input.absolutePath)
            val muxFmt = if (targetMime == "video/x-vnd.on2.vp8")
                MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
            else
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            muxer = MediaMuxer(outPath, muxFmt)

            // 视频转码：音频轨先 add（copy 用，必须 start 前全部 add）
            var muxAudioTrack = -1
            var audioDone = false
            val aInfo = android.media.MediaCodec.BufferInfo()
            var aBuf: ByteBuffer? = null
            if (!isAudio && aTrack >= 0) {
                muxAudioTrack = muxer.addTrack(aFmt!!)
                audioExtractor = MediaExtractor()
                audioExtractor.setDataSource(input.absolutePath)
                audioExtractor.selectTrack(aTrack)
                val aSize = runCatching {
                    aFmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                }.getOrDefault(256 * 1024)
                aBuf = ByteBuffer.allocate(aSize.coerceIn(64 * 1024, 4 * 1024 * 1024))
            }

            val T = 10000L
            val decInfo = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()
            var decInputDone = false
            var encInputDone = false
            var encOutputDone = false
            var muxStarted = false
            var muxTrack = -1

            /* 修复「转码后视频后期无声」：
             * 原循环条件是 while (!encOutputDone)——视频编码一完成就退出，
             * 此时音频轨往往还没复制完（音视频等长或音频更长）→ 输出后半段静音。
             * 改为「视频完成 且 音频复制完成」才退出；视频完成后继续把剩余音频写完。 */
            while (!encOutputDone || (audioExtractor != null && !audioDone)) {
                TaskManager.checkpoint(task)

                // 音频轨零重编码复制（视频转码时保留声音）
                if (audioExtractor != null && !audioDone) {
                    aInfo.offset = 0
                    aInfo.size = audioExtractor.readSampleData(aBuf!!, 0)
                    if (aInfo.size < 0) {
                        audioDone = true
                    } else if (muxStarted) {
                        aInfo.presentationTimeUs = audioExtractor.sampleTime
                        aInfo.flags = audioExtractor.sampleFlags
                        muxer.writeSampleData(muxAudioTrack, aBuf, aInfo)
                        audioExtractor.advance()
                    }
                }

                // 视频已编码完成：跳过视频部分，只把剩余音频复制完
                if (encOutputDone) continue

                if (!decInputDone) {
                    val inIdx = dec.dequeueInputBuffer(T)
                    if (inIdx >= 0) {
                        val buf = dec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            decInputDone = true
                        } else {
                            dec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                var decOut = dec.dequeueOutputBuffer(decInfo, T)
                while (decOut >= 0 || decOut == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    when {
                        decOut == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {}
                        decOut >= 0 -> {
                            var rendered = false   // Surface 模式已渲染（避免重复释放）
                            if (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                if (!encInputDone) {
                                    if (useSurface) {
                                        // Surface 模式：通知编码器输入结束（B 帧缓存在此刷出）
                                        runCatching { enc.signalEndOfInputStream() }
                                        encInputDone = true
                                    } else {
                                        val eIdx = enc.dequeueInputBuffer(T)
                                        if (eIdx >= 0) {
                                            enc.queueInputBuffer(eIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                            encInputDone = true
                                        }
                                    }
                                }
                            } else if (useSurface) {
                                /* Surface 直连——releaseOutputBuffer(render=true)
                                 * 系统直接把该帧送到编码器输入 Surface（自动做色彩转换并
                                 * 继承 pts），无需任何 YUV 拷贝，颜色一定正确 */
                                rendered = true
                                /* render with the dec frame pts (ns) so the
                                 * enc receives correct timestamps (the boolean overload
                                 * loses them -> wrong duration / playback speed). */
                                val tsNs = (decInfo.presentationTimeUs * 1000L).coerceAtLeast(1L)
                                dec.releaseOutputBuffer(decOut, tsNs)
                            } else if (decInfo.size > 0) {
                                if (!encInputDone) {
                                    val eIdx = enc.dequeueInputBuffer(T)
                                    if (eIdx >= 0) {
                                        val encIn = enc.getInputBuffer(eIdx)!!
                                        var written = copyYuvFrame(dec, decOut, encIn)
                                        if (written <= 0) {
                                            val decBuf = runCatching { dec.getOutputBuffer(decOut) }.getOrNull()
                                            if (decBuf != null) {
                                                encIn.clear()
                                                decBuf.position(decInfo.offset)
                                                decBuf.limit(decInfo.offset + decInfo.size)
                                                encIn.put(decBuf)
                                                written = decInfo.size
                                            }
                                        }
                                        if (written > 0) {
                                            enc.queueInputBuffer(eIdx, 0, written, decInfo.presentationTimeUs, 0)
                                        } else {
                                            enc.queueInputBuffer(eIdx, 0, 0, decInfo.presentationTimeUs, 0)
                                        }
                                    }
                                }
                            }
                            if (!rendered) dec.releaseOutputBuffer(decOut, false)
                        }
                    }
                    decOut = dec.dequeueOutputBuffer(decInfo, 0)
                }

                var encOut = enc.dequeueOutputBuffer(encInfo, T)
                while (encOut >= 0 || encOut == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    when {
                        encOut == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!muxStarted) {
                                muxTrack = muxer.addTrack(enc.outputFormat)
                                if (rotation != 0) muxer.setOrientationHint(rotation)
                                muxer.start()
                                muxStarted = true
                            }
                        }
                        encOut >= 0 -> {
                            if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                encOutputDone = true
                            }
                            if (encInfo.size > 0 && muxStarted) {
                                val encBuf = enc.getOutputBuffer(encOut)!!
                                encBuf.position(encInfo.offset)
                                encBuf.limit(encInfo.offset + encInfo.size)
                                muxer.writeSampleData(muxTrack, encBuf, encInfo)
                            }
                            enc.releaseOutputBuffer(encOut, false)
                            if (durationUs > 0 && encInfo.presentationTimeUs > 0) {
                                onProgress((encInfo.presentationTimeUs * 100 / durationUs).toInt().coerceIn(0, 99))
                            }
                        }
                    }
                    encOut = enc.dequeueOutputBuffer(encInfo, 0)
                }
            }

            onProgress(100)
            outPath

        } catch (e: Exception) {
            e.printStackTrace()
            File(outPath).delete()
            null
        } finally {
            try { muxer?.stop() } catch (e: Exception) {}
            try { muxer?.release() } catch (e: Exception) {}
            try { inputSurface?.release() } catch (e: Exception) {}
            try { encoder?.stop() } catch (e: Exception) {}
            try { encoder?.release() } catch (e: Exception) {}
            try { decoder?.stop() } catch (e: Exception) {}
            try { decoder?.release() } catch (e: Exception) {}
            try { extractor?.release() } catch (e: Exception) {}
            try { audioExtractor?.release() } catch (e: Exception) {}
        }
    }

    /** 探测源视频真实帧率（MediaFormat 优先，缺失时用 MediaMetadataRetriever 由帧数/时长推算） */
    private fun detectSourceFps(input: File, srcFmt: MediaFormat): Int {
        val fromFmt = runCatching { srcFmt.getInteger(MediaFormat.KEY_FRAME_RATE) }.getOrDefault(0)
        if (fromFmt > 0) return fromFmt
        val byMeta = runCatching {
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(input.absolutePath)
            val fc = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull() ?: 0L
            val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            mmr.release()
            if (fc > 0 && durMs > 0) Math.round(fc * 1000.0 / durMs).toInt() else 0
        }.getOrDefault(0)
        return if (byMeta in 1..240) byMeta else 30
    }

    private fun buildEncoderFormat(srcFmt: MediaFormat, targetMime: String, isAudio: Boolean, surfaceInput: Boolean = false, srcFps: Int = 30): MediaFormat {
        return MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, targetMime)
            if (isAudio) {
                setInteger(MediaFormat.KEY_SAMPLE_RATE,
                    runCatching { srcFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(44100))
                setInteger(MediaFormat.KEY_CHANNEL_COUNT,
                    runCatching { srcFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(2))
                setInteger(MediaFormat.KEY_BIT_RATE, 128000)
            } else {
                setInteger(MediaFormat.KEY_WIDTH,
                    runCatching { srcFmt.getInteger(MediaFormat.KEY_WIDTH) }.getOrDefault(1920))
                setInteger(MediaFormat.KEY_HEIGHT,
                    runCatching { srcFmt.getInteger(MediaFormat.KEY_HEIGHT) }.getOrDefault(1080))
                setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, if (srcFps > 0) srcFps else 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    if (surfaceInput) MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                    else MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            }
        }
    }

    // ═══════════════ YUV 帧拷贝（stride/crop）═══════════════

    private fun copyYuvFrame(decoder: MediaCodec, decOut: Int, dst: ByteBuffer): Int {
        val img = try { decoder.getOutputImage(decOut) } catch (e: Exception) { return -1 }
        return try {
            val crop = img!!.cropRect
            val w = crop.width()
            val h = crop.height()
            if (w <= 0 || h <= 0) return -1
            val uvW = (w + 1) / 2
            val uvH = (h + 1) / 2
            val needed = w * h + uvW * uvH * 2
            dst.clear()
            if (dst.capacity() < needed) return -1
            val planes = img!!.planes
            /* 检测 NV12（2 plane, plane[1].pixelStride=2，UV 交错）
             * Android decoder 很多输出 NV12，假设 3 plane I420 会触发越界或 fallback memcpy
             * 直接 memcpy NV12 buffer 到期望 I420 的 encoder → UV 错位 → 黑+白+紫+绿 */
            val isNV12 = planes.size == 2 && planes[1].pixelStride == 2
            if (isNV12) {
                // Y plane
                copyPlane(planes[0], crop.left, crop.top, w, h, dst)
                // NV12 → I420：plane[1] 是 UV 交错 (U,V,U,V,...)，拆成 U 区 + V 区
                val uvPlane = planes[1]
                val src = uvPlane.buffer
                val rowStride = uvPlane.rowStride
                val pixelStride = uvPlane.pixelStride
                val left = crop.left / 2
                val top = crop.top / 2
                val uOffset = w * h
                val vOffset = uOffset + uvW * uvH
                for (row in 0 until uvH) {
                    for (col in 0 until uvW) {
                        val srcPos = (top + row) * rowStride + (left + col) * pixelStride
                        dst.put(uOffset + row * uvW + col, src.get(srcPos))      // U
                        dst.put(vOffset + row * uvW + col, src.get(srcPos + 1))  // V
                    }
                }
            } else {
                // 标准 I420 / YV12（3 plane）
                copyPlane(planes[0], crop.left, crop.top, w, h, dst)
                copyPlane(planes[1], crop.left / 2, crop.top / 2, uvW, uvH, dst)
                copyPlane(planes[2], crop.left / 2, crop.top / 2, uvW, uvH, dst)
            }
            needed
        } catch (e: Exception) {
            -1
        } finally {
            img!!.close()
        }
    }

    private fun copyPlane(plane: Image.Plane, left: Int, top: Int, w: Int, h: Int, dst: ByteBuffer) {
        val src = plane.buffer
        val ps = plane.pixelStride
        val rowStart = top * plane.rowStride + left * ps
        for (row in 0 until h) {
            src.position(rowStart + row * plane.rowStride)
            if (ps == 1) {
                // 标准拷贝：设置 limit 后整块复制（put(ByteBuffer,int) 在部分 Android 版本不可靠）
                val oldLimit = src.limit()
                src.limit(src.position() + w)
                dst.put(src)
                src.limit(oldLimit)
            } else {
                val limit = src.position() + w * ps
                while (src.position() < limit) {
                    dst.put(src.get())
                    if (ps == 2) src.get()
                    else src.position(src.position() + ps - 1)
                }
            }
        }
    }
}
