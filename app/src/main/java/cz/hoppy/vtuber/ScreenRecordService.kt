package cz.hoppy.vtuber

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.IBinder
import android.provider.MediaStore

/** Nahrávání obrazovky (postava) + mikrofon do Movies/HoppyVTuber jako MP4. */
class ScreenRecordService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        var onFinished: ((Uri?) -> Unit)? = null
    }

    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var display: VirtualDisplay? = null
    private var uri: Uri? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_STOP -> stop()
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("rec", "Nahrávání", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "rec")
            .setContentTitle("Hoppy VTuber nahrává")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val code = intent.getIntExtra(EXTRA_CODE, 0)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)!!
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projection = mpm.getMediaProjection(code, data)
        projection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stop() }
        }, null)

        val dm = resources.displayMetrics
        val w = dm.widthPixels / 2 * 2
        val h = dm.heightPixels / 2 * 2

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "hoppy_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/HoppyVTuber")
        }
        uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        val pfd = contentResolver.openFileDescriptor(uri!!, "w")!!

        recorder = MediaRecorder(this).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(pfd.fileDescriptor)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setVideoSize(w, h)
            setVideoFrameRate(30)
            setVideoEncodingBitRate(8_000_000)
            setAudioEncodingBitRate(128_000)
            setAudioSamplingRate(44100)
            prepare()
        }
        display = projection!!.createVirtualDisplay(
            "hoppy", w, h, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, recorder!!.surface, null, null
        )
        recorder!!.start()
    }

    private fun stop() {
        val saved = uri
        try { recorder?.stop() } catch (_: Exception) { }
        recorder?.release(); recorder = null
        display?.release(); display = null
        projection?.stop(); projection = null
        uri = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        onFinished?.invoke(saved)
    }
}
