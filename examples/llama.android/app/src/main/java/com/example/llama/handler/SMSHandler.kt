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

class SMSHandler(private val context: Context, private val llama: LLamaAndroid) {
    suspend fun handleSMS(userInput: String): String {
        Log.d("SMSHandler", "Processing SMS intent for input: $userInput")
        // Prompt to extract contact name or phone number and message
        val smsPrompt = """
        Extract the contact name (or phone number) and message content from the following user input for sending an SMS.
        Return a JSON object with two fields: "contact" (the contact name or phone number) and "message" (the SMS content).
        If no contact or message is found, return empty strings for those fields.
        Input: $userInput
        Example output: {"contact": "Bob", "message": "Hello, how are you?"}
        """
        return try {
            val smsJson = withContext(Dispatchers.IO) {
                var result = ""
                llama.send(smsPrompt).collect { partial ->
                    result += partial // Aggregate streaming response
                }
                result
            }
            // Parse JSON (simplified; use kotlinx.serialization in practice)
            val contact = if (smsJson.contains("\"contact\"")) {
                smsJson.substringAfter("\"contact\":\"").substringBefore("\",")
            } else {
                ""
            }
            val message = if (smsJson.contains("\"message\"")) {
                smsJson.substringAfter("\"message\":\"").substringBefore("\"}")
            } else {
                ""
            }
            Log.d("SMSHandler", "Extracted contact: $contact, message: $message")

            if (contact.isBlank() || message.isBlank()) {
                return "No contact or message found in input."
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
                    // Initiate SMS
                    val intent = Intent(Intent.ACTION_SENDTO).apply {
                        data = Uri.parse("smsto:$phoneNumber")
                        putExtra("sms_body", message)
                    }
                    context.startActivity(intent)
                    "Initiating SMS to $contact ($phoneNumber): $message"
                }
            }
        } catch (e: Exception) {
            Log.e("SMSHandler", "SMS handling error: ${e.message}")
            "Error processing SMS: ${e.message}"
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