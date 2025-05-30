package com.example.llama.handler

import android.content.Context
import android.llama.cpp.LLamaAndroid
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.example.llama.handler.rag.RAGHandler
import com.example.llama.model.ChatHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.isActive

data class MessageHandlerParams(
    val context: Context,
    val userName: String,
    val assistantName: String,
    val modelPath: String,
    val messages: SnapshotStateList<String>,
    val chatHistories: SnapshotStateList<ChatHistory>,
    val isGenerating: MutableState<Boolean>,
    val currentChatId: MutableState<String?>,
    val selectedCalendarId: MutableState<Long?>,
    val calendarList: MutableState<List<Pair<Long, String>>>,
    val chatsDir: File,
    val llama: LLamaAndroid
)

class MessageHandler {

    private var sendJob: Job? = null
    private var responseBuilder = StringBuilder()

    private lateinit var ragHandler: RAGHandler
    private fun formatChatTemplate(
        messages: List<Pair<String, String>>,
        addGenerationPrompt: Boolean = false
    ): String {
        val builder = StringBuilder()
        messages.forEachIndexed { index, (role, content) ->
            if (index == 0 && role != "system") {
                builder.append("<|im_start|>system You are a helpful assistant.<|im_end|> ")
            }
            builder.append("<|im_start|>$role $content<|im_end|> ")
        }
        if (addGenerationPrompt) {
            builder.append("<|im_start|>assistant ")
        }
        return builder.toString().trim()
    }

    private fun messagesToPairs(
        messages: List<String>,
        userName: String,
        assistantName: String,
        currentPrompt: String? = null
    ): List<Pair<String, String>> {
        val pairs = mutableListOf<Pair<String, String>>()
        messages.forEach { msg ->
            when {
                msg.startsWith("$userName: ") -> {
                    pairs.add("user" to msg.removePrefix("$userName: ").trim())
                }

                msg.startsWith("$assistantName: ") -> {
                    pairs.add("assistant" to msg.removePrefix("$assistantName: ").trim())
                }

                else -> {
                    pairs.add("assistant" to msg.trim())
                }
            }
        }
        if (currentPrompt != null) {
            pairs.add("user" to currentPrompt.trim())
        }
        return pairs
    }

