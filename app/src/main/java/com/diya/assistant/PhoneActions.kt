package com.diya.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.SearchManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.CancellationSignal
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.MediaStore
import android.provider.Telephony
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.provider.ContactsContract
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import kotlin.coroutines.resume

/** Everything Diya can do on the phone itself. */
class PhoneActions(context: Context) {
    private val ctx = context.applicationContext

    enum class Launch { OPENED, NOTIFIED, MISSING }
    enum class Volume { UP, DOWN, MUTE, UNMUTE, SET, GET }

    sealed class CallResult {
        data class Started(val name: String, val launch: Launch) : CallResult()
        data object NoContactsPermission : CallResult()
        data object NotFound : CallResult()
    }

    /**
     * Opens another app. Android blocks this while Diya is in the background unless
     * "Display over other apps" is allowed, so in that case we post a tap-to-open notification.
     */
    fun launch(intent: Intent, label: String): Launch {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (DiyaBus.activityVisible || Settings.canDrawOverlays(ctx)) {
            return try {
                ctx.startActivity(intent)
                Launch.OPENED
            } catch (e: ActivityNotFoundException) {
                Launch.MISSING
            } catch (e: SecurityException) {
                Notifications.showTapToOpen(ctx, intent, label)
                Launch.NOTIFIED
            }
        }
        Notifications.showTapToOpen(ctx, intent, label)
        return Launch.NOTIFIED
    }

