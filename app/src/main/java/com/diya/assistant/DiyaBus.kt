package com.diya.assistant

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** What Diya is doing right now. */
enum class Status { OFF, PREPARING, WAITING, LISTENING, THINKING, SPEAKING }

data class ChatLine(val fromUser: Boolean, val text: String)

/**
 * Shared state between the background service (which does the work)
 * and the screen (which only displays it).
 */
object DiyaBus {
    val status = MutableStateFlow(Status.OFF)
    /** Extra info under the status: download progress, errors. */
    val detail = MutableStateFlow("")
    /** What the wake-word engine last heard. Shown on screen to help tune the wake word. */
    val heard = MutableStateFlow("")
    /** Live transcript while you are speaking a command. */
    val partial = MutableStateFlow("")
    val wakeReady = MutableStateFlow(false)
    val chat = MutableStateFlow<List<ChatLine>>(emptyList())

    /** New WhatsApp / SMS / Telegram messages, for announcing aloud. */
    val newMessages = MutableSharedFlow<SeenNotification>(extraBufferCapacity = 10)

    @Volatile
    var activityVisible = false

    /** Phone is ringing right now (so "answer the call" makes sense). */
    @Volatile
    var ringing = false

    @Volatile
    var inCall = false

    fun addChat(fromUser: Boolean, text: String) {
        if (text.isBlank()) return
        chat.update { (it + ChatLine(fromUser, text)).takeLast(60) }
    }
}
