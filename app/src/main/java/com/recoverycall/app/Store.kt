package com.recoverycall.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class Contact(
    val id: String,
    var name: String,
    var phone: String,
    var amount: String,
    var status: String = "ACTIVE", // ACTIVE, PAID, DND, DONE
    var reminders: Int = 0,
    var triesToday: Int = 0,
    var triesDay: Int = -1,
    var nextAt: Long = 0L,
    var last: String = "Not called yet"
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("phone", phone).put("amount", amount)
        .put("status", status).put("reminders", reminders).put("triesToday", triesToday)
        .put("triesDay", triesDay).put("nextAt", nextAt).put("last", last)

    companion object {
        fun from(o: JSONObject) = Contact(
            o.getString("id"), o.getString("name"), o.getString("phone"), o.optString("amount", ""),
            o.optString("status", "ACTIVE"), o.optInt("reminders", 0), o.optInt("triesToday", 0),
            o.optInt("triesDay", -1), o.optLong("nextAt", 0L), o.optString("last", "")
        )
    }
}

object Store {
    private fun p(c: Context) = c.getSharedPreferences("rc", Context.MODE_PRIVATE)

    fun contacts(c: Context): MutableList<Contact> {
        val a = JSONArray(p(c).getString("contacts", "[]"))
        return MutableList(a.length()) { Contact.from(a.getJSONObject(it)) }
    }

    fun saveContacts(c: Context, l: List<Contact>) {
        val a = JSONArray()
        l.forEach { a.put(it.toJson()) }
        p(c).edit().putString("contacts", a.toString()).apply()
    }

    fun int(c: Context, k: String, d: Int) = p(c).getInt(k, d)
    fun setInt(c: Context, k: String, v: Int) = p(c).edit().putInt(k, v).apply()
    fun bool(c: Context, k: String) = p(c).getBoolean(k, false)
    fun setBool(c: Context, k: String, v: Boolean) = p(c).edit().putBoolean(k, v).apply()

    // Defaults for settings
    val defaults = linkedMapOf(
        "startHour" to 9,      // earliest call hour (24h)
        "endHour" to 19,       // calls stop at this hour
        "retryMin" to 30,      // minutes before redial when unanswered
        "maxTries" to 3,       // max dial attempts per contact per day
        "repeatDays" to 3,     // days between reminders after a message was delivered
        "maxReminders" to 5,   // stop after this many delivered reminders
        "ringWait" to 18       // seconds to wait after dialing before playing message
    )

    fun setting(c: Context, k: String) = int(c, k, defaults[k]!!)

    fun recordingFile(c: Context) = File(c.filesDir, "recording.m4a")
    fun hasRecording(c: Context) = recordingFile(c).let { it.exists() && it.length() > 1000 }

    fun today(): Int {
        val cal = Calendar.getInstance()
        return cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
    }

    fun log(c: Context, msg: String) {
        val t = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date())
        val old = (p(c).getString("log", "") ?: "").lines().filter { it.isNotBlank() }.takeLast(39)
        p(c).edit().putString("log", (old + "$t  $msg").joinToString("\n")).apply()
    }

    fun logText(c: Context) = (p(c).getString("log", "") ?: "").lines().filter { it.isNotBlank() }.reversed().joinToString("\n")
}
