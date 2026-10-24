package com.diya.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.diya.assistant.databinding.ActivitySettingsBinding
import com.google.android.material.button.MaterialButton

class SettingsActivity : AppCompatActivity() {
    private lateinit var b: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { renderAccess() }

    /** A special Android permission that has its own settings page. */
    private class Access(val title: String, val why: String, val isOn: (Context) -> Boolean, val intent: (Context) -> Intent)

    private val accesses = listOf(
        Access(
            "Notification access", "Read and reply to WhatsApp, SMS and other messages",
            Perms::notificationAccess
        ) { Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) },
        Access(
            "Display over other apps", "Open apps, timers and calls hands-free from the background",
            Perms::overlay
        ) { Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${it.packageName}")) },
        Access(
            "Do Not Disturb control", "Turn silent / Do Not Disturb on and off",
            Perms::dndAccess
        ) { Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) },
        Access(
            "Modify system settings", "Change screen brightness",
            Perms::systemSettings
        ) { Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${it.packageName}")) },
        Access(
            "Unrestricted battery", "Stop Android from killing Diya in the background",
            Perms::unrestrictedBattery
        ) {
            @SuppressLint("BatteryLife")
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${it.packageName}"))
            i
        },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        applySystemInsets(b.root)
        prefs = Prefs(this)

        b.nameInput.setText(prefs.userName)
        b.cityInput.setText(prefs.city)
        b.keyInput.setText(prefs.geminiKey)
        b.modelInput.setText(prefs.geminiModel)
        b.wakeInput.setText(prefs.extraWakeWords.joinToString(", "))
        b.countryInput.setText(prefs.countryCode)
        b.languageGroup.check(if (prefs.hindi) R.id.langHindi else R.id.langEnglish)
        b.announceCalls.isChecked = prefs.announceCalls
        b.announceMessages.isChecked = prefs.announceMessages

        b.backButton.setOnClickListener { finish() }
        b.getKeyButton.setOnClickListener { open(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey"))) }
        b.voiceButton.setOnClickListener { open(Intent("com.android.settings.TTS_SETTINGS")) }
        b.grantButton.setOnClickListener {
            val missing = Perms.missing(this)
            if (missing.isEmpty()) {
                Toast.makeText(this, "All permissions are already allowed ✓", Toast.LENGTH_SHORT).show()
            } else {
                permissionLauncher.launch(missing.toTypedArray())
            }
        }

        b.saveButton.setOnClickListener {
            prefs.userName = b.nameInput.text?.toString().orEmpty()
            prefs.city = b.cityInput.text?.toString().orEmpty()
            prefs.geminiKey = b.keyInput.text?.toString().orEmpty()
            prefs.geminiModel = b.modelInput.text?.toString().orEmpty()
            prefs.extraWakeWords = b.wakeInput.text?.toString().orEmpty()
                .split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            prefs.countryCode = b.countryInput.text?.toString().orEmpty()
            prefs.language = if (b.languageGroup.checkedRadioButtonId == R.id.langHindi) "hi" else "en"
            prefs.announceCalls = b.announceCalls.isChecked
            prefs.announceMessages = b.announceMessages.isChecked
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        renderAccess()
    }

    private fun renderAccess() {
        val total = Perms.runtime.size
        val allowed = total - Perms.missing(this).size
        b.permStatus.text = if (allowed == total) {
            "All $total permissions allowed ✓ (microphone, contacts, calls, SMS, calendar, location, notifications)"
        } else {
            "$allowed of $total allowed. Diya needs these for calls, SMS, calendar, location and contacts."
        }

        val box = b.accessContainer
        box.removeAllViews()
        for (a in accesses) {
            val on = runCatching { a.isOn(this) }.getOrDefault(false)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, 0)
            }
            val label = TextView(this).apply {
                text = "${a.title}\n${a.why}"
                setTextColor(getColor(R.color.text))
                textSize = 14f
                setLineSpacing(0f, 1.15f)
            }
            row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val button = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = if (on) "On ✓" else "Allow"
                isEnabled = !on
                setOnClickListener { open(a.intent(this@SettingsActivity)) }
            }
            row.addView(button)
            box.addView(row)
        }
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open that on this phone.", Toast.LENGTH_SHORT).show()
        }
    }
}
