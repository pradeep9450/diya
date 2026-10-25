package com.diya.assistant

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.diya.assistant.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private var pulse: AnimatorSet? = null
    private var shownStatus: Status? = null
    private var afterPermission: (() -> Unit)? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val action = afterPermission
            afterPermission = null
            if (hasMic()) action?.invoke()
            else Toast.makeText(this, "Diya needs the microphone to hear you.", Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        applySystemInsets(b.root)
        prefs = Prefs(this)

        b.settingsButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        b.powerButton.setOnClickListener {
            if (DiyaBus.status.value == Status.OFF) {
                prefs.wantOn = true
                withPermissions { DiyaService.send(this, DiyaService.ACTION_START) }
            } else {
                prefs.wantOn = false
                DiyaService.send(this, DiyaService.ACTION_STOP)
            }
        }

        b.micButton.setOnClickListener {
            withPermissions { DiyaService.send(this, DiyaService.ACTION_LISTEN) }
        }

        b.sendButton.setOnClickListener { sendTyped() }
        b.input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendTyped()
                true
            } else false
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { DiyaBus.status.collect { render() } }
                launch { DiyaBus.detail.collect { render() } }
                launch { DiyaBus.partial.collect { render() } }
                launch { DiyaBus.wakeReady.collect { render() } }
                launch {
                    DiyaBus.heard.collect {
                        b.heardText.text = if (it.isBlank()) "" else "Wake-word engine heard: “$it”"
                    }
                }
                launch { DiyaBus.chat.collect { renderChat(it) } }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        DiyaBus.activityVisible = true
        // Like a smart speaker: Diya turns herself on when you open the app (unless you turned her off).
        if (prefs.wantOn && hasMic() && DiyaBus.status.value == Status.OFF) {
            DiyaService.send(this, DiyaService.ACTION_START)
        }
    }

    override fun onPause() {
        DiyaBus.activityVisible = false
        super.onPause()
    }

    private fun sendTyped() {
        val text = b.input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        b.input.setText("")
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(b.input.windowToken, 0)
        withPermissions { DiyaService.send(this, DiyaService.ACTION_TEXT, text) }
    }

    // ---------------- Permissions ----------------

    private fun hasMic() = granted(Manifest.permission.RECORD_AUDIO)

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun withPermissions(action: () -> Unit) {
        // First time: ask for everything at once. After that only the microphone is required.
        val missing = Perms.missing(this)
        val mustAsk = !hasMic() || (missing.isNotEmpty() && !prefs.askedOptionalPermissions)
        if (!mustAsk) {
            action()
            return
        }
        prefs.askedOptionalPermissions = true
        afterPermission = action
        permissionLauncher.launch(missing.toTypedArray())
    }

    // ---------------- Rendering ----------------

    private fun render() {
        val s = DiyaBus.status.value
        b.statusText.text = when (s) {
            Status.OFF -> "Diya is resting"
            Status.PREPARING -> "Getting ready…"
            Status.WAITING -> if (DiyaBus.wakeReady.value) "Say “Hey Diya”" else "Tap the mic to talk"
            Status.LISTENING -> DiyaBus.partial.value.ifBlank { "Listening…" }
            Status.THINKING -> "Thinking…"
            Status.SPEAKING -> "Speaking…"
        }
        b.detailText.text = DiyaBus.detail.value
        b.detailText.visibility = if (DiyaBus.detail.value.isBlank()) View.GONE else View.VISIBLE
        b.powerButton.text = if (s == Status.OFF) "Turn on Diya" else "Turn off"
        if (s != shownStatus) {
            shownStatus = s
            renderOrb(s)
        }
    }

    private fun renderOrb(s: Status) {
        val (inner, outer) = when (s) {
            Status.OFF -> 0xFF4A3A2E.toInt() to 0xFF1E1610.toInt()
            Status.PREPARING -> 0xFF9C7550.toInt() to 0xFF3A2A1E.toInt()
            Status.WAITING -> 0xFFFFC56B.toInt() to 0xFFE8721C.toInt()
            Status.LISTENING -> 0xFFFFF1C9.toInt() to 0xFFFF8A1F.toInt()
            Status.THINKING -> 0xFFFFB547.toInt() to 0xFFB4471A.toInt()
            Status.SPEAKING -> 0xFFFFE08A.toInt() to 0xFFFF6A00.toInt()
        }
        b.orb.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = dp(70).toFloat()
            colors = intArrayOf(inner, outer)
        }
        b.orbGlow.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = dp(110).toFloat()
            colors = intArrayOf(ColorUtils.setAlphaComponent(outer, 0x88), Color.TRANSPARENT)
        }

        pulse?.cancel()
        pulse = null
        b.orb.scaleX = 1f
        b.orb.scaleY = 1f
        val period = when (s) {
            Status.LISTENING -> 600L
            Status.SPEAKING -> 420L
            Status.THINKING -> 900L
            Status.PREPARING -> 1400L
            else -> 0L
        }
        if (period == 0L) {
            b.orbGlow.alpha = if (s == Status.OFF) 0.15f else 0.7f
            return
        }
        fun repeating(a: ObjectAnimator) = a.apply {
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
        pulse = AnimatorSet().apply {
            playTogether(
                repeating(ObjectAnimator.ofFloat(b.orb, View.SCALE_X, 1f, 1.1f)),
                repeating(ObjectAnimator.ofFloat(b.orb, View.SCALE_Y, 1f, 1.1f)),
                repeating(ObjectAnimator.ofFloat(b.orbGlow, View.ALPHA, 0.45f, 1f)),
            )
            duration = period
            start()
        }
    }

    private fun renderChat(lines: List<ChatLine>) {
        val container = b.chatContainer
        container.removeAllViews()
        if (lines.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "Try saying:\n“Hey Diya, what's the weather?”\n“Kal ka mausam batao”\n" +
                    "“5 minute ka timer lagao”\n“Mummy ko call karo”\n“Rahul ko WhatsApp karo ki main late hoon”\n" +
                    "“Add meeting with Rahul tomorrow at 5 pm”\n“Arijit Singh ke gaane bajao”\n“मैं कहाँ हूँ?”"
                setTextColor(getColor(R.color.muted))
                textSize = 14f
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.3f)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(24)
            })
            return
        }
        val maxW = (resources.displayMetrics.widthPixels * 0.78).toInt()
        for (line in lines) {
            val tv = TextView(this).apply {
                text = line.text
                textSize = 15f
                maxWidth = maxW
                setTextColor(getColor(if (line.fromUser) R.color.on_amber else R.color.text))
                background = AppCompatResources.getDrawable(
                    this@MainActivity,
                    if (line.fromUser) R.drawable.bubble_user else R.drawable.bubble_diya
                )
                setPadding(dp(14), dp(10), dp(14), dp(10))
            }
            container.addView(tv, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = if (line.fromUser) Gravity.END else Gravity.START
                topMargin = dp(8)
            })
        }
        b.chatScroll.post { b.chatScroll.smoothScrollTo(0, container.height) }
    }
}
