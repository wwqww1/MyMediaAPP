package com.mymedia.app.utils

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.os.Environment
import com.mymedia.app.model.MediaTask
import java.io.File
import java.nio.ByteBuffer

/**
 * 音视频分离 — 纯视频零重编码（保留旋转元数据）
 * 分离音频由 FFmpeg 转 MP3（TaskManager 内处理）
 */
object MediaSeparator {

    /** 提取纯视频（零重编码复制视频轨 → MP4），自动命名 + 自动去重 */
    fun extractVideoOnly(
        ctx: Context, input: File, outputName: String,
        task: MediaTask?, onProgress: (Int) -> Unit
    ): String? {
        val out = Naming.uniqueFile(getOutputDir(), outputName)
        return extract(ctx, input.absolutePath, out.absolutePath, false, task, onProgress)
    }

    private fun extract(
        ctx: Context, inputPath: String, outPath: String,
        isAudio: Boolean, task: MediaTask?, onProgress: (Int) -> Unit
    ): String? {
        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null

        return try {
            extractor = MediaExtractor()
            extractor.setDataSource(inputPath)

            // 找轨道
            var trackIdx = -1
            var fmt: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (isAudio && m.startsWith("audio/")) { trackIdx = i; fmt = f; break }
                if (!isAudio && m.startsWith("video/")) { trackIdx = i; fmt = f; break }
            }
            if (trackIdx == -1) error(if (isAudio) "视频中没有音频轨道" else "视频中没有视频轨道")

            extractor.selectTrack(trackIdx)

            // 旋转元数据：MediaMuxer 不继承源旋转，必须回写否则竖屏变横屏
            val rotation = if (isAudio) 0 else readRotationFromPath(inputPath)

            val maxInputSize = runCatching {
                fmt!!.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            }.getOrDefault(256 * 1024)

            val buf = ByteBuffer.allocate(maxInputSize.coerceIn(64 * 1024, 4 * 1024 * 1024))
            val info = android.media.MediaCodec.BufferInfo()

            muxer = MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outTrack = muxer.addTrack(fmt!!)
            if (rotation != 0) muxer.setOrientationHint(rotation)
            muxer.start()

            val durationUs = runCatching {
                fmt.getLong(MediaFormat.KEY_DURATION)
            }.getOrDefault(0L)

            while (true) {
                TaskManager.checkpoint(task)
                info.offset = 0
                info.size = extractor.readSampleData(buf, 0)
                if (info.size < 0) break
                info.presentationTimeUs = extractor.sampleTime
                info.flags = extractor.sampleFlags
                muxer.writeSampleData(outTrack, buf, info)
                extractor.advance()
                if (durationUs > 0) {
                    onProgress((info.presentationTimeUs * 100 / durationUs).toInt().coerceIn(0, 99))
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
            try { extractor?.release() } catch (e: Exception) {}
        }
    }

    /** 输出目录：默认 Download/我的多媒体（旧），Android 10+ 由 TaskManager.init 切到私有外部目录 */
    @Volatile
    private var outputDirOverride: File? = null

    fun setOutputDir(dir: File) { outputDirOverride = dir }

    fun getOutputDir(): File {
        outputDirOverride?.let { return it }
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "我的多媒体"
        )
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 从文件路径读取旋转角度（0/90/180/270），失败返回 0 */
    fun readRotationFromPath(path: String): Int {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(path)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            0
        } finally {
            runCatching { mmr.release() }
        }
    }
}
