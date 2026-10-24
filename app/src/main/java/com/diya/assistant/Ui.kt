package com.diya.assistant

import android.content.Context
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Keeps content clear of the status bar, navigation bar and keyboard. */
fun applySystemInsets(view: View) {
    val l = view.paddingLeft
    val t = view.paddingTop
    val r = view.paddingRight
    val b = view.paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        v.setPadding(l + bars.left, t + bars.top, r + bars.right, b + maxOf(bars.bottom, ime.bottom))
        WindowInsetsCompat.CONSUMED
    }
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
