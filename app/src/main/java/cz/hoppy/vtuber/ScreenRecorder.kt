package cz.hoppy.vtuber

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.view.WindowManager

/**
 * Nahrávání celé obrazovky (hra + Hoppy v bublině) přes MediaProjection.
 * Zvuk z mikrofonu (tvůj hlas a co slyší mikrofon).
 */
class ScreenRecorder(private val context: Context, private val onStopped: (Boolean) -> Unit) {

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var uri: Uri? = null
    private var pfd: ParcelFileDescriptor? = null
    private var startedAt = 0L

    val isRecording get() = recorder != null
    fun timeText(): String { val s = (SystemClock.elapsedRealtime() - startedAt) / 1000; return "%02d:%02d".format(s / 60, s % 60) }

    fun start(resultCode: Int, data: Intent): Boolean {
        if (isRecording) return true
        return try {
            val mpm = context.getSystemService(MediaProjectionManager::class.java)
            val p = mpm.getMediaProjection(resultCode, data) ?: return false
            projection = p
            // Android 14+: callback musí být zaregistrovaný před vytvořením displeje
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { Handler(Looper.getMainLooper()).post { stop() } }
            }, Handler(Looper.getMainLooper()))

            val wm = context.getSystemService(WindowManager::class.java)
            val b = wm.maximumWindowMetrics.bounds
            // max 1920 na delší straně, sudé rozměry
            val k = minOf(1f, 1920f / maxOf(b.width(), b.height()))
            val w = ((b.width() * k).toInt() / 2) * 2
            val h = ((b.height() * k).toInt() / 2) * 2
            val dpi = context.resources.displayMetrics.densityDpi

            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "hoppy_hra_${System.currentTimeMillis()}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/HoppyVTuber")
            }
            val u = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)!!
            uri = u
            val fd = context.contentResolver.openFileDescriptor(u, "w")!!
            pfd = fd
            val withAudio = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            val r = (if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()).apply {
                if (withAudio) setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(fd.fileDescriptor)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(w, h)
                setVideoFrameRate(30)
                setVideoEncodingBitRate(10_000_000)
                if (withAudio) {
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(128_000); setAudioSamplingRate(44100)
                }
                prepare()
            }
            display = p.createVirtualDisplay("hoppy_rec", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null)
            r.start()
            recorder = r
            startedAt = SystemClock.elapsedRealtime()
            true
        } catch (e: Exception) {
            CrashLog.note(context, "nahrávání obrazovky", e)
            cleanup(false); false
        }
    }

    fun stop() {
        if (recorder == null && projection == null) return
        val ok = runCatching { recorder?.stop() }.isSuccess && recorder != null
        cleanup(ok)
        onStopped(ok)
    }

    private fun cleanup(ok: Boolean) {
        runCatching { recorder?.release() }; recorder = null
        runCatching { display?.release() }; display = null
        val p = projection; projection = null
        runCatching { p?.stop() }
        runCatching { pfd?.close() }; pfd = null
        if (!ok) uri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
        uri = null
    }
}

/** Průhledná aktivita jen pro systémový dotaz „Spustit nahrávání obrazovky?“. Výsledek pošle bublině. */
class ScreenCaptureRequestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && data != null) {
            startService(Intent(this, OverlayService::class.java)
                .setAction(OverlayService.ACTION_REC_GRANTED)
                .putExtra("code", resultCode).putExtra("data", data))
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
