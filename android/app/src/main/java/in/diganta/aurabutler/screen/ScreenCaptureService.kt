package `in`.diganta.aurabutler.screen

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import `in`.diganta.aurabutler.R
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** One-shot, user-authorized screen capture. */
class ScreenCaptureService : Service() {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private val completed = AtomicBoolean(false)
    private val cleaningUp = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundSafely()
        val request = intent ?: return finishWithError("Screen-capture request was empty.")
        val resultCode = request.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData = request.parcelableIntentExtra(EXTRA_RESULT_DATA)
            ?: return finishWithError("Android did not return screen-sharing permission data.")
        val width = request.getIntExtra(EXTRA_WIDTH, 720).coerceIn(320, 2_560)
        val height = request.getIntExtra(EXTRA_HEIGHT, 1_280).coerceIn(480, 2_560)
        val density = request.getIntExtra(EXTRA_DENSITY, 320).coerceIn(120, 640)
        val captureDelayMs = request.getLongExtra(EXTRA_CAPTURE_DELAY_MS, DEFAULT_CAPTURE_DELAY_MS)
            .coerceIn(1_500L, 12_000L)

        if (resultCode != Activity.RESULT_OK) {
            return finishWithError("Screen-sharing permission was not granted.")
        }

        val manager = getSystemService(MediaProjectionManager::class.java)
        mediaProjection = manager.getMediaProjection(resultCode, resultData)
            ?: return finishWithError("Android could not create the screen projection.")

        handlerThread = HandlerThread("AuraScreenCapture").also { it.start() }
        val handler = Handler(handlerThread!!.looper)
        mediaProjection?.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    finishCapture()
                }
            },
            handler
        )

        handler.postDelayed({
            if (cleaningUp.get() || completed.get()) return@postDelayed
            imageReader = ImageReader.newInstance(
                width,
                height,
                PixelFormat.RGBA_8888,
                2
            )
            imageReader?.setOnImageAvailableListener({ reader ->
                if (!completed.compareAndSet(false, true)) {
                    reader.acquireLatestImage()?.close()
                    return@setOnImageAvailableListener
                }
                val image = reader.acquireLatestImage()
                if (image == null) {
                    sendError("AURA received no screen frame.")
                    finishCapture()
                    return@setOnImageAvailableListener
                }

                try {
                    val plane = image.planes.first()
                    val buffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * width
                    val paddedWidth = width + rowPadding / pixelStride
                    val padded = Bitmap.createBitmap(
                        paddedWidth,
                        height,
                        Bitmap.Config.ARGB_8888
                    )
                    padded.copyPixelsFromBuffer(buffer)
                    val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
                    if (cropped !== padded) padded.recycle()

                    val outputFile = File(
                        cacheDir,
                        "aura_screen_${System.currentTimeMillis()}.jpg"
                    )
                    FileOutputStream(outputFile).use { stream ->
                        cropped.compress(Bitmap.CompressFormat.JPEG, 88, stream)
                    }
                    cropped.recycle()
                    sendSuccess(outputFile.absolutePath)
                } catch (error: Throwable) {
                    Log.e(TAG, "Screen capture failed", error)
                    sendError(
                        "AURA could not save the screen frame: " +
                            error.message.orEmpty().take(100)
                    )
                } finally {
                    image.close()
                    finishCapture()
                }
            }, handler)

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "AURA one-shot analyzer",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                handler
            )

        }, captureDelayMs)

        handler.postDelayed({
            if (completed.compareAndSet(false, true)) {
                sendError("Screen capture timed out. Keep the target screen visible and try again.")
                finishCapture()
            }
        }, captureDelayMs + CAPTURE_TIMEOUT_MS)

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        finishCapture()
        super.onDestroy()
    }

    private fun startForegroundSafely() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_aura)
            .setContentTitle("AURA screen analyzer")
            .setContentText("Switch to the target screen — AURA captures one frame after the countdown")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            type
        )
    }

    private fun finishWithError(message: String): Int {
        sendError(message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        return START_NOT_STICKY
    }

    private fun sendSuccess(path: String) {
        sendBroadcast(
            Intent(ACTION_CAPTURE_COMPLETE)
                .setPackage(packageName)
                .putExtra(EXTRA_PATH, path)
        )
    }

    private fun sendError(message: String) {
        sendBroadcast(
            Intent(ACTION_CAPTURE_FAILED)
                .setPackage(packageName)
                .putExtra(EXTRA_ERROR, message)
        )
    }

    private fun finishCapture() {
        if (!cleaningUp.compareAndSet(false, true)) return

        val display = virtualDisplay
        virtualDisplay = null
        val reader = imageReader
        imageReader = null
        val projection = mediaProjection
        mediaProjection = null
        val thread = handlerThread
        handlerThread = null

        runCatching { display?.release() }
        runCatching { reader?.close() }
        runCatching { projection?.stop() }
        thread?.quitSafely()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun Intent.parcelableIntentExtra(key: String): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(key, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(key)
        }

    companion object {
        const val ACTION_CAPTURE_COMPLETE =
            "in.diganta.aurabutler.action.SCREEN_CAPTURE_COMPLETE"
        const val ACTION_CAPTURE_FAILED =
            "in.diganta.aurabutler.action.SCREEN_CAPTURE_FAILED"
        const val EXTRA_PATH = "capture_path"
        const val EXTRA_ERROR = "capture_error"

        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val EXTRA_WIDTH = "width"
        private const val EXTRA_HEIGHT = "height"
        private const val EXTRA_DENSITY = "density"
        private const val EXTRA_CAPTURE_DELAY_MS = "capture_delay_ms"
        private const val CHANNEL_ID = "aura_screen_capture"
        private const val CHANNEL_NAME = "AURA screen analysis"
        private const val NOTIFICATION_ID = 7201
        private const val CAPTURE_TIMEOUT_MS = 10_000L
        private const val DEFAULT_CAPTURE_DELAY_MS = 5_000L
        private const val TAG = "AuraScreenCapture"

        fun start(
            context: Context,
            resultCode: Int,
            resultData: Intent,
            width: Int,
            height: Int,
            density: Int,
            captureDelayMs: Long = DEFAULT_CAPTURE_DELAY_MS
        ) {
            createChannel(context)
            val intent = Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
                .putExtra(EXTRA_WIDTH, width)
                .putExtra(EXTRA_HEIGHT, height)
                .putExtra(EXTRA_DENSITY, density)
                .putExtra(EXTRA_CAPTURE_DELAY_MS, captureDelayMs)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = "Visible one-shot screen analysis sessions"
                    }
                )
        }
    }
}
