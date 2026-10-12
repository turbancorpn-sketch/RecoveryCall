package com.recoverycall.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.UUID

class MainActivity : Activity() {

    private val REQ_PERMS = 10
    private val REQ_CSV = 20
    private val REQ_AUDIO = 21

    private lateinit var toggleBtn: Button
    private lateinit var statusTv: TextView
    private lateinit var recStatus: TextView
    private lateinit var recBtn: Button
    private lateinit var contactsBox: LinearLayout
    private lateinit var logTv: TextView
    private val fields = linkedMapOf<String, EditText>()

    private var recorder: MediaRecorder? = null
    private var preview: MediaPlayer? = null
    private val ui = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 5000) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(text: String, size: Float = 14f, bold: Boolean = false, color: Int = Color.parseColor("#222222")) =
        TextView(this).apply {
            this.text = text; textSize = size; setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(4))
        }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun edit(hint: String, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(this).apply {
        this.hint = hint; inputType = type; setSingleLine()
    }

    private fun section(parent: LinearLayout, title: String) {
        parent.addView(View(this).apply { setBackgroundColor(Color.parseColor("#DDDDDD")) },
            LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(16) })
        parent.addView(label(title, 18f, true, Color.parseColor("#0B5FA5")))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(40))
        }
        setContentView(ScrollView(this).apply { addView(root) })

        root.addView(label("Recovery Caller", 26f, true))
        root.addView(label("Automatic payment-reminder calls with your own recorded voice.", 14f, false, Color.GRAY))

        // ---------------- run control
        statusTv = label("", 15f, true)
        root.addView(statusTv)
        val consent = CheckBox(this).apply {
            text = "I confirm every person on my list has agreed to receive these calls and I will honour Do-Not-Call requests."
            isChecked = Store.bool(this@MainActivity, "consent")
            setOnCheckedChangeListener { _, v -> Store.setBool(this@MainActivity, "consent", v) }
        }
        root.addView(consent)
        toggleBtn = button("Start auto-dialer") { toggleRun() }
        root.addView(toggleBtn)
        root.addView(button("Allow background running (battery)") { askBattery() })

        // ---------------- recording
        section(root, "1. Recorded message")
        recStatus = label("")
        root.addView(recStatus)
        recBtn = button("Record message") { toggleRecord() }
        root.addView(recBtn)
        root.addView(button("Play recording") { playPreview() })
        root.addView(button("Choose an audio file instead") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "audio/*"
            }, REQ_AUDIO)
        })

        // ---------------- schedule
        section(root, "2. Schedule rules")
        val names = linkedMapOf(
            "startHour" to "Start calling at hour (0-23)",
            "endHour" to "Stop calling at hour (0-23)",
            "retryMin" to "Redial if unanswered after (minutes)",
            "maxTries" to "Max dial attempts per person per day",
            "repeatDays" to "Repeat reminder every (days)",
            "maxReminders" to "Stop after this many delivered reminders",
            "ringWait" to "Seconds to wait before playing message"
        )
        names.forEach { (k, n) ->
            root.addView(label(n, 13f, false, Color.GRAY))
            val e = edit(n, InputType.TYPE_CLASS_NUMBER)
            e.setText(Store.setting(this, k).toString())
            fields[k] = e
            root.addView(e)
        }
        root.addView(button("Save schedule rules") { saveSettings() })

        // ---------------- contacts
        section(root, "3. People to call")
        val nameE = edit("Name"); val phoneE = edit("Phone (with country code)", InputType.TYPE_CLASS_PHONE)
        val amtE = edit("Amount due (optional)")
        root.addView(nameE); root.addView(phoneE); root.addView(amtE)
        root.addView(button("Add person") {
            val ph = phoneE.text.toString().trim()
            if (ph.filter { it.isDigit() }.length < 6) { toast("Enter a valid phone number"); return@button }
            val list = Store.contacts(this)
            list.add(Contact(UUID.randomUUID().toString(), nameE.text.toString().trim().ifEmpty { ph }, ph, amtE.text.toString().trim()))
            Store.saveContacts(this, list)
            nameE.setText(""); phoneE.setText(""); amtE.setText("")
            refresh()
        })
        root.addView(button("Import CSV (name,phone,amount)") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            }, REQ_CSV)
        })
        contactsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(contactsBox)

        // ---------------- log
        section(root, "Activity log")
        logTv = label("", 12f)
        root.addView(logTv)
    }

    override fun onResume() {
        super.onResume()
        ui.post(refresher)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(refresher)
        if (recorder != null) toggleRecord()
        stopPreview()
    }

    // ------------------------------------------------------------------ UI refresh

    private fun refresh() {
        val on = Store.bool(this, "enabled")
        statusTv.text = if (on) "Status: RUNNING" else "Status: stopped"
        statusTv.setTextColor(if (on) Color.parseColor("#1B7F3B") else Color.parseColor("#B00020"))
        toggleBtn.text = if (on) "Stop auto-dialer" else "Start auto-dialer"
        recStatus.text = if (Store.hasRecording(this)) "Message ready (${Store.recordingFile(this).length() / 1024} KB)" else "No message yet - record or choose a file."
        recBtn.text = if (recorder != null) "Stop recording" else "Record message"
        logTv.text = Store.logText(this)

        contactsBox.removeAllViews()
        val list = Store.contacts(this)
        if (list.isEmpty()) contactsBox.addView(label("No one added yet.", 13f, false, Color.GRAY))
        for (c in list) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setBackgroundColor(Color.parseColor("#F3F6FA"))
            }
            row.addView(label("${c.name}  -  ${c.phone}" + if (c.amount.isNotEmpty()) "  -  ${c.amount}" else "", 15f, true))
            row.addView(label("${c.status} | reminders: ${c.reminders} | ${c.last}", 12f, false, Color.GRAY))
            val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.START }
            fun small(t: String, f: () -> Unit) = Button(this).apply {
                text = t; isAllCaps = false; textSize = 12f; setOnClickListener { f(); refresh() }
            }.also { btns.addView(it) }
            small("Call now") { upd(c.id) { it.status = "ACTIVE"; it.nextAt = 0; it.triesToday = 0 } }
            small("Paid") { upd(c.id) { it.status = "PAID"; it.last = "Marked paid" } }
            small("Do not call") { upd(c.id) { it.status = "DND"; it.last = "Opted out" } }
            small("Delete") {
                val l = Store.contacts(this); l.removeAll { it.id == c.id }; Store.saveContacts(this, l)
            }
            row.addView(btns)
            contactsBox.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
    }

    private fun upd(id: String, f: (Contact) -> Unit) {
        val l = Store.contacts(this)
        l.firstOrNull { it.id == id }?.let(f)
        Store.saveContacts(this, l)
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    private fun saveSettings() {
        for ((k, e) in fields) {
            val v = e.text.toString().toIntOrNull() ?: Store.defaults[k]!!
            Store.setInt(this, k, v.coerceAtLeast(if (k == "startHour" || k == "endHour") 0 else 1))
        }
        toast("Saved")
    }

    // ------------------------------------------------------------------ start / stop

    private fun neededPerms(): Array<String> {
        val l = mutableListOf(
            Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS)
        return l.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
    }

    private fun toggleRun() {
        if (Store.bool(this, "enabled")) {
            Store.setBool(this, "enabled", false)
            startService(Intent(this, DialerService::class.java).setAction(DialerService.ACTION_STOP))
            refresh(); return
        }
        if (!Store.bool(this, "consent")) { toast("Please tick the consent box first"); return }
        if (!Store.hasRecording(this)) { toast("Add your recorded message first"); return }
        if (Store.contacts(this).none { it.status == "ACTIVE" }) { toast("Add at least one person"); return }
        saveSettings()
        val need = neededPerms()
        if (need.isNotEmpty()) { requestPermissions(need, REQ_PERMS); return }
        startRun()
    }

    private fun startRun() {
        Store.setBool(this, "enabled", true)
        startForegroundService(Intent(this, DialerService::class.java))
        refresh()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code != REQ_PERMS) return
        if (neededPerms().none { it != Manifest.permission.POST_NOTIFICATIONS }) startRun()
        else toast("Phone, call-control and microphone permissions are all required")
    }

    private fun askBattery() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) { toast("Already allowed"); return }
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    // ------------------------------------------------------------------ recording

    private fun toggleRecord() {
        if (recorder != null) {
            try { recorder?.stop() } catch (_: Exception) { Store.recordingFile(this).delete() }
            recorder?.release(); recorder = null
            refresh(); return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 11); return
        }
        stopPreview()
        try {
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(44100)
            r.setAudioEncodingBitRate(96000)
            r.setOutputFile(Store.recordingFile(this).absolutePath)
            r.prepare(); r.start()
            recorder = r
            toast("Recording... tap Stop when finished")
        } catch (e: Exception) {
            toast("Could not record: ${e.message}")
        }
        refresh()
    }

    private fun playPreview() {
        if (!Store.hasRecording(this)) { toast("No recording yet"); return }
        stopPreview()
        try {
            preview = MediaPlayer().apply {
                setDataSource(Store.recordingFile(this@MainActivity).absolutePath)
                prepare(); start()
            }
        } catch (e: Exception) { toast("Cannot play: ${e.message}") }
    }

    private fun stopPreview() {
        try { preview?.stop() } catch (_: Exception) {}
        try { preview?.release() } catch (_: Exception) {}
        preview = null
    }

    // ------------------------------------------------------------------ file pickers

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        try {
            if (requestCode == REQ_AUDIO) {
                contentResolver.openInputStream(uri)!!.use { i ->
                    Store.recordingFile(this).outputStream().use { o -> i.copyTo(o) }
                }
                toast("Audio file loaded")
            } else if (requestCode == REQ_CSV) {
                val list = Store.contacts(this)
                var added = 0
                contentResolver.openInputStream(uri)!!.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val p = line.split(",").map { it.trim().trim('"') }
                        if (p.size >= 2 && p[1].count { it.isDigit() } >= 6 && list.none { it.phone == p[1] }) {
                            list.add(Contact(UUID.randomUUID().toString(), p[0].ifEmpty { p[1] }, p[1], p.getOrElse(2) { "" }))
                            added++
                        }
                    }
                }
                Store.saveContacts(this, list)
                toast("Imported $added people")
            }
        } catch (e: Exception) {
            toast("Failed: ${e.message}")
        }
        refresh()
    }
}