    fun setTimer(seconds: Int, label: String = ""): Launch = launch(
        Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label.ifBlank { "Diya timer" })
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true),
        "start your timer"
    )

    fun setAlarm(hour: Int, minute: Int, label: String = "Diya alarm"): Launch = launch(
        Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true),
        "set your alarm"
    )

    /** Finds an installed app whose name best matches what you said. */
    fun findApp(spoken: String): Pair<String, Intent>? {
        val pm = ctx.packageManager
        val want = squash(spoken)
        if (want.isEmpty()) return null
        @Suppress("DEPRECATION")
        val apps = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        )
        var best: ResolveInfo? = null
        var bestScore = 0
        for (ri in apps) {
            val label = squash(ri.loadLabel(pm).toString())
            if (label.isEmpty()) continue
            val score = when {
                label == want -> 4
                label.startsWith(want) -> 3
                want.startsWith(label) && label.length >= 3 -> 2
                label.contains(want) -> 1
                else -> 0
            }
            if (score > bestScore) {
                best = ri
                bestScore = score
            }
        }
        val ri = best ?: return null
        val intent = pm.getLaunchIntentForPackage(ri.activityInfo.packageName) ?: return null
        return ri.loadLabel(pm).toString() to intent
    }

    private fun squash(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun installed(pkg: String) = ctx.packageManager.getLaunchIntentForPackage(pkg) != null

    /** Returns the name of the app used and how it went. */
    fun play(query: String, app: String?): Pair<String, Launch> {
        val q = Uri.encode(query)
        if (app == "spotify" && installed("com.spotify.music")) {
            return "Spotify" to launch(Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:$q")), "play $query")
        }
        if (app == "youtube music" && installed(YT_MUSIC)) {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=$q")).setPackage(YT_MUSIC)
            return "YouTube Music" to launch(i, "play $query")
        }
        if (installed(YOUTUBE)) {
            val i = Intent(Intent.ACTION_SEARCH).setPackage(YOUTUBE).putExtra(SearchManager.QUERY, query)
            return "YouTube" to launch(i, "play $query")
        }
        return "YouTube" to launch(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$q")), "play $query"
        )
    }

    fun webSearch(query: String): Launch = launch(
        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))),
        "see results for $query"
    )

    fun call(who: String): CallResult {
        val digits = who.filter { it.isDigit() || it == '+' }
        val (name, number) = if (digits.count { it.isDigit() } >= 5) {
            who to digits
        } else {
            if (!granted(Manifest.permission.READ_CONTACTS)) return CallResult.NoContactsPermission
            findContact(who) ?: return CallResult.NotFound
        }
        val action = if (granted(Manifest.permission.CALL_PHONE)) Intent.ACTION_CALL else Intent.ACTION_DIAL
        return CallResult.Started(name, launch(Intent(action, Uri.parse("tel:" + Uri.encode(number))), "call $name"))
    }

    private val relationAliases = mapOf(
        "mom" to listOf("mom", "mummy", "mumma", "mama", "maa", "mother", "ammi", "mammi"),
        "dad" to listOf("dad", "daddy", "papa", "pappa", "father", "pitaji", "abbu"),
        "brother" to listOf("bhai", "bhaiya", "brother", "bro"),
        "sister" to listOf("didi", "sister", "behen", "sis"),
        "wife" to listOf("wife", "biwi"),
        "husband" to listOf("husband", "pati"),
    )

    /** Rough "sounds like" key: "Mummy" and "mammi" both become "m". */
    private fun skeleton(s: String): String {
        val t = s.lowercase()
            .replace("ph", "f").replace("w", "v").replace("z", "j").replace("ck", "k").replace("q", "k")
            .replace(Regex("[aeiouyh]"), "")
            .replace(Regex("[^a-z0-9]"), "")
        return t.replace(Regex("(.)\\1+"), "$1")
    }

    /** Finds a contact by (possibly Hindi or misheard) name. Returns display name and number. */
    fun findContact(spoken: String): Pair<String, String>? {
        if (!granted(Manifest.permission.READ_CONTACTS)) return null
        val latin = if (Hindi.hasDevanagari(spoken)) Translit.toLatin(spoken) else spoken.lowercase()
        val wanted = buildSet {
            add(latin)
            relationAliases.values.firstOrNull { group -> group.any { skeleton(it) == skeleton(latin) } }?.let { addAll(it) }
        }
        val all = mutableListOf<Pair<String, String>>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(0) ?: continue
                val num = c.getString(1) ?: continue
                all.add(n to num)
            }
        }
        if (all.isEmpty()) return null
        fun score(name: String): Int {
            val n = name.lowercase()
            var best = 0
            for (w in wanted) {
                val sc = when {
                    n == w || name == spoken -> 100
                    n.split(" ").any { it == w } -> 80
                    n.startsWith(w) -> 70
                    n.contains(w) -> 60
                    skeleton(n) == skeleton(w) -> 50
                    n.split(" ").any { skeleton(it) == skeleton(w) && skeleton(w).isNotEmpty() } -> 40
                    else -> 0
                }
                if (sc > best) best = sc
            }
            return best
        }
        val pick = all.maxByOrNull { score(it.first) } ?: return null
        return if (score(pick.first) > 0) pick else null
    }

    fun setTorch(on: Boolean): Boolean = try {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        if (id == null) false else {
            cm.setTorchMode(id, on)
            true
        }
    } catch (e: Exception) {
        false
    }

    /** Changes media volume and returns the new level in percent. */
    fun volume(kind: Volume, percent: Int = 0): Int {
        val am = ctx.getSystemService(AudioManager::class.java)
        val stream = AudioManager.STREAM_MUSIC
        val max = am.getStreamMaxVolume(stream).coerceAtLeast(1)
        val step = (max / 10).coerceAtLeast(1)
        val cur = am.getStreamVolume(stream)
        runCatching {
            when (kind) {
                Volume.UP -> am.setStreamVolume(stream, (cur + step * 2).coerceAtMost(max), 0)
                Volume.DOWN -> am.setStreamVolume(stream, (cur - step * 2).coerceAtLeast(0), 0)
                Volume.MUTE -> am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0)
                Volume.UNMUTE -> am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
                Volume.SET -> am.setStreamVolume(stream, (percent.coerceIn(0, 100) * max + 50) / 100, 0)
                Volume.GET -> Unit
            }
        }
        return am.getStreamVolume(stream) * 100 / max
    }

    fun battery(): Pair<Int, Boolean> {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) to bm.isCharging
    }

    // ---------------- Messages ----------------

    /** Sends a normal SMS. Returns false if SMS permission is missing or sending failed. */
    fun sendSms(number: String, text: String): Boolean {
        if (!granted(Manifest.permission.SEND_SMS)) return false
        return try {
            val sms = if (Build.VERSION.SDK_INT >= 31) {
                ctx.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            sms.sendMultipartTextMessage(number, null, sms.divideMessage(text), null, null)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Opens WhatsApp with the chat and message ready; you just tap send. */
    fun whatsapp(number: String, text: String?, countryCode: String): Launch {
        var digits = number.filter { it.isDigit() }
        if (number.trim().startsWith("00")) digits = digits.drop(2)
        if (digits.length == 11 && digits.startsWith("0")) digits = digits.drop(1)
        if (!number.trim().startsWith("+") && digits.length <= 10) digits = countryCode + digits
        val url = "https://api.whatsapp.com/send?phone=$digits" + (if (text.isNullOrBlank()) "" else "&text=" + Uri.encode(text))
        val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { installed(it) }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        if (pkg != null) intent.setPackage(pkg)
        return launch(intent, "open WhatsApp")
    }

    data class Sms(val from: String, val body: String)

    fun latestSms(limit: Int = 3): List<Sms>? {
        if (!granted(Manifest.permission.READ_SMS)) return null
        val out = mutableListOf<Sms>()
        ctx.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.TextBasedSmsColumns.ADDRESS, Telephony.TextBasedSmsColumns.BODY),
            null, null, Telephony.TextBasedSmsColumns.DATE + " DESC"
        )?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                val addr = c.getString(0).orEmpty()
                out.add(Sms(contactName(addr) ?: addr, c.getString(1).orEmpty()))
            }
        }
        return out
    }

    fun contactName(number: String): String? {
        if (number.isBlank() || !granted(Manifest.permission.READ_CONTACTS)) return null
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---------------- Calls ----------------

    /** Names (or numbers) of missed calls in the last 24 hours, newest first. */
    fun missedCalls(): List<String>? {
        if (!granted(Manifest.permission.READ_CALL_LOG)) return null
        val since = System.currentTimeMillis() - 24 * 3600_000L
        val out = mutableListOf<String>()
        ctx.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER),
            "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.DATE} > ?",
            arrayOf(CallLog.Calls.MISSED_TYPE.toString(), since.toString()),
            "${CallLog.Calls.DATE} DESC"
        )?.use { c ->
            while (c.moveToNext() && out.size < 10) {
                val name = c.getString(0)?.takeIf { it.isNotBlank() } ?: c.getString(1).orEmpty()
                if (name.isNotBlank()) out.add(name)
            }
        }
        return out
    }

    @SuppressLint("MissingPermission")
    fun answerCall(): Boolean {
        if (!granted(Manifest.permission.ANSWER_PHONE_CALLS)) return false
        return runCatching {
            @Suppress("DEPRECATION")
            ctx.getSystemService(TelecomManager::class.java).acceptRingingCall()
            true
        }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    fun endCall(): Boolean {
        if (Build.VERSION.SDK_INT < 28 || !granted(Manifest.permission.ANSWER_PHONE_CALLS)) return false
        return runCatching {
            @Suppress("DEPRECATION")
            ctx.getSystemService(TelecomManager::class.java).endCall()
        }.getOrDefault(false)
    }

    // ---------------- Calendar ----------------

    data class Event(val title: String, val begin: Long, val allDay: Boolean)

    fun events(day: LocalDate): List<Event>? {
        if (!granted(Manifest.permission.READ_CALENDAR)) return null
        val zone = ZoneId.systemDefault()
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, start)
            ContentUris.appendId(it, end)
        }.build()
        val out = mutableListOf<Event>()
        ctx.contentResolver.query(
            uri,
            arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY),
            null, null, "${CalendarContract.Instances.BEGIN} ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                out.add(Event(c.getString(0) ?: "Untitled event", c.getLong(1), c.getInt(2) == 1))
            }
        }
        return out
    }

    /**
     * Adds an event (1 hour, or all-day when no time is given) with an alert [reminderMinutes] before it.
     * Reminders use 0, so the phone's calendar alerts you exactly on time. Null = no permission.
     */
    fun addEvent(title: String, date: LocalDate, time: LocalTime?, reminderMinutes: Int = 10): Boolean? {
        if (!granted(Manifest.permission.WRITE_CALENDAR) || !granted(Manifest.permission.READ_CALENDAR)) return null
        val calId = writableCalendar() ?: return false
        return try {
            val zone = ZoneId.systemDefault()
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.HAS_ALARM, if (time != null) 1 else 0)
                if (time == null) {
                    val utcStart = date.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
                    put(CalendarContract.Events.DTSTART, utcStart)
                    put(CalendarContract.Events.DTEND, utcStart + 24 * 3600_000L)
                    put(CalendarContract.Events.ALL_DAY, 1)
                    put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
                } else {
                    val start = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
                    put(CalendarContract.Events.DTSTART, start)
                    put(CalendarContract.Events.DTEND, start + 3600_000L)
                    put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
                }
            }
            val uri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: return false
            if (time != null) {
                val eventId = ContentUris.parseId(uri)
                ctx.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, eventId)
                    put(CalendarContract.Reminders.MINUTES, reminderMinutes)
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                })
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun writableCalendar(): Long? {
        ctx.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${CalendarContract.Calendars.VISIBLE} = 1",
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
            null
        )?.use { c ->
            var first: Long? = null
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (c.getInt(1) == 1) return id
                if (first == null) first = id
            }
            return first
        }
        return null
    }

    // ---------------- Location ----------------

    @SuppressLint("MissingPermission")
    suspend fun location(): Location? {
        if (!granted(Manifest.permission.ACCESS_COARSE_LOCATION) && !granted(Manifest.permission.ACCESS_FINE_LOCATION)) return null
        val lm = ctx.getSystemService(LocationManager::class.java)
        val providers = lm.getProviders(true)
        val last = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < 30 * 60_000L) return last
        if (Build.VERSION.SDK_INT >= 30 && providers.isNotEmpty()) {
            val provider = listOf("fused", LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).firstOrNull { it in providers }
                ?: providers.first()
            val fresh = withTimeoutOrNull(12_000) {
                suspendCancellableCoroutine<Location?> { cont ->
                    val signal = CancellationSignal()
                    cont.invokeOnCancellation { signal.cancel() }
                    lm.getCurrentLocation(provider, signal, ctx.mainExecutor) { loc -> if (cont.isActive) cont.resume(loc) }
                }
            }
            if (fresh != null) return fresh
        }
        return last
    }

    /** "Koramangala, Bengaluru" for a location, using the phone's free geocoder. */
    suspend fun placeName(loc: Location): String? = withContext(Dispatchers.IO) {
        try {
            @Suppress("DEPRECATION")
            val a = Geocoder(ctx, Locale.ENGLISH).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()
                ?: return@withContext null
            listOfNotNull(a.subLocality, a.locality ?: a.subAdminArea, a.adminArea).distinct().joinToString(", ").ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }

    // ---------------- Navigation, camera, settings panels ----------------

    fun navigate(place: String): Launch {
        val maps = "com.google.android.apps.maps"
        val intent = if (installed(maps)) {
            Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(place))).setPackage(maps)
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(place)))
        }
        return launch(intent, "navigate to $place")
    }

    fun camera(selfie: Boolean): Launch {
        val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        if (selfie) {
            intent.putExtra("android.intent.extras.CAMERA_FACING", 1)
            intent.putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
            intent.putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
        }
        return launch(intent, "open the camera")
    }

    enum class Panel { WIFI, BLUETOOTH, INTERNET }

    fun settingsPanel(p: Panel): Launch {
        val intent = when (p) {
            Panel.WIFI -> Intent(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS)
            Panel.BLUETOOTH -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            Panel.INTERNET -> Intent(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIRELESS_SETTINGS)
        }
        return launch(intent, "open settings")
    }

    // ---------------- Do not disturb & brightness ----------------

    /** Null if Diya hasn't been given Do Not Disturb access. */
    fun doNotDisturb(on: Boolean): Boolean? {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) return null
        return runCatching {
            nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
            true
        }.getOrDefault(false)
    }

    enum class Brightness { UP, DOWN, SET }

    /** Returns the new brightness in percent, or null if "Modify system settings" isn't allowed. */
    fun brightness(kind: Brightness, percent: Int = 0): Int? {
        if (!Settings.System.canWrite(ctx)) return null
        val cr = ctx.contentResolver
        return runCatching {
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            val cur = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
            val next = when (kind) {
                Brightness.UP -> cur + 50
                Brightness.DOWN -> cur - 50
                Brightness.SET -> percent.coerceIn(0, 100) * 255 / 100
            }.coerceIn(5, 255)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, next)
            next * 100 / 255
        }.getOrNull()
    }

    fun has(p: String) = granted(p)

    private fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val YOUTUBE = "com.google.android.youtube"
        private const val YT_MUSIC = "com.google.android.apps.youtube.music"
    }
}
