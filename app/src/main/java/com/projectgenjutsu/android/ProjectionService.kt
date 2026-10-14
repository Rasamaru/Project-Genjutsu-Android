package com.projectgenjutsu.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Toast
import com.google.mlkit.nl.translate.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.nio.ByteBuffer

/**
 * Первый Android MVP: с согласия пользователя постоянно поддерживает MediaProjection,
 * но OCR выполняет ТОЛЬКО по нажатию плавающей кнопки. Фото не сохраняются.
 * Для каждого запроса полный пакет результатов выводится атомарно, без обрывков.
 */
class ProjectionService : Service() {
    companion object {
        const val ACTION_START = "com.projectgenjutsu.android.START"
        const val ACTION_STOP = "com.projectgenjutsu.android.STOP"
        const val EXTRA_RESULT_CODE = "captureResultCode"
        const val EXTRA_RESULT_DATA = "captureResultIntent"
        const val EXTRA_LANGUAGE = "languageIndex"
        private const val CHANNEL = "genjutsu_capture"
        private const val NOTIFICATION_ID = 1001
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var frameThread: HandlerThread
    private lateinit var frameHandler: Handler
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var textRecognizer: TextRecognizer? = null
    private var translator: Translator? = null
    private var overlay: TranslationOverlay? = null
    private var capturedWidth = 1
    private var capturedHeight = 1
    private var modelReady = false
    private var processing = false
    private var closing = false
    private var version = 0
    @Volatile private var scanPending = false
    @Volatile private var readyAfterMs = 0L

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            main.post {
                if (!closing) {
                    notifyUser("Android остановил захват экрана")
                    stopSelf()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        frameThread = HandlerThread("GenjutsuCapture").also { it.start() }
        frameHandler = Handler(frameThread.looper)
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Genjutsu · перевод экрана", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (projection != null) {
                    notifyUser("Сессия уже работает. Используйте плавающую кнопку.")
                } else {
                    try {
                        // Для Android 14+ foreground service должен запуститься ДО getMediaProjection().
                        startForeground(
                            NOTIFICATION_ID, notification("Запуск захвата экрана…"),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                        )
                        setupProjection(intent)
                    } catch (error: Exception) {
                        notifyUser("Не удалось начать захват: ${error.javaClass.simpleName}")
                        stopSelf()
                    }
                }
            }
            ACTION_STOP -> stopSelf()
        }
        // Не воссоздавать захват без нового согласия пользователя.
        return START_NOT_STICKY
    }

    private fun notification(message: String): Notification {
        val stopIntent = Intent(this, ProjectionService::class.java).apply { action = ACTION_STOP }
        val stop = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("Project Genjutsu · экранный перевод")
            .setContentText(message)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Остановить", stop)
            .build()
    }

    private fun setStatus(message: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification(message))
    }

    private fun setupProjection(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val token = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
            ?: error("Нет разрешения на захват")
        val language = intent.getIntExtra(EXTRA_LANGUAGE, 0).coerceIn(0, 2)

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(when (language) {
                1 -> TranslateLanguage.JAPANESE
                2 -> TranslateLanguage.CHINESE
                else -> TranslateLanguage.ENGLISH
            })
            .setTargetLanguage(TranslateLanguage.RUSSIAN)
            .build()
        translator = Translation.getClient(options)
        textRecognizer = when (language) {
            1 -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            2 -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }

        val metrics = resources.displayMetrics
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.getRealMetrics(metrics)
        capturedWidth = metrics.widthPixels
        capturedHeight = metrics.heightPixels
        reader = ImageReader.newInstance(
            capturedWidth, capturedHeight, PixelFormat.RGBA_8888, 2
        )
        reader?.setOnImageAvailableListener({ imageReader -> onFrame(imageReader) }, frameHandler)

        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = projectionManager.getMediaProjection(resultCode, token)
            ?: error("Android не предоставил MediaProjection")
        projection?.registerCallback(projectionCallback, frameHandler)
        // Одна сессия = один createVirtualDisplay. Не вызывать повторно на Android 14+.
        virtualDisplay = projection?.createVirtualDisplay(
            "GenjutsuScreen", capturedWidth, capturedHeight, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, frameHandler
        )

        overlay = TranslationOverlay(this) { scanOnce() }.also { it.attach() }
        setStatus("Подготовка офлайн-модели, нужен Wi-Fi")
        val wifiOnly = DownloadConditions.Builder().requireWifi().build()
        translator?.downloadModelIfNeeded(wifiOnly)
            ?.addOnSuccessListener {
                if (closing) return@addOnSuccessListener
                modelReady = true
                setStatus("Готово · нажмите плавающую кнопку 文A")
                notifyUser("Офлайн-перевод готов. Перейдите в игру и нажмите 文A")
            }
            ?.addOnFailureListener {
                if (closing) return@addOnFailureListener
                setStatus("Не удалось скачать языковую модель · проверьте Wi-Fi")
                notifyUser("Модель не загружена. Проверьте Wi-Fi и перезапустите сессию")
            }
    }

    private fun scanOnce() {
        if (closing) return
        if (!modelReady) {
            notifyUser("Подождите загрузки модели перевода по Wi-Fi")
            return
        }
        if (processing || scanPending) return

        // При повороте устройства геометрия захвата устаревает. Останавливаем
        // сессию, чтобы не рисовать перевод в неверных координатах.
        val metrics = resources.displayMetrics
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        if (metrics.widthPixels != capturedWidth || metrics.heightPixels != capturedHeight) {
            notifyUser("Экран повернулся. Запустите перевод заново")
            stopSelf()
            return
        }
        overlay?.hideForCapture()
        readyAfterMs = SystemClock.elapsedRealtime() + 350L
        scanPending = true
        val thisVersion = ++version
        main.postDelayed({
            if (!closing && thisVersion == version && scanPending) {
                scanPending = false
                overlay?.showControls()
                notifyUser("Не удалось получить новый кадр. Попробуйте снова")
            }
        }, 2800L)
    }

    private fun onFrame(imageReader: ImageReader) {
        val image = try { imageReader.acquireLatestImage() } catch (_: IllegalStateException) { null }
            ?: return
        try {
            if (!scanPending || SystemClock.elapsedRealtime() < readyAfterMs) return
            // Флаг сбрасывается до переноса Bitmap на главный поток: никаких повторных OCR.
            scanPending = false
            val plane = image.planes[0]
            val buffer: ByteBuffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val bitmapWidth = capturedWidth + (rowStride - pixelStride * capturedWidth) / pixelStride
            val padded = Bitmap.createBitmap(bitmapWidth, capturedHeight, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(buffer)
            val cropped = Bitmap.createBitmap(padded, 0, 0, capturedWidth, capturedHeight)
            if (cropped !== padded) padded.recycle()
            main.post { recognize(cropped) }
        } catch (error: Exception) {
            main.post {
                overlay?.showControls()
                processing = false
                notifyUser("Не удалось обработать кадр: ${error.javaClass.simpleName}")
            }
        } finally {
            image.close()
        }
    }

    private fun recognize(image: Bitmap) {
        if (closing) { image.recycle(); return }
        processing = true
        val jobVersion = version
        setStatus("Распознавание текста…")
        val input = InputImage.fromBitmap(image, 0)
        textRecognizer?.process(input)
            ?.addOnSuccessListener { result ->
                image.recycle()
                if (closing || jobVersion != version) return@addOnSuccessListener
                val recognized = result.textBlocks.mapNotNull { block ->
                    block.boundingBox?.let { box ->
                        if (block.text.isNotBlank()) Pair(Rect(box), block.text.trim().take(900))
                        else null
                    }
                }.take(16)
                if (recognized.isEmpty()) {
                    overlay?.setTranslations(emptyList(), capturedWidth, capturedHeight)
                    processing = false
                    setStatus("Текст не найден · нажмите 文A ещё раз")
                } else translateAll(recognized, jobVersion)
            }
            ?.addOnFailureListener { error ->
                image.recycle()
                if (closing || jobVersion != version) return@addOnFailureListener
                processing = false
                overlay?.showControls()
                setStatus("Ошибка OCR, повторите сканирование")
                notifyUser("OCR: ${error.javaClass.simpleName}")
            }
    }

    private fun translateAll(blocks: List<Pair<Rect, String>>, jobVersion: Int) {
        val engine = translator ?: run {
            processing = false
            overlay?.showControls()
            return
        }
        setStatus("Локальный перевод…")
        val translated = arrayOfNulls<PositionedTranslation>(blocks.size)
        var remaining = blocks.size
        for ((index, block) in blocks.withIndex()) {
            engine.translate(block.second)
                .addOnCompleteListener { result ->
                    if (closing || jobVersion != version) return@addOnCompleteListener
                    if (result.isSuccessful && !result.result.isNullOrBlank()) {
                        translated[index] = PositionedTranslation(block.first, result.result!!)
                    }
                    remaining--
                    if (remaining == 0) {
                        // Атомарная отрисовка: одна серия OCR -> одно изменение оверлея.
                        overlay?.setTranslations(translated.filterNotNull(), capturedWidth, capturedHeight)
                        processing = false
                        setStatus("Перевод готов · нажмите 文A для обновления")
                        if (translated.all { it == null }) notifyUser("Перевод не получен")
                    }
                }
        }
    }

    private fun notifyUser(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        closing = true
        version++
        scanPending = false
        overlay?.close()
        overlay = null
        textRecognizer?.close()
        textRecognizer = null
        translator?.close()
        translator = null
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        try { projection?.unregisterCallback(projectionCallback) } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        frameThread.quitSafely()
        super.onDestroy()
    }
}
