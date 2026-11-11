package cz.hoppy.vtuber

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import io.github.sceneview.SceneView

/**
 * Nahrává jen 3D scénu (zrcadlení SceneView do MediaRecorderu) – žádná tlačítka ani REC ve videu.
 * Používá ji aplikace i bublina. Video se uloží do Movies/HoppyVTuber.
 */
class SceneRecorder(private val context: Context, private val sceneView: SceneView) {

    private var recorder: MediaRecorder? = null
    private var uri: Uri? = null
    private var pfd: ParcelFileDescriptor? = null
    private var startedAt = 0L

    val isRecording get() = recorder != null
    val elapsedSec get() = if (isRecording) (SystemClock.elapsedRealtime() - startedAt) / 1000 else 0L

    /** [minWidth] = video aspoň tak široké (bublina je malá, video se zvětší). */
    fun start(maxWidth: Int = 1080, minWidth: Int = 720): Boolean {
        if (isRecording) return true
        val vw = sceneView.width; val vh = sceneView.height
        if (vw < 16 || vh < 16) return false
        val target = vw.coerceIn(minWidth, maxWidth).toFloat()
        val w = ((target).toInt() / 2) * 2
        val h = ((vh * target / vw).toInt() / 2) * 2
        val withAudio = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "hoppy_${System.currentTimeMillis()}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/HoppyVTuber")
            }
            val u = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)!!
            uri = u
            val fd = context.contentResolver.openFileDescriptor(u, "w")!!
            pfd = fd
            val r = (if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()).apply {
                if (withAudio) setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(fd.fileDescriptor)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(w, h)
                setVideoFrameRate(30)
                setVideoEncodingBitRate(8_000_000)
                if (withAudio) {
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(128_000); setAudioSamplingRate(44100)
                }
                prepare(); start()
            }
            sceneView.startMirroring(r.surface, 0, 0, w, h)
            recorder = r
            startedAt = SystemClock.elapsedRealtime()
            true
        } catch (e: Exception) {
            CrashLog.note(context, "nahrávání start", e)
            recorder?.release(); recorder = null
            runCatching { pfd?.close() }; pfd = null
            uri?.let { runCatching { context.contentResolver.delete(it, null, null) } }; uri = null
            false
        }
    }

    /** Zastaví a uloží. Vrací true, když se video uložilo. Volat jde kdykoli (i když nic neběží). */
    fun stop(): Boolean {
        val r = recorder ?: return false
        recorder = null
        runCatching { sceneView.stopMirroring(r.surface) }
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        runCatching { pfd?.close() }; pfd = null
        if (!ok) uri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
        uri = null
        return ok
    }

    fun timeText(): String { val s = elapsedSec; return "%02d:%02d".format(s / 60, s % 60) }
}
