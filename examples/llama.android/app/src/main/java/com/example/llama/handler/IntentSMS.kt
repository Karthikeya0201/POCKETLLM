package com.example.llama.handler

import android.Manifest
import android.app.AlertDialog
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.telephony.SmsManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.fragment.app.FragmentActivity

class IntentSMS(private val context: Context) {

    companion object {
        private const val TAG = "IntentSMS"
        private const val REQUEST_SMS_PERMISSION = 101
        private const val REQUEST_CONTACTS_PERMISSION = 102
        private val NUMBER_CLEANER = Regex("[^0-9+]")
        private val NAME_REGEX = Regex(
            """(?i)(?:(?:text|message|sms)\s+)?([\p{L}\s\-']+)""",
            setOf(RegexOption.IGNORE_CASE)
        )
    }

    fun sendSMS(contactNameOrNumber: String, message: String): Boolean {
        if (contactNameOrNumber.isBlank() || message.isBlank()) {
            showToast("Contact name/number and message cannot be empty")
            Log.w(TAG, "Empty input provided: contact=$contactNameOrNumber, message=$message")
            return false
        }

        Log.d(TAG, "Initiating SMS for contact: $contactNameOrNumber")
        return if (isPhoneNumber(contactNameOrNumber)) {
            confirmAndSendSMS(contactNameOrNumber, message)
        } else {
            queryContactsAndProceed(contactNameOrNumber, message)
        }
    }

    private fun extractContactName(command: String): String {
        Log.d(TAG, "Extracting contact name from command: $command")
        val matchResult = NAME_REGEX.find(command)
        val name = matchResult?.groups?.get(1)?.value?.trim() ?: ""
        Log.d(TAG, "Extracted name: '$name'")
        return name
    }

    private fun queryContactsAndProceed(name: String, message: String): Boolean {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) {
            requestPermission(Manifest.permission.READ_CONTACTS, REQUEST_CONTACTS_PERMISSION)
            showToast("Permission to read contacts is required")
            Log.e(TAG, "READ_CONTACTS permission not granted")
            return false
        }

        val contacts = getFilteredContacts(context.contentResolver, name)
        return when {
            contacts.isEmpty() -> {
                showToast("No contacts found for \"$name\"")
                Log.w(TAG, "No contacts matched for: $name")
                false
            }
            contacts.size == 1 && contacts[0].second.size == 1 -> {
                confirmAndSendSMS(contacts[0].second[0], message)
            }
            else -> {
                showContactSelectionPopup(contacts, message)
                true
            }
        }
    }

    private fun getFilteredContacts(
        contentResolver: ContentResolver,
        userContactName: String
    ): List<Triple<String, List<String>, Int>> {
        val contacts: MutableList<Pair<Contact, Int>> = mutableListOf()
        contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            null, null, null, null
        )?.use { contactCursor ->
            val contactNameColumnIndex = contactCursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
            val contactIdColumnIndex = contactCursor.getColumnIndex(ContactsContract.Contacts._ID)
            while (contactCursor.moveToNext()) {
                val name = contactCursor.getString(contactNameColumnIndex) ?: continue
                val id = contactCursor.getString(contactIdColumnIndex) ?: continue
                val distance = calculateWeightedLevenshteinDistance(name.lowercase(), userContactName.lowercase())
                if (name.lowercase().contains(userContactName.lowercase()) || distance <= (name.length / 2).coerceAtMost(4)) {
                    contacts.add(Pair(Contact(name, id), distance))
                }
            }
        }

        contacts.sortWith(compareBy({ it.second }, { it.first.name }))
        val validContacts = mutableListOf<Triple<String, List<String>, Int>>()
        contacts.take(5).forEach { (contact, distance) ->
            val numbers = contact.getNumbers(contentResolver)
            if (numbers.isNotEmpty()) {
                validContacts.add(Triple(contact.name, numbers, distance))
            }
        }
        return validContacts
    }

    private fun showContactSelectionPopup(contacts: List<Triple<String, List<String>, Int>>, message: String) {
        val items = contacts.flatMap { contact ->
            contact.second.map { number ->
                "${contact.first} (${number})" to Pair(contact.first, number)
            }
        }

        val itemLabels = items.map { it.first }.toTypedArray()
        AlertDialog.Builder(context).apply {
            setTitle("Select Contact")
            setItems(itemLabels) { _, which ->
                val selected = items[which].second
                Log.d(TAG, "Selected contact: ${selected.first}, number: ${selected.second}")
                confirmAndSendSMS(selected.second, message)
            }
            setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            setCancelable(true)
        }.show()
    }

    private fun confirmAndSendSMS(phoneNumber: String, message: String): Boolean {
        if (!hasPermission(Manifest.permission.SEND_SMS)) {
            requestPermission(Manifest.permission.SEND_SMS, REQUEST_SMS_PERMISSION)
            showToast("Permission to send SMS is required")
            Log.e(TAG, "SEND_SMS permission not granted")
            return false
        }

        AlertDialog.Builder(context).apply {
            setTitle("Confirm SMS")
            setMessage("Send message to $phoneNumber?\n\nMessage: $message")
            setPositiveButton("Send") { _, _ ->
                try {
                    val smsManager = SmsManager.getDefault()
                    smsManager.sendTextMessage(phoneNumber, null, message, null, null)
                    showToast("SMS sent to $phoneNumber", Toast.LENGTH_SHORT)
                    Log.d(TAG, "SMS sent to: $phoneNumber")
                } catch (e: Exception) {
                    showToast("Failed to send SMS: ${e.message}")
                    Log.e(TAG, "Exception during SMS send: ${e.message}", e)
                }
            }
            setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            setCancelable(true)
        }.show()
        return true
    }

    private fun isPhoneNumber(input: String): Boolean {
        return input.matches(Regex("^[+]?[0-9]{7,15}$"))
    }

    private fun hasPermission(permission: String): Boolean {
        return ActivityCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermission(permission: String, requestCode: Int) {
        if (context is FragmentActivity) {
            ActivityCompat.requestPermissions(context, arrayOf(permission), requestCode)
        }
    }

    private fun showToast(message: String, duration: Int = Toast.LENGTH_LONG) {
        Toast.makeText(context, message, duration).show()
    }

    private data class Contact(val name: String, val id: String) {
        fun getNumbers(contentResolver: ContentResolver): List<String> {
            val numbers = mutableListOf<String>()
            val cleanedNumbers = mutableSetOf<String>()
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                null,
                "${ContactsContract.CommonDataKinds.Phone.NUMBER} IS NOT NULL AND ${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                arrayOf(id),
                null
            )?.use { cursor ->
                val numberColumnIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext()) {
                    val number = cursor.getString(numberColumnIndex)?.takeIf { it.isNotEmpty() } ?: continue
                    val cleanedNumber = NUMBER_CLEANER.replace(number, "")
                    if (cleanedNumber !in cleanedNumbers) {
                        numbers.add(number)
                        cleanedNumbers.add(cleanedNumber)
                    }
                }
            } ?: Log.w(TAG, "Failed to query numbers for contact: $name")
            return numbers
        }
    }

    private fun calculateWeightedLevenshteinDistance(s1: String, s2: String): Int {
        val len1 = s1.length
        val len2 = s2.length
        val dp = Array(len1 + 1) { IntArray(len2 + 1) }

        for (i in 0..len1) {
            dp[i][0] = i
        }
        for (j in 0..len2) {
            dp[0][j] = j
        }

        for (i in 1..len1) {
            for (j in 1..len2) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else {
                    val positionWeight = 1 + (i.coerceAtMost(j) - 1) / len1
                    (2 * positionWeight).toInt()
                }
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[len1][len2]
    }
}
