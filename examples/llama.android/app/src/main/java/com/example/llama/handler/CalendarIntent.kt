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
    val duration: Int? = null // Duration in minutes, null if not specified
)

class CalendarIntent(private val context: Context) {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private const val DEFAULT_DURATION_MINUTES = 30 // Fixed: Removed duplicate 'const'
    }

    fun insertEvent(event: CalendarEvent, selectedCalendarId: Long?): Boolean {
        // Check calendar permissions
        if (!hasCalendarPermission()) {
            Log.w("CalendarIntent", "Calendar permissions not granted")
            Toast.makeText(context, "Calendar permissions required", Toast.LENGTH_SHORT).show()
            return false
        }

        // Validate provided calendar ID
        if (selectedCalendarId == null) {
            Log.e("CalendarIntent", "No calendar ID provided")
            Toast.makeText(context, "No calendar selected", Toast.LENGTH_SHORT).show()
            return false
        }

        // Verify calendar ID is valid and visible
        val calendarDetails = validateCalendarId(selectedCalendarId)
        if (calendarDetails == null) {
            Log.e("CalendarIntent", "Invalid or invisible calendar ID: $selectedCalendarId")
            Toast.makeText(
                context,
                "Selected calendar is invalid or not visible",
                Toast.LENGTH_SHORT
            ).show()
            return false
        }
        val (calendarId, accountName, displayName) = calendarDetails
        Log.d(
            "CalendarIntent",
            "Using calendar ID: $calendarId, Name: $displayName, Account: $accountName"
        )

        try {
            // Validate title
            val title = event.title.takeIf { it.isNotBlank() } ?: "Untitled Event"
            if (title.isBlank()) {
                Log.w("CalendarIntent", "Event title is blank")
                Toast.makeText(context, "Event title is required", Toast.LENGTH_SHORT).show()
                return false
            }

            // Parse date (yyyy-MM-dd) or default to current date
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val currentCalendar = Calendar.getInstance()
            val selectedDate = Calendar.getInstance()

            val dateStr =
                event.date?.takeIf { it != "Unknown" } ?: dateFormat.format(currentCalendar.time)
            try {
                val dateParts = dateStr.split("-")
                if (dateParts.size == 3) {
                    selectedDate.set(
                        dateParts[0].toInt(),
                        dateParts[1].toInt() - 1, // Calendar months are 0-based
                        dateParts[2].toInt()
                    )
                } else {
                    throw IllegalArgumentException("Invalid date format")
                }
            } catch (e: Exception) {
                Log.w("CalendarIntent", "Invalid date format: $dateStr, using current date")
                selectedDate.time = currentCalendar.time
            }

            // Parse start time (HH:mm) or default to current time
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            val timeStr =
                event.time?.takeIf { it != "Unknown" } ?: timeFormat.format(currentCalendar.time)
            val startTime = try {
                timeFormat.parse(timeStr)
            } catch (e: Exception) {
                Log.w("CalendarIntent", "Invalid time format: $timeStr, using current time")
                currentCalendar.time
            }

            // Calculate start and end times
            val startCalendar = Calendar.getInstance().apply {
                time = startTime!!
                set(Calendar.YEAR, selectedDate.get(Calendar.YEAR))
                set(Calendar.MONTH, selectedDate.get(Calendar.MONTH))
                set(Calendar.DAY_OF_MONTH, selectedDate.get(Calendar.DAY_OF_MONTH))
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            val endCalendar = startCalendar.clone() as Calendar
            val durationMinutes = event.duration?.takeIf { it > 0 } ?: DEFAULT_DURATION_MINUTES
            endCalendar.add(Calendar.MINUTE, durationMinutes)

            // Prepare ContentValues (mimicking DashboardFragment.kt)
            val values = ContentValues().apply {
                put(CalendarContract.Events.DTSTART, startCalendar.timeInMillis)
                put(CalendarContract.Events.DTEND, endCalendar.timeInMillis)
                put(CalendarContract.Events.TITLE, title)
                put(
                    CalendarContract.Events.DESCRIPTION,
                    ""
                ) // Empty, as MessageHandler doesn't provide it
                put(
                    CalendarContract.Events.EVENT_LOCATION,
                    ""
                ) // Empty, as MessageHandler doesn't provide it
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }

            // Insert event
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            if (uri != null) {
                Log.d(
                    "CalendarIntent",
                    "Event inserted: $title on $dateStr at $timeStr for $durationMinutes minutes " +
                        "into calendar ID: $calendarId, account: $accountName, URI: $uri, TimeZone: ${TimeZone.getDefault().id}"
                )
                Toast.makeText(context, "Event added to calendar: $accountName", Toast.LENGTH_SHORT)
                    .show()
                return true
            } else {
                Log.e("CalendarIntent", "Failed to insert event: ContentResolver returned null")
                Toast.makeText(context, "Insert failed", Toast.LENGTH_SHORT).show()
                return false
            }
        } catch (e: SecurityException) {
            Log.e("CalendarIntent", "Permission error: ${e.message}")
            Toast.makeText(context, "Calendar permission denied", Toast.LENGTH_SHORT).show()
            return false
        } catch (e: Exception) {
            Log.e("CalendarIntent", "Error inserting event: ${e.message}")
            Toast.makeText(context, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
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

    private fun validateCalendarId(calendarId: Long): Triple<Long, String, String>? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.VISIBLE
        )
        val selection =
            "${CalendarContract.Calendars._ID} = ? AND ${CalendarContract.Calendars.VISIBLE} = 1"
        val selectionArgs = arrayOf(calendarId.toString())

        val cursor: Cursor? = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )

        var calendarDetails: Triple<Long, String, String>? = null
        cursor?.use {
            if (it.moveToFirst()) {
                val id = it.getLong(0)
                val accountName = it.getString(1) ?: "Unknown account"
                val displayName = it.getString(2) ?: "Unknown calendar"
                calendarDetails = Triple(id, accountName, displayName)
                Log.d(
                    "CalendarIntent",
                    "Validated calendar: ID=$id, Account=$accountName, Name=$displayName, Visible=1"
                )
            } else {
                Log.w("CalendarIntent", "Calendar ID $calendarId not found or not visible")
            }
        }

        return calendarDetails
    }
}
