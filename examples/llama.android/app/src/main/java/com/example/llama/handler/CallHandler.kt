package com.example.llama.handler

import android.Manifest
import android.app.AlertDialog
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.fragment.app.FragmentActivity
import kotlin.math.min

class IntentCall(private val context: Context) {

    companion object {
        private const val TAG = "IntentCall"
        private const val REQUEST_CALL_PERMISSION = 1
        private const val REQUEST_CONTACTS_PERMISSION = 2
        private val NUMBER_CLEANER = Regex("[^0-9+]")
        private val NAME_REGEX = Regex(
            """(?i)(?:call|dial|phone|contact)\s+([a-zA-Z\s]+)""",
            setOf(RegexOption.IGNORE_CASE)
        )
    }

    fun initiateCall(command: String): Boolean {
        if (command.isBlank()) {
            showToast("Command cannot be empty")
            Log.w(TAG, "Empty command provided")
            return false
        }

        val contactName = extractContactName(command)
        if (contactName.isBlank()) {
            showToast("No valid contact name found in command")
            Log.w(TAG, "No valid contact name extracted from command: $command")
            return false
        }

        Log.d(TAG, "Initiating call for contact: $contactName")
        return if (isPhoneNumber(contactName)) {
            callPhoneNumber(contactName)
        } else {
            queryContactsAndProceed(contactName)
        }
    }

    private fun extractContactName(command: String): String {
        val matchResult = NAME_REGEX.find(command)
        return matchResult?.groups?.get(1)?.value?.trim() ?: ""
    }

    private fun queryContactsAndProceed(name: String): Boolean {
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
                callPhoneNumber(contacts[0].second[0])
            }
            else -> {
                showContactSelectionPopup(contacts)
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

    private fun showContactSelectionPopup(contacts: List<Triple<String, List<String>, Int>>) {
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
                callPhoneNumber(selected.second)
            }
            setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            setCancelable(true)
        }.show()
    }

    private fun callPhoneNumber(number: String): Boolean {
        if (!hasPermission(Manifest.permission.CALL_PHONE)) {
            requestPermission(Manifest.permission.CALL_PHONE, REQUEST_CALL_PERMISSION)
            showToast("Permission to make calls is required")
            Log.e(TAG, "CALL_PHONE permission not granted")
            return false
        }

        return try {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$number")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            showToast("Calling $number...", Toast.LENGTH_SHORT)
            Log.d(TAG, "Initiated call to: $number")
            true
        } catch (e: Exception) {
            showToast("Failed to initiate call: ${e.message}")
            Log.e(TAG, "Exception during call: ${e.message}", e)
            false
        }
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
