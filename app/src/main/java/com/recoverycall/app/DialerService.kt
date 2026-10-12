package com.recoverycall.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import java.util.Calendar

/**
 * Foreground service that runs the redial schedule:
 *  - every 20s looks for the next due contact inside the allowed calling window
 *  - dials via the phone's SIM, waits [ringWait] seconds after the call goes off-hook,
 *    plays the recorded message on the loudspeaker, then hangs up
 *  - unanswered: redial after [retryMin], up to [maxTries] per day
 *  - delivered: remind again after [repeatDays], up to [maxReminders] times
 */
class DialerService : Service() {

    companion object {
        const val ACTION_STOP = "com.recoverycall.app.STOP"
        private const val CHANNEL = "dialer"
        private const val DIAL_TIMEOUT_MS = 45_000L
    }

    private val h = Handler(Looper.getMainLooper())
    private var wl: PowerManager.WakeLock? = null
    private var current: Contact? = null
    private var phase = 0 // 0 idle, 1 dialing, 2 off-hook waiting, 3 playing message, 4 closing
    private var player: MediaPlayer? = null
    private var rxRegistered = false

    private val tick = object : Runnable {
        override fun run() {
            try {
                doTick()
            } catch (e: Exception) {
                Store.log(this@DialerService, "Error: ${e.message}")
                resetCall()
            }
            h.postDelayed(this, 20_000)
        }
    }

    private val dialTimeout = Runnable {
        if (phase == 1) fail("Could not connect")
    }

    private val playRunnable = Runnable {
        if (phase == 2) play()
    }

