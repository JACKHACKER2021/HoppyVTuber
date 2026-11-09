package cz.hoppy.vtuber

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix as GMatrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2

/**
 * Přední kamera -> MediaPipe FaceLandmarker -> natočení hlavy, mrkání, pusa.
 * Používá ji jak MainActivity, tak plovoucí bublina (OverlayService).
 */
class FaceTracker(private val context: Context) {

    // surová data z trackingu (zapisuje analyzer vlákno)
    @Volatile var yaw = 0f; private set
    @Volatile var pitch = 0f; private set
    @Volatile var roll = 0f; private set
    @Volatile var blinkL = 0f; private set
    @Volatile var blinkR = 0f; private set
    @Volatile var jaw = 0f; private set
    @Volatile var smile = 0f; private set
    @Volatile var seenAt = 0L; private set

    // kalibrace (sdílená přes Prefs, ať platí i v bublině)
    var offYaw = Prefs.get(context).getFloat("offYaw", 0f); private set
    var offPitch = Prefs.get(context).getFloat("offPitch", 0f); private set
    var offRoll = Prefs.get(context).getFloat("offRoll", 0f); private set

    val faceVisible get() = SystemClock.uptimeMillis() - seenAt < 700

    var isRunning = false; private set

    private val executor = Executors.newSingleThreadExecutor()
    private val landmarker: FaceLandmarker? = runCatching { createLandmarker() }
        .onFailure { Log.e("FaceTracker", "FaceLandmarker se nepodařilo vytvořit", it) }
        .getOrNull()
    private var provider: ProcessCameraProvider? = null
    private var useCases: Array<UseCase> = emptyArray()
    private var analysis: ImageAnalysis? = null
    private var rotation: Int? = null

    private fun createLandmarker(): FaceLandmarker {
        val opts = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task").build())
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)
            .setOutputFaceBlendshapes(true)
            .setOutputFacialTransformationMatrixes(true)
            .setResultListener { res, _ -> onFace(res) }
            .build()
        return FaceLandmarker.createFromOptions(context, opts)
    }

    /** Připojí kameru k danému lifecycle (Activity nebo služba). Volat z hlavního vlákna. */
    fun start(owner: LifecycleOwner, previewView: PreviewView? = null) {
        if (isRunning) return
        isRunning = true
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!isRunning) return@addListener
            val p = future.get()
            provider = p
            val list = mutableListOf<UseCase>()
            previewView?.let { pv ->
                list += Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
            }
            val a = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .apply { rotation?.let { setTargetRotation(it) } }
                .build()
            a.setAnalyzer(executor) { proxy -> analyze(proxy) }
            list += a
            analysis = a
            useCases = list.toTypedArray()
            try {
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, *useCases)
            } catch (e: Exception) {
                Log.e("FaceTracker", "Kameru nejde připojit", e)
                isRunning = false
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** Odpojí jen vlastní use-case (ProcessCameraProvider je sdílený v celém procesu). */
    fun stop() {
        isRunning = false
        if (useCases.isNotEmpty()) runCatching { provider?.unbind(*useCases) }
        useCases = emptyArray()
        analysis = null
    }

    /** Otočení displeje (Surface.ROTATION_*), aby tracking fungoval i na šířku ve hře. */
    fun setRotation(r: Int) {
        rotation = r
        analysis?.targetRotation = r
    }

    fun calibrate() {
        offYaw = yaw; offPitch = pitch; offRoll = roll
        Prefs.get(context).edit()
            .putFloat("offYaw", offYaw).putFloat("offPitch", offPitch).putFloat("offRoll", offRoll)
            .apply()
    }

    fun close() {
        stop()
        // zavřít až po posledním snímku na analyzer vlákně
        executor.execute { runCatching { landmarker?.close() } }
        executor.shutdown()
    }

    private fun analyze(proxy: androidx.camera.core.ImageProxy) {
        try {
            val bmp = Bitmap.createBitmap(proxy.width, proxy.height, Bitmap.Config.ARGB_8888)
            val rotDeg = proxy.imageInfo.rotationDegrees.toFloat()
            proxy.use { bmp.copyPixelsFromBuffer(it.planes[0].buffer) }
            val rot = GMatrix().apply { postRotate(rotDeg) }
            val upright = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, rot, true)
            landmarker?.detectAsync(BitmapImageBuilder(upright).build(), SystemClock.uptimeMillis())
        } catch (e: Exception) {
            Log.w("FaceTracker", "Snímek přeskočen", e)
        }
    }

    private fun onFace(res: FaceLandmarkerResult) {
        res.facialTransformationMatrixes().ifPresent { list ->
            if (list.isNotEmpty()) {
                seenAt = SystemClock.uptimeMillis()
                val m = list[0]                       // 4x4, column-major: m[col*4+row]
                fun r(i: Int, j: Int) = m[j * 4 + i]
                val deg = (180f / PI.toFloat())
                yaw = atan2(r(0, 2), r(2, 2)) * deg
                pitch = asin((-r(1, 2)).coerceIn(-1f, 1f)) * deg
                roll = atan2(r(1, 0), r(1, 1)) * deg
            }
        }
        res.faceBlendshapes().ifPresent { all ->
            if (all.isNotEmpty()) {
                for (c in all[0]) when (c.categoryName()) {
                    "eyeBlinkLeft" -> blinkL = c.score()
                    "eyeBlinkRight" -> blinkR = c.score()
                    "jawOpen" -> jaw = c.score()
                    "mouthSmileLeft" -> smile = c.score()
                }
            }
        }
    }
}
