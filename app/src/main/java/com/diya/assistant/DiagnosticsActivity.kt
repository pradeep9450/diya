package com.diya.assistant

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.speech.SpeechRecognizer
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity

/** Shows what Diya is doing and recent events, with a button to copy it all for help. */
class DiagnosticsActivity : AppCompatActivity() {
    private lateinit var report: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg))
            setPadding(dp(16), dp(8), dp(16), dp(12))
        }
        root.addView(TextView(this).apply {
            text = "Diagnostics"
            textSize = 22f
            setTextColor(getColor(R.color.amber))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(4))
        })
        root.addView(TextView(this).apply {
            text = "If something isn't working, tap Copy and paste it to Claude."
            setTextColor(getColor(R.color.muted))
            textSize = 13f
        })
        report = TextView(this).apply {
            setTextColor(getColor(R.color.text))
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, dp(10), 0, dp(10))
        }
        root.addView(ScrollView(this).apply { addView(report) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = "Refresh"
            setOnClickListener { render() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(Button(this).apply {
            text = "Copy"
            setOnClickListener {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Diya diagnostics", report.text))
                Toast.makeText(this@DiagnosticsActivity, "Copied", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(Button(this).apply {
            text = "Close"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttons)
        setContentView(root)
        applySystemInsets(root)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val prefs = Prefs(this)
        val cmd = CommandListener(this)
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
        val onDevice = if (Build.VERSION.SDK_INT >= 31) {
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(this).toString() }.getOrDefault("?")
        } else "n/a"
        val missing = Perms.missing(this).map { it.substringAfterLast('.') }
        report.text = buildString {
            appendLine("Diya ${version ?: "?"} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Phone: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine()
            appendLine("Status: ${DiyaBus.status.value}   Wake ready: ${DiyaBus.wakeReady.value}")
            if (DiyaBus.detail.value.isNotBlank()) appendLine("Detail: ${DiyaBus.detail.value}")
            appendLine("Last heard by wake word: ${DiyaBus.heard.value.ifBlank { "-" }}")
            appendLine("Wake model bundled in app: ${VoskModelManager.hasBundledModel(this@DiagnosticsActivity)}")
            appendLine()
            appendLine("Speech recognizer available: ${cmd.isAvailable()}")
            appendLine("Using: ${cmd.preferredService()?.flattenToShortString() ?: "phone default"}")
            appendLine("On-device recognition: $onDevice")
            appendLine("Speech mode: ${prefs.speechEngine}   Language: ${prefs.language}   Conversation mode: ${prefs.conversationMode}")
            appendLine("Gemini key set: ${prefs.geminiKey.isNotBlank()} (${prefs.geminiModel})")
            appendLine()
            appendLine("Missing permissions: ${if (missing.isEmpty()) "none" else missing.joinToString()}")
            appendLine("Notification access: ${Perms.notificationAccess(this@DiagnosticsActivity)}")
            appendLine("Display over apps: ${Perms.overlay(this@DiagnosticsActivity)}")
            appendLine("Unrestricted battery: ${Perms.unrestrictedBattery(this@DiagnosticsActivity)}")
            appendLine()
            CrashLog.last(this@DiagnosticsActivity)?.let {
                appendLine("LAST CRASH:")
                appendLine(it.take(3000))
                appendLine()
            }
            appendLine("Recent events:")
            val log = DiyaLog.all()
            if (log.isEmpty()) appendLine("(none yet — turn Diya on and try talking)")
            log.takeLast(60).forEach { appendLine(it) }
        }
    }
}
