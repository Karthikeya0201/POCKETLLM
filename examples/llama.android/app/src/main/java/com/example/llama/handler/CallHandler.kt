package com.example.llama.handler

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import android.llama.cpp.LLamaAndroid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CallHandler(private val context: Context, private val llama: LLamaAndroid) {
    suspend fun handleCall(userInput: String): String {
        Log.d("CallHandler", "Processing call intent for input: $userInput")
        // Prompt to extract contact name or phone number
        val callPrompt = """
        Extract the contact name or phone number from the following user input for making a call.
        Return a JSON object with one field: "contact" (the contact name or phone number).
        If no clear contact name or phone number is found, return an empty string for "contact".
        Input: $userInput
        Example output: {"contact": "Alice"} or {"contact": "+1234567890"}
        """
        return try {
            val contactJson = withContext(Dispatchers.IO) {
                var result = ""
                llama.send(callPrompt).collect { partial ->
                    result += partial // Aggregate streaming response
                }
                result
            }
            // Parse JSON (simplified; use kotlinx.serialization in practice)
            val contact = if (contactJson.contains("\"contact\"")) {
                contactJson.substringAfter("\"contact\":\"").substringBefore("\"}")
            } else {
                ""
            }
            Log.d("CallHandler", "Extracted contact: $contact")

            if (contact.isBlank()) {
                return "No contact or phone number found in input."
            }

            // Check if contact is a phone number
            val phoneNumber = if (contact.matches(Regex("\\+?\\d{10,15}"))) {
                contact
            } else {
                // Resolve contact name to phone number
                resolveContactPhoneNumber(context.contentResolver, contact)
            }

            when {
                phoneNumber.isNullOrBlank() -> {
                    "No phone number found for contact '$contact'."
                }
                phoneNumber == "multiple" -> {
                    "Multiple contacts found for '$contact'. Please specify."
                }
                else -> {
                    // Initiate call
                    val intent = Intent(Intent.ACTION_DIAL).apply {
                        data = Uri.parse("tel:$phoneNumber")
                    }
                    context.startActivity(intent)
                    "Initiating call to $contact ($phoneNumber)"
                }
            }
        } catch (e: Exception) {
            Log.e("CallHandler", "Call handling error: ${e.message}")
            "Error processing call: ${e.message}"
        }
    }

    private fun resolveContactPhoneNumber(contentResolver: ContentResolver, contactName: String): String? {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$contactName%")

        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )

        return cursor?.use {
            var phoneNumber: String? = null
            var count = 0
            while (it.moveToNext()) {
                val number = it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
                if (number.isNotBlank()) {
                    phoneNumber = number.replace("[^0-9+]".toRegex(), "")
                    count++
                }
            }
            if (count > 1) "multiple" else phoneNumber
        }
    }
}