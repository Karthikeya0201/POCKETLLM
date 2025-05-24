package com.example.llama.handler

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CalendarContract
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.*

data class CalendarEvent(
    val title: String,
    val date: String?, // Expected in yyyy-MM-dd format
    val time: String?, // Expected in HH:mm format
    val description: String? = null,
    val location: String? = null
)

class CalendarIntent(private val context: Context) {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
    }

    fun insertEvent(event: CalendarEvent, selectedCalendarId: Long?): Boolean {
        // Check calendar permissions
        if (!hasCalendarPermission()) {
            Log.w("CalendarIntent", "Calendar permissions not granted")
            Toast.makeText(context, "Calendar permissions required", Toast.LENGTH_SHORT).show()
            return false
        }

        // If no calendar ID is provided, try to get a default calendar
        val calendarId = selectedCalendarId ?: getDefaultCalendarId()
        if (calendarId == null) {
            Log.e("CalendarIntent", "No calendar available")
            Toast.makeText(context, "No calendar available", Toast.LENGTH_SHORT).show()
            return false
        }

        // Get the account name (email) for the calendar
        val accountName = getCalendarAccountName(calendarId) ?: "Unknown account"

        try {
            // Default to current date/time if not provided
            val currentCalendar = Calendar.getInstance()
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

            val dateStr = event.date?.takeIf { it != "Unknown" } ?: dateFormat.format(currentCalendar.time)
            val timeStr = event.time?.takeIf { it != "Unknown" } ?: timeFormat.format(currentCalendar.time)

            // Parse date and time
            val eventCalendar = Calendar.getInstance()
            try {
                val dateParts = dateStr.split("-")
                if (dateParts.size == 3) {
                    eventCalendar.set(
                        dateParts[0].toInt(),
                        dateParts[1].toInt() - 1, // Calendar months are 0-based
                        dateParts[2].toInt()
                    )
                } else {
                    throw IllegalArgumentException("Invalid date format")
                }
            } catch (e: Exception) {
                Log.w("CalendarIntent", "Invalid date format: $dateStr, using current date")
                eventCalendar.time = currentCalendar.time
            }

            try {
                val time = timeFormat.parse(timeStr)
                eventCalendar.set(
                    Calendar.HOUR_OF_DAY, time.hours,
                    Calendar.MINUTE, time.minutes,
                    Calendar.SECOND, 0
                )
            } catch (e: Exception) {
                Log.w("CalendarIntent", "Invalid time format: $timeStr, using current time")
                eventCalendar.time = currentCalendar.time
            }

            // Set end time (default: 1 hour after start)
            val endCalendar = eventCalendar.clone() as Calendar
            endCalendar.add(Calendar.HOUR_OF_DAY, 1)

            // Prepare event details
            val title = event.title.takeIf { it != "Unknown" } ?: "Untitled Event"
            val description = event.description ?: ""
            val location = event.location ?: ""

            // Insert event into calendar
            val values = ContentValues().apply {
                put(CalendarContract.Events.DTSTART, eventCalendar.timeInMillis)
                put(CalendarContract.Events.DTEND, endCalendar.timeInMillis)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DESCRIPTION, description)
                put(CalendarContract.Events.EVENT_LOCATION, location)
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }

            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            if (uri != null) {
                Log.d("CalendarIntent", "Event inserted: $title on $dateStr at $timeStr into calendar ID: $calendarId, account: $accountName")
                Toast.makeText(context, "Event added to calendar: $accountName", Toast.LENGTH_SHORT).show()
                return true
            } else {
                Log.e("CalendarIntent", "Failed to insert event")
                Toast.makeText(context, "Failed to insert event", Toast.LENGTH_SHORT).show()
                return false
            }
        } catch (e: Exception) {
            Log.e("CalendarIntent", "Error inserting event: ${e.message}")
            Toast.makeText(context, "Failed to insert event: ${e.message}", Toast.LENGTH_SHORT).show()
            return false
        }
    }

    private fun hasCalendarPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CALENDAR
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun getDefaultCalendarId(): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME
        )
        val selection = "${CalendarContract.Calendars.VISIBLE} = 1"

        val cursor: Cursor? = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            selection,
            null,
            null
        )

        var calendarId: Long? = null
        cursor?.use {
            if (it.moveToFirst()) {
                calendarId = it.getLong(0)
                Log.d("CalendarIntent", "Selected default calendar ID: $calendarId")
            }
        }
        return calendarId
    }

    private fun getCalendarAccountName(calendarId: Long): String? {
        val projection = arrayOf(
            CalendarContract.Calendars.ACCOUNT_NAME
        )
        val selection = "${CalendarContract.Calendars._ID} = ?"
        val selectionArgs = arrayOf(calendarId.toString())

        val cursor: Cursor? = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )

        var accountName: String? = null
        cursor?.use {
            if (it.moveToFirst()) {
                accountName = it.getString(0)
                Log.d("CalendarIntent", "Calendar ID: $calendarId, Account name: $accountName")
            }
        }
        return accountName
    }
}