    suspend fun sendMessage(
        params: MessageHandlerParams,
        prompt: String,
        scope: kotlinx.coroutines.CoroutineScope
    ) = coroutineScope {
        if (prompt.isBlank()) {
            Log.d("MessageHandler", "Empty prompt, skipping send")
            return@coroutineScope
        }

        Log.d("MessageHandler", "Sending prompt: $prompt")
        params.isGenerating.value = true
        params.messages.add("${params.userName}: $prompt")
        params.messages.add("${params.assistantName}: ")

        sendJob?.cancel()
        responseBuilder.clear()
        sendJob = scope.launch {  // Reintroduce sendJob to allow cancellation
            try {
                val wordCount = prompt.split("\\s+".toRegex()).size
                Log.d("MessageHandler", "Prompt word count: $wordCount")
                if (params.modelPath.isEmpty()) {
                    params.messages[params.messages.lastIndex] =
                        "${params.assistantName}: No model loaded. Please load a model from Settings."
                    params.isGenerating.value = false
                    Log.d("MessageHandler", "No model loaded")
                    return@launch
                }

                val questionWordsRegex = Regex(
                    """^(explain|what|why|where|how|when|who|which|describe|tell|define|clarify)\b""",
                    RegexOption.IGNORE_CASE
                )

                val twoWordCallRegex = Regex(
                    """^(call|phone|dial|contact)\s+([a-zA-Z\s\-']+)$""",
                    RegexOption.IGNORE_CASE
                )

                val smsRegex = Regex(
                    """^(sms|text|message|ping)\s+([a-zA-Z\s\-']+)\s*(.+)$""",
                    RegexOption.IGNORE_CASE
                )

                val calendarKeywordsRegex = Regex(
                    """\b(schedule|insert|plan|add)\b.*\b(event|task|meet)\b.*\b(calendar)\b""",
                    RegexOption.IGNORE_CASE
                )

                val ambiguousWordsRegex = Regex(
                    """\b(call(ing)?|phone|dial(ing)?|ring|buzz|contact|reach|talk|speak|event|meet(ing)?|schedul(e|ing)?|set|appointment|calendar|remind(er|ing)?|invite|plan|organize|sms|text|message|chat|reply|notify|ping|send|compose|today|tomorrow|soon|later|reschedul(e|ing)?)\b""",
                    RegexOption.IGNORE_CASE
                )

                val intent = when {
                    smsRegex.matches(prompt.trim()) -> {
                        Log.d("MessageHandler", "SMS pattern matched: $prompt")
                        "sms"
                    }

                    questionWordsRegex.find(prompt) != null -> {
                        Log.d("MessageHandler", "Question word detected: $prompt")
                        "chat"
                    }

                    twoWordCallRegex.matches(prompt.trim()) -> {
                        Log.d("MessageHandler", "Two-word call pattern matched: $prompt")
                        val match = twoWordCallRegex.find(prompt.trim())!!
                        val nameOrNumber = match.groupValues[2].trim()

                        Log.d("MessageHandler", "Extracted name or number: '$nameOrNumber'")
                        Toast.makeText(
                            params.context,
                            "Connecting Call Services",
                            Toast.LENGTH_SHORT
                        ).show()
                        delay(500)

                        val intentCall = IntentCall(params.context)
                        val callSuccess = intentCall.initiateCall(nameOrNumber)
                        params.messages[params.messages.lastIndex] = if (callSuccess) {
                            "${params.assistantName}: Calling $nameOrNumber..."
                        } else {
                            "${params.assistantName}: Failed to call $nameOrNumber. Ensure the contact exists."
                        }
                        Log.d(
                            "MessageHandler",
                            "Call attempt for '$nameOrNumber' success: $callSuccess"
                        )
                        return@launch
                    }

                    calendarKeywordsRegex.containsMatchIn(prompt) -> {
                        Log.d("MessageHandler", "Calendar keywords matched: $prompt")
                        "calendar"
                    }

                    ambiguousWordsRegex.containsMatchIn(prompt) -> {
                        val intentPrompt = """
                            You are an Intent Classifier. Analyze the user prompt and determine the intent.
                            The possible intents are:
                            - call: User explicitly requests to make a phone call to a person, using verbs like "call", "phone", or "dial" followed by a name.
                            - sms: User explicitly requests to send an SMS, using verbs like "sms", "text", or "message" followed by a name and message content.
                            - calendar: User requests to create or manage a calendar event.
                            - chat: User wants to engage in conversation or ask general/technical questions.
                            Rules:
                            1. Output ONLY the intent name (call, sms, calendar, or chat) in lowercase.
                            2. Do NOT include any other text, explanation, or comments.
                            3. If the intent is unclear, output "chat".
                            4. For "sms" intent:
                               - Requires explicit verbs "sms", "text", or "message" followed by a name and message content.
                               - Examples: "Text John Hello" -> sms, "SMS Sarah Meeting at 5pm" -> sms.
                            5. For "call" intent:
                               - Requires explicit verbs "call", "phone", or "dial" followed by a person's name.
                               - Examples: "Call John" -> call, "Phone Sarah" -> call.
                            6. For "calendar" intent:
                               - Look for keywords like "schedule", "set", "meeting", "event", "appointment", or "calendar".
                               - Examples: "Schedule a meeting with John tomorrow at 5pm" -> calendar.
                            7. For "chat" intent:
                               - Covers general questions, technical queries, or anything not explicitly a phone call, SMS, or calendar event.
                               - Examples: "What is call by reference?" -> chat.
                            8. Prioritize "sms" if "sms", "text", or "message" is explicitly mentioned.
                        """.trimIndent()

                        val intentChatMessages = listOf(
                            "system" to intentPrompt,
                            "user" to prompt
                        )
                        val formattedIntentPrompt =
                            formatChatTemplate(intentChatMessages, addGenerationPrompt = true)
                        Log.d("MessageHandler", "Formatted intent prompt: $formattedIntentPrompt")

                        responseBuilder.clear()
                        params.llama.send(formattedIntentPrompt, formatChat = true)
                            .collect { partialResponse ->
                                if (isActive) {
                                    responseBuilder.append(partialResponse)
                                }
                            }
                        responseBuilder.toString().trim().lowercase()
                    }

                    else -> {
                        Log.d("MessageHandler", "No ambiguous words found, defaulting to chat")
                        "chat"
                    }
                }
                Log.d("MessageHandler", "Determined intent: $intent")

                when (intent) {
                    "sms" -> {
                        val smsPrompt = """
                        You are an SMS Extractor. Follow these rules exactly:
                        1. The User Prompt describes an SMS request (e.g., "Text John Hello").
                        2. Extract the contact name or number and the message content.
                        3. Output MUST be in this format, with no additional text or explanations:
                           Contact: <Name or Number>
                           Message: <Message Content>
                        4. Contact handling:
                           - Extract the name or phone number following verbs like "sms", "text", or "message".
                           - If a phone number is provided, keep it as is.
                           - If a name is provided, use the exact name (e.g., "John", "my boss").
                        5. Message handling:
                           - Extract all text after the contact name/number as the message.
                           - If no message content is provided, use an empty string.
                        6. Do NOT include any other text, comments, or explanations.
                        7. Examples:
                           - Prompt: "Text John Hello there"
                             Contact: John
                             Message: Hello there
                           - Prompt: "SMS +1234567890 Meeting at 5pm"
                             Contact: +1234567890
                             Message: Meeting at 5pm
                           - Prompt: "Message Sarah asking Where are you"
                             Contact: Sarah
                             Message: Where are you
                        """.trimIndent()

                        val smsChatMessages = listOf(
                            "system" to smsPrompt,
                            "user" to prompt
                        )
                        val formattedSMSPrompt =
                            formatChatTemplate(smsChatMessages, addGenerationPrompt = true)
                        Log.d("MessageHandler", "Formatted SMS prompt: $formattedSMSPrompt")

                        Toast.makeText(
                            params.context,
                            "Connecting SMS Services",
                            Toast.LENGTH_SHORT
                        ).show()

                        responseBuilder.clear()
                        params.llama.send(formattedSMSPrompt, formatChat = true)
                            .collect { partialResponse ->
                                if (isActive) {
                                    responseBuilder.append(partialResponse)
                                }
                            }
                        val smsDetails = responseBuilder.toString().trim()
                        Log.d("MessageHandler", "SMS details: $smsDetails")

                        val detailsMap = smsDetails.lines().associate {
                            val (key, value) = it.split(": ", limit = 2)
                            key.trim() to value.trim()
                        }
                        val contact = detailsMap["Contact"] ?: ""
                        val message = detailsMap["Message"] ?: ""

                        if (contact.isBlank()) {
                            params.messages[params.messages.lastIndex] =
                                "${params.assistantName}: Invalid contact name or number"
                        } else {
                            val intentSMS = IntentSMS(params.context)
                            val smsSuccess = intentSMS.sendSMS(contact, message)
                            params.messages[params.messages.lastIndex] = if (smsSuccess) {
                                "${params.assistantName}: Sending SMS to $contact..."
                            } else {
                                "${params.assistantName}: Failed to send SMS to $contact. Ensure the contact exists."
                            }
                            Log.d(
                                "MessageHandler",
                                "SMS attempt for '$contact' success: $smsSuccess"
                            )
                        }
                    }

                    "call" -> {
                        val callPrompt = """
                        You are a Caller Name Extractor. Follow these rules exactly:
                            1. The User Prompt will give a call request (e.g., "Call John").
                            2. Extract ONLY the name of the person to call.
                            3. Output MUST be in this 1-line format:
                             Call <Name>
                            4. Do NOT include any other text.
                            5. Do NOT explain, greet, or comment. Output only the required format.
                            6. If no valid name is found, output an empty string.
                        """.trimIndent()

                        val callChatMessages = listOf(
                            "system" to callPrompt,
                            "user" to prompt
                        )
                        val formattedCallPrompt =
                            formatChatTemplate(callChatMessages, addGenerationPrompt = true)
                        Log.d("MessageHandler", "Formatted call prompt: $formattedCallPrompt")

                        Toast.makeText(
                            params.context,
                            "Connecting Call Services",
                            Toast.LENGTH_SHORT
                        ).show()

                        responseBuilder.clear()
                        params.llama.send(formattedCallPrompt, formatChat = true)
                            .collect { partialResponse ->
                                if (isActive) {
                                    responseBuilder.append(partialResponse)
                                }
                            }
                        val callPerson = responseBuilder.toString().trim()

                        val nameToCall = callPerson.removePrefix("Call ").trim()
                        if (nameToCall.isBlank()) {
                            params.messages[params.messages.lastIndex] =
                                "${params.assistantName}: Invalid contact name"
                        } else {
                            val intentCall = IntentCall(params.context)
                            val callSuccess = intentCall.initiateCall(nameToCall)
                            params.messages[params.messages.lastIndex] = if (callSuccess) {
                                "${params.assistantName}: Calling $nameToCall..."
                            } else {
                                "${params.assistantName}: Failed to call $nameToCall. Ensure the contact exists."
                            }
                            Log.d(
                                "MessageHandler",
                                "Call attempt for '$nameToCall' success: $callSuccess"
                            )
                        }
                    }

                    "calendar" -> {
                        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                        val calendar = Calendar.getInstance()

                        val currentDate = dateFormat.format(Date())
                        calendar.add(Calendar.DAY_OF_YEAR, 1)
                        val tomorrowDate = dateFormat.format(calendar.time)
                        calendar.time = Date()
                        calendar.add(Calendar.WEEK_OF_YEAR, 1)
                        val nextWeekDate = dateFormat.format(calendar.time)
                        calendar.time = Date()
                        while (calendar.get(Calendar.DAY_OF_WEEK) != Calendar.FRIDAY) {
                            calendar.add(Calendar.DAY_OF_YEAR, 1)
                        }
                        val thisFridayDate = dateFormat.format(calendar.time)
                        calendar.time = Date()
                        calendar.add(Calendar.DAY_OF_YEAR, 2)
                        val inTwoDaysDate = dateFormat.format(calendar.time)

                        val calendarPrompt = """
                            You are a Calendar Event Extractor. Follow these rules exactly:
                            1. The User Prompt describes a calendar event (e.g., "Set a meeting with John tomorrow at 5pm for 1 hour").
                            2. Extract the event details: title, date, time, and duration.
                            3. Output MUST be in this format, with no additional text or explanations:
                               Title: <Event Title>
                               Date: <Date in yyyy-MM-dd format>
                               Time: <Time in HH:mm format or "Unknown">
                               Duration: <Duration in minutes or "Unknown">
                            4. Date handling:
                               - Use the current date as reference: $currentDate.
                               - Convert relative dates based on the current date:
                                 - "today": Use current date.
                                 - "tomorrow": Add 1 day to current date.
                                 - "in X days" or "X days from now": Add X days to current date.
                                 - "next week": Add 7 days to current date.
                                 - "in X weeks": Add X * 7 days to current date.
                                 - Specific days (e.g., "this Friday", "next Monday"): Calculate based on current day of week.
                               - If date is missing or unclear, use current date.
                               - Always output date in yyyy-MM-dd format (e.g., "2025-05-25").
                            5. Time handling:
                               - Convert times to 24-hour format (HH:mm, e.g., "5pm" -> "17:00", "5am" -> "05:00").
                               - Handle inputs like "5pm", "5:00 pm", "5", "evening", etc.:
                                 - Explicit times (e.g., "5pm", "5:00 pm"): Convert to HH:mm.
                                 - Partial times (e.g., "5"): Assume "05:00" if no AM/PM, or clarify based on context (e.g., "5 in evening" -> "17:00").
                                 - Vague times (e.g., "evening", "morning"): Use defaults (evening: "18:00", morning: "09:00").
                               - If time is missing or unclear, output "Unknown".
                            6. Duration handling:
                               - Extract duration from phrases like "for X minutes", "for X hours", or "until Y" (e.g., "for 1 hour" -> "60", "for 30 minutes" -> "30").
                               - Convert hours to minutes (e.g., "1 hour" -> "60").
                               - If duration is missing or unclear, output "Unknown".
                            7. Title handling:
                               - If title is missing, use "Event" as default.
                               - For names, use the exact name provided (e.g., "my boss" -> "Meeting with my boss").
                               - Remove verbs like "set" or "schedule" from title (e.g., "Schedule a meeting with John" -> "Meeting with John").
                            8. Do NOT include any other text, comments, or explanations.
                            9. Examples:
                               - Prompt: "Schedule a meeting with John tomorrow at 5pm for 1 hour"
                                 Title: Meeting with John
                                 Date: $tomorrowDate
                                 Time: 17:00
                                 Duration: 60
                               - Prompt: "Set an event next week at 2:30pm"
                                 Title: Event
                                 Date: $nextWeekDate
                                 Time: 14:30
                                 Duration: Unknown
                               - Prompt: "Meeting with Sarah this Friday at 9am for 45 minutes"
                                 Title: Meeting with Sarah
                                 Date: $thisFridayDate
                                 Time: 09:00
                                 Duration: 45
                               - Prompt: "Set an event in 2 days in the evening for 2 hours"
                                 Title: Event
                                 Date: $inTwoDaysDate
                                 Time: 18:00
                                 Duration: 120
                               - Prompt: "Schedule a meeting today"
                                 Title: Meeting
                                 Date: $currentDate
                                 Time: Unknown
                                 Duration: Unknown
                        """.trimIndent()

                        val calendarChatMessages = listOf(
                            "system" to calendarPrompt,
                            "user" to prompt
                        )
                        val formattedCalendarPrompt =
                            formatChatTemplate(calendarChatMessages, addGenerationPrompt = true)
                        Log.d(
                            "MessageHandler",
                            "Formatted calendar prompt: $formattedCalendarPrompt"
                        )

                        Toast.makeText(
                            params.context,
                            "Connecting Calendar Services",
                            Toast.LENGTH_SHORT
                        ).show()

                        responseBuilder.clear()
                        params.llama.send(formattedCalendarPrompt, formatChat = true)
                            .collect { partialResponse ->
                                if (isActive) {
                                    responseBuilder.append(partialResponse)
                                }
                            }
                        val eventDetails = responseBuilder.toString().trim()
                        Log.d("MessageHandler", "Calendar event details: $eventDetails")

                        val detailsMap = eventDetails.lines().associate {
                            val (key, value) = it.split(": ", limit = 2)
                            key.trim() to value.trim()
                        }
                        val title = detailsMap["Title"] ?: "Event"
                        val date = detailsMap["Date"]?.takeIf { it != "Unknown" }
                        val time = detailsMap["Time"]?.takeIf { it != "Unknown" }
                        val durationStr = detailsMap["Duration"]?.takeIf { it != "Unknown" }
                        val duration = durationStr?.toIntOrNull()

                        val calendarIntent = CalendarIntent(params.context)
                        val event = CalendarEvent(
                            title = title,
                            date = date,
                            time = time,
                            duration = duration
                        )
                        val success =
                            calendarIntent.insertEvent(event, params.selectedCalendarId.value)

                        params.messages[params.messages.lastIndex] = if (success) {
                            "${params.assistantName}: Scheduled $title${date?.let { " on $it" } ?: ""}${time?.let { " at $it" } ?: ""}${duration?.let { " for $it minutes" } ?: ""}"
                        } else {
                            "${params.assistantName}: Failed to schedule $title"
                        }
                    }

                    else -> {
                        val contextItems = retryWithBackoff(3, 1000L) {
                            ragHandler.retrieveRelevantContext(prompt, topK = 3)
                        } ?: emptyList()
                        val contextString = if (contextItems.isNotEmpty()) {
                            contextItems.joinToString("\n") { "Context: $it" }
                        } else {
                            Log.d("MH", "No valid RAG context retrieved after retries")
                            "No valid context found."
                        }
                        Log.d("MH", "Retrieved RAG context: $contextString")

                        val chatPrompt = """
                            You are ${params.assistantName}, a helpful AI assistant to user named ${params.userName}. Follow these guidelines:
                            - Be concise yet thorough
                            - Use clear, simple language
                            - Format responses neatly with proper spacing
                            - If unsure, say "I don't know" rather than guessing
                            - Always maintain a helpful, professional tone
                            - Use the following context to inform your response, if relevant:
                            $contextString
                        """.trimIndent()

                        val chatMessages = messagesToPairs(
                            params.messages.dropLast(1),
                            params.userName,
                            params.assistantName,
                            prompt
                        ).toMutableList()
                        if (chatMessages.none { it.first == "system" }) {
                            chatMessages.add(0, "system" to chatPrompt)
                        }

                        Log.d("MessageHandler", "Chat Prompt: $chatPrompt\n User Msg: $prompt\n")

                        val formattedChatPrompt =
                            formatChatTemplate(chatMessages, addGenerationPrompt = true)


                        responseBuilder.clear()
                        params.llama.send(formattedChatPrompt, formatChat = true)
                            .collect { partialResponse ->
                                if (isActive) {
                                    responseBuilder.append(partialResponse)
                                    val currentResponse = responseBuilder.toString().trim()

                                    if (currentResponse.isNotEmpty() &&
                                        !currentResponse.endsWith("...") &&
                                        currentResponse != params.messages.lastOrNull()
                                            ?.removePrefix("${params.assistantName}: ")?.trim()
                                    ) {
                                        withContext(Dispatchers.Main) {
                                            val formattedResponse =
                                                currentResponse.replace("\n\n", "\n")
                                            params.messages[params.messages.lastIndex] =
                                                "${params.assistantName}: $formattedResponse"
                                        }
                                    }
                                }
                            }
                    }
                }
            } finally {
                params.isGenerating.value = false
                sendJob = null
                Log.d("MessageHandler", "Sending finished")
            }
            // Wait for the sendJob to complete or be canceled
            sendJob?.join()
        }
    }

    fun pause(params: MessageHandlerParams) {
        sendJob?.cancel()
        responseBuilder.clear()
        params.isGenerating.value = false
        params.messages.removeAll {
            it.startsWith("${params.assistantName}:") &&
                    (it.length < 10 || it.endsWith("...") || it == "${params.assistantName}: ")
        }
        Log.d("MessageHandler", "Paused, cleaned partial responses")
    }
}


private suspend fun <T> retryWithBackoff(
    maxAttempts: Int,
    delayMs: Long,
    block: suspend () -> T
): T? {
    repeat(maxAttempts) { attempt ->
        try {
            return block()
        } catch (e: Exception) {
            Log.d("MessageHandler", "Attempt ${attempt + 1} failed: ${e.message}")
            if (attempt < maxAttempts - 1) delay(delayMs)
        }
    }
    return null
}
