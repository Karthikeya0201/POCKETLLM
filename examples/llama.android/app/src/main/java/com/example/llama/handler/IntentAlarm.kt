package com.example.llama.handler

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.Log
import android.widget.Toast
import java.util.regex.Pattern

class IntentAlarm(private val context: Context) {

    companion object {
        private const val TAG = "IntentAlarm"
        private val TIME_PATTERN = Pattern.compile("^([01]\\d|2[0-3]):([0-5]\\d)$")
    }

    fun setAlarm(command: String): Boolean {
        if (command.isBlank()) {
            showToast("Command cannot be empty")
            Log.w(TAG, "Empty command provided")
            return false
        }

        val time = extractTime(command)
        if (!isValidTime(time)) {
            showToast("Invalid time format: $time. Use HH:mm (24-hour)")
            Log.w(TAG, "Invalid time format: $time")
            return false
        }

        return scheduleClockAppAlarm(time)
    }

    private fun extractTime(command: String): String {
        val parts = command.trim().split("\\s+".toRegex())
        val time = parts.find { TIME_PATTERN.matcher(it).matches() } ?: ""
        return time
    }

    private fun isValidTime(time: String): Boolean {
        return TIME_PATTERN.matcher(time).matches()
    }

    private fun scheduleClockAppAlarm(time: String): Boolean {
        val (hour, minute) = time.split(":").map { it.toInt() }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, "Scheduled by PocketLLM App")
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }

        try {
            // Check if there's an activity to handle the intent
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                Log.d(TAG, "Alarm intent sent for $time")
                return true
            } else {
                showToast("No Clock app found to set alarm")
                Log.e(TAG, "No activity found to handle ACTION_SET_ALARM")
                return false
            }
        } catch (e: Exception) {
            showToast("Failed to set alarm: ${e.message}")
            Log.e(TAG, "Exception setting Clock app alarm: ${e.message}", e)
            return false
        }
    }

    private fun showToast(message: String, duration: Int = Toast.LENGTH_LONG) {
        Toast.makeText(context, message, duration).show()
    }
}