    private val stateRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            onPhone(i.getStringExtra(TelephonyManager.EXTRA_STATE))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Store.setBool(this, "enabled", false)
            shutdown()
            return START_NOT_STICKY
        }
        startInForeground()
        if (!rxRegistered) {
            registerReceiver(stateRx, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED))
            rxRegistered = true
            val pm = getSystemService(PowerManager::class.java)
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "recoverycall:dialer").apply { acquire() }
            Store.log(this, "Auto-dialer started")
            h.removeCallbacks(tick)
            h.post(tick)
        }
        return START_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Auto-dialer", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, DialerService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Recovery Caller is running")
            .setContentText("Placing scheduled reminder calls")
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    private fun shutdown() {
        h.removeCallbacksAndMessages(null)
        resetCall()
        if (rxRegistered) {
            try { unregisterReceiver(stateRx) } catch (_: Exception) {}
            rxRegistered = false
        }
        try { wl?.release() } catch (_: Exception) {}
        wl = null
        Store.log(this, "Auto-dialer stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        if (rxRegistered) {
            try { unregisterReceiver(stateRx) } catch (_: Exception) {}
            rxRegistered = false
        }
        try { wl?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    // ---------------------------------------------------------------- scheduling

    private fun doTick() {
        if (!Store.bool(this, "enabled")) {
            shutdown(); return
        }
        if (phase != 0) return

        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (hour < Store.setting(this, "startHour") || hour >= Store.setting(this, "endHour")) return

        if (!Store.hasRecording(this)) {
            Store.log(this, "No recorded message - add one in the app"); return
        }
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            Store.log(this, "Phone permission missing"); return
        }

        val list = Store.contacts(this)
        val today = Store.today()
        val maxTries = Store.setting(this, "maxTries")
        list.forEach { if (it.triesDay != today) { it.triesDay = today; it.triesToday = 0 } }
        val now = System.currentTimeMillis()
        val next = list.filter { it.status == "ACTIVE" && it.nextAt <= now && it.triesToday < maxTries }
            .minByOrNull { it.nextAt }
        Store.saveContacts(this, list)
        if (next != null) startCall(next.id)
    }

    private fun mutate(id: String, f: (Contact) -> Unit) {
        val list = Store.contacts(this)
        list.firstOrNull { it.id == id }?.let(f)
        Store.saveContacts(this, list)
    }

    // ---------------------------------------------------------------- call flow

    @SuppressLint("MissingPermission")
    private fun startCall(id: String) {
        val c = Store.contacts(this).firstOrNull { it.id == id } ?: return
        val number = c.phone.filter { it.isDigit() || it == '+' }
        if (number.length < 6) {
            mutate(id) { it.status = "DONE"; it.last = "Invalid number" }
            return
        }
        current = c
        phase = 1
        mutate(id) { it.triesToday++; it.last = "Dialing..." }
        Store.log(this, "Dialing ${c.name}")
        val uri = Uri.fromParts("tel", number, null)
        try {
            getSystemService(TelecomManager::class.java).placeCall(uri, Bundle())
        } catch (e: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e2: Exception) {
                fail("Dial failed: ${e2.message}"); return
            }
        }
        h.postDelayed(dialTimeout, DIAL_TIMEOUT_MS)
    }

    private fun onPhone(state: String?) {
        when (state) {
            TelephonyManager.EXTRA_STATE_OFFHOOK -> if (phase == 1) {
                phase = 2
                h.removeCallbacks(dialTimeout)
                h.postDelayed(playRunnable, Store.setting(this, "ringWait") * 1000L)
            }
            TelephonyManager.EXTRA_STATE_IDLE -> when (phase) {
                2 -> fail("No answer / declined")
                3 -> deliver("Hung up during message")
            }
        }
    }

    private fun play() {
        val c = current ?: return
        val am = getSystemService(AudioManager::class.java)
        try {
            am.isSpeakerphoneOn = true
            am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(Store.recordingFile(this).absolutePath)
            mp.setOnCompletionListener { deliver("Message delivered") }
            mp.setOnErrorListener { _, _, _ -> fail("Playback error"); true }
            mp.prepare()
            phase = 3
            mutate(c.id) { it.last = "Playing message..." }
            player = mp
            mp.start()
        } catch (e: Exception) {
            fail("Playback failed: ${e.message}")
        }
    }

    /** Message played (fully or partly): schedule the next reminder. */
    private fun deliver(note: String) {
        val c = current
        stopPlayer()
        if (c != null) {
            val max = Store.setting(this, "maxReminders")
            val gap = Store.setting(this, "repeatDays") * 86_400_000L
            mutate(c.id) {
                it.reminders++
                it.nextAt = System.currentTimeMillis() + gap
                it.last = "$note (${timeNow()})"
                if (it.reminders >= max) { it.status = "DONE"; it.last += " - reminder limit reached" }
            }
            Store.log(this, "${c.name}: $note")
        }
        phase = 4 // closing: ignore further events until the hang-up finishes
        h.postDelayed({ hangUp(); resetCall() }, 1200)
    }

    /** Not answered / failed: redial after the retry gap. */
    private fun fail(reason: String) {
        val c = current
        h.removeCallbacks(dialTimeout)
        h.removeCallbacks(playRunnable)
        stopPlayer()
        hangUp()
        if (c != null) {
            val gap = Store.setting(this, "retryMin") * 60_000L
            mutate(c.id) {
                it.nextAt = System.currentTimeMillis() + gap
                it.last = "$reason (${timeNow()})"
            }
            Store.log(this, "${c.name}: $reason")
        }
        resetCall()
    }

    private fun resetCall() {
        h.removeCallbacks(dialTimeout)
        h.removeCallbacks(playRunnable)
        stopPlayer()
        try { getSystemService(AudioManager::class.java).isSpeakerphoneOn = false } catch (_: Exception) {}
        current = null
        phase = 0
    }

    private fun stopPlayer() {
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
    }

    @SuppressLint("MissingPermission")
    private fun hangUp() {
        try {
            if (Build.VERSION.SDK_INT >= 28 &&
                checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED
            ) {
                getSystemService(TelecomManager::class.java).endCall()
            }
        } catch (_: Exception) {}
    }

    private fun timeNow(): String {
        val c = Calendar.getInstance()
        return "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }
}
