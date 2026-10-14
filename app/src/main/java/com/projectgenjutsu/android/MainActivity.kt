package com.projectgenjutsu.android

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val screenCaptureRequest = 412
    private lateinit var languageSpinner: Spinner
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = (18 * resources.displayMetrics.density).toInt()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(0xFF0B0B12.toInt())
        }
        val title = TextView(this).apply {
            text = "◈  Project Genjutsu"
            textSize = 25f
            setTextColor(0xFF22D3EE.toInt())
        }
        panel.addView(title)
        panel.addView(label("Android · 0.1.0 · прототип ручного перевода"))
        panel.addView(label("Исходный язык:"))
        languageSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf("English → Русский", "日本語 → Русский", "中文 → Русский")
            )
            setBackgroundColor(0xFFF5F7FF.toInt())
        }
        panel.addView(languageSpinner, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        val saved = getSharedPreferences("ui", MODE_PRIVATE).getInt("lang", 0).coerceIn(0, 2)
        languageSpinner.setSelection(saved)

        panel.addView(actionButton("Начать захват и перевод", 0xFF22D3EE.toInt()) {
            requestSession()
        })
        panel.addView(actionButton("Остановить сессию", 0xFF7C4DFF.toInt()) {
            stopService(Intent(this, ProjectionService::class.java))
            status.text = "Сессия остановлена"
        })
        status = label("Для работы нужно разрешить показ поверх окон и захват экрана.")
        panel.addView(status)
        panel.addView(label(
            "Как пользоваться: запустите сессию → откройте игру → нажмите плавающую кнопку 文A. " +
            "Перевод останется до следующего ручного сканирования."
        ))
        panel.addView(label(
            "ПРИВАТНОСТЬ: OCR и перевод текста выполняются на устройстве через ML Kit. " +
            "Первое скачивание языковой модели требует Wi-Fi; Google ML Kit может передавать " +
            "служебные метрики работы SDK. Содержимое экрана приложение никуда не отправляет. " +
            "Захват включается только после вашего разрешения и видимого уведомления."
        ))
        panel.addView(label(
            "Ограничения прототипа: ручной режим; текст закрывается подложкой, " +
            "не восстанавливается фон; защищённый от записи экран не захватывается."
        ))

        setContentView(ScrollView(this).apply {
            addView(panel)
        })
    }

    private fun label(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 15f
        setTextColor(0xFFF5F7FF.toInt())
        setPadding(0, 14, 0, 14)
        gravity = Gravity.START
    }

    private fun actionButton(caption: String, color: Int, action: () -> Unit): Button =
        Button(this).apply {
            text = caption
            setTextColor(0xFF0B0B12.toInt())
            setBackgroundColor(color)
            setOnClickListener { action() }
        }

    private fun requestSession() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 801)
            status.text = "Разрешите уведомления и нажмите «Начать» ещё раз"
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
            status.text = "Разрешите отображение поверх других окон и нажмите «Начать» снова"
            return
        }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val captureIntent = if (Build.VERSION.SDK_INT >= 34) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else manager.createScreenCaptureIntent()
        startActivityForResult(captureIntent, screenCaptureRequest)
    }

    @Deprecated("Для простого прототипа используется стандартный callback Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != screenCaptureRequest) return
        if (resultCode != RESULT_OK || data == null) {
            status.text = "Разрешение захвата экрана не предоставлено"
            return
        }
        val lang = languageSpinner.selectedItemPosition.coerceIn(0, 2)
        getSharedPreferences("ui", MODE_PRIVATE).edit().putInt("lang", lang).apply()
        val start = Intent(this, ProjectionService::class.java).apply {
            action = ProjectionService.ACTION_START
            putExtra(ProjectionService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ProjectionService.EXTRA_RESULT_DATA, data)
            putExtra(ProjectionService.EXTRA_LANGUAGE, lang)
        }
        startForegroundService(start)
        status.text = "Сессия запущена. Перейдите в игру и нажмите 文A."
        Toast.makeText(this, "Откройте игру и нажмите плавающую кнопку 文A", Toast.LENGTH_LONG).show()
    }
}
