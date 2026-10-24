package com.diya.assistant

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** One notification Diya saw, e.g. a WhatsApp message. */
data class SeenNotification(
    val pkg: String,
    val app: String,
    val title: String,
    val text: String,
    val time: Long,
    /** The notification's own "Reply" button, if it has one (WhatsApp, Telegram, Messages…). */
    val reply: Notification.Action? = null,
) {
    val canReply: Boolean get() = reply?.remoteInputs?.isNotEmpty() == true

    val isMessage: Boolean get() = pkg in MESSAGING_APPS

    companion object {
        val MESSAGING_APPS = setOf(
            "com.whatsapp", "com.whatsapp.w4b",
            "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms",
            "org.telegram.messenger", "com.instagram.android", "com.facebook.orca",
        )
    }
}

/**
 * Lets Diya read your notifications (WhatsApp, SMS, Telegram…) aloud when you ask,
 * and announce new messages if you turn that on in settings.
 * You enable it once in Android: Settings → Notification access → Diya.
 */
class NotificationWatcher : NotificationListenerService() {

    override fun onListenerConnected() {
        NotificationStore.connected = true
        runCatching { activeNotifications }.getOrNull()?.forEach { remember(it, announce = false) }
    }

    override fun onListenerDisconnected() {
        NotificationStore.connected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = remember(sbn, announce = true)

    private fun remember(sbn: StatusBarNotification, announce: Boolean) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty().trim()
        if (title.isEmpty() && text.isEmpty()) return
        val reply = n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
        val item = SeenNotification(sbn.packageName, appName(sbn.packageName), title, text, sbn.postTime, reply)
        if (NotificationStore.add(item) && announce && item.isMessage) DiyaBus.newMessages.tryEmit(item)
    }

    private fun appName(pkg: String): String = try {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }
}

/** Remembers recent notifications in memory (never saved to disk). */
object NotificationStore {
    private val items = ArrayDeque<SeenNotification>()

    /** True while Android has given Diya notification access. */
    @Volatile
    var connected = false

    /** Returns false for duplicates (apps often re-post the same message). */
    @Synchronized
    fun add(item: SeenNotification): Boolean {
        val dup = items.any { it.pkg == item.pkg && it.title == item.title && it.text == item.text }
        if (dup) return false
        // Keep only the newest notification per chat so replies go to the right conversation.
        if (item.canReply) items.removeAll { it.pkg == item.pkg && it.title == item.title && it.canReply }
        items.addLast(item)
        while (items.size > 40) items.removeFirst()
        return true
    }

    @Synchronized
    fun recent(onlyMessages: Boolean, pkgFilter: Set<String>? = null, limit: Int = 5): List<SeenNotification> =
        items.asReversed()
            .filter { (!onlyMessages || it.isMessage) && (pkgFilter == null || it.pkg in pkgFilter) }
            .take(limit)

    /** Newest chat (with a reply button) whose title matches one of [names]. */
    @Synchronized
    fun findChat(names: List<String>): SeenNotification? {
        val wanted = names.map { squash(it) }.filter { it.length >= 2 }
        if (wanted.isEmpty()) return null
        return items.asReversed().firstOrNull { n ->
            n.isMessage && n.canReply && squash(n.title).let { t -> wanted.any { t.contains(it) } }
        }
    }

    @Synchronized
    fun latestReplyable(): SeenNotification? = items.asReversed().firstOrNull { it.isMessage && it.canReply }

    /** Sends [text] through the notification's own Reply button, like replying from the notification shade. */
    fun reply(ctx: Context, item: SeenNotification, text: String): Boolean {
        val action = item.reply ?: return false
        val inputs = action.remoteInputs ?: return false
        val intent = Intent()
        val results = Bundle()
        for (ri in inputs) results.putCharSequence(ri.resultKey, text)
        RemoteInput.addResultsToIntent(inputs, intent, results)
        return try {
            action.actionIntent.send(ctx, 0, intent)
            true
        } catch (e: PendingIntent.CanceledException) {
            false
        }
    }

    private fun squash(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
}
