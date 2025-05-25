package com.example.llama.handler

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.example.llama.model.ChatHistory
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import android.llama.cpp.LLamaAndroid

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

    private suspend fun saveCurrentChat(
        messages: SnapshotStateList<String>,
        userName: String,
        currentChatId: MutableState<String?>,
        chatHistories: SnapshotStateList<ChatHistory>,
        chatsDir: File
    ) {
        if (messages.isEmpty()) {
            Log.d("MessageHandler", "No messages to save")
            return
        }

        val chatId = currentChatId.value ?: UUID.randomUUID().toString()

        val userPrefix = "$userName: "
        val firstMsg =
            messages.firstOrNull { it.startsWith(userPrefix) } ?: messages.firstOrNull() ?: ""
        val trimmedTitle = if (firstMsg.startsWith(userPrefix)) {
            firstMsg.removePrefix(userPrefix).trimStart()
        } else {
            firstMsg
        }
        val title = trimmedTitle.take(30)

        val chatHistory = ChatHistory(
            id = chatId,
            title = title,
            date = Date(),
            messages = messages.toList()
        )
        val file = File(chatsDir, "$chatId.json")

        withContext(Dispatchers.IO) {
            try {
                val json = Json.encodeToString(chatHistory)
                file.writeText(json)
                Log.d("MessageHandler", "Chat saved to: ${file.absolutePath}")

                val existingIndex = chatHistories.indexOfFirst { it.id == chatId }
                if (existingIndex != -1) {
                    chatHistories[existingIndex] = chatHistory
                    Log.d("MessageHandler", "Updated existing chat: $chatId")
                } else {
                    chatHistories.add(chatHistory)
                    currentChatId.value = chatId
                    Log.d("MessageHandler", "Added new chat: $chatId")
                }
                chatHistories.sortByDescending { it.date }
                Log.d("MessageHandler", "Chat list updated, size: ${chatHistories.size}")
            } catch (e: Exception) {
                Log.e("MessageHandler", "Failed to save chat $chatId: ${e.stackTraceToString()}")
            }
        }
    }

    fun sendMessage(
        params: MessageHandlerParams,
        prompt: String,
        scope: kotlinx.coroutines.CoroutineScope
    ) {
        if (prompt.isBlank()) {
            Log.d("MessageHandler", "Empty prompt, skipping send")
            return
        }

        params.isGenerating.value = true
        params.messages.add("${params.userName}: $prompt")
        params.messages.add("${params.assistantName}: ")

        sendJob?.cancel()
        sendJob = scope.launch {
            try {
                Log.d("MessageHandler", "Sending prompt: $prompt")
                // Log word count for debugging
                val wordCount = prompt.split("\\s+".toRegex()).size
                Log.d("MessageHandler", "Prompt word count: $wordCount")
                if (params.modelPath.isEmpty()) {
                    params.messages[params.messages.lastIndex] =
                        "${params.assistantName}: No model loaded. Please load a model from Settings."
                    params.isGenerating.value = false
                    Log.d("MessageHandler", "No model loaded")
                    return@launch
                }

                val responseBuilder = StringBuilder()
                val twoWordCallRegex = Regex(
                    """^(call|phone|dial)\s+(.+)$""",
                    RegexOption.IGNORE_CASE
                )
                val intent = when {
                    twoWordCallRegex.matches(prompt) -> {
                        Log.d("MessageHandler", "Two-word call pattern matched: $prompt")
                        "call"
                    }
                    else -> {
                        val ambiguousWordsRegex = Regex(
                            """\b(call(ing)?|phone|dial(ing)?|event|meet(ing)?|schedul(e|ing)?|set|appointment|calendar|tomorrow|today|[0-9]+(am|pm))\b""",
                            RegexOption.IGNORE_CASE
                        )
                        val checkIntent = true
                        if (checkIntent && ambiguousWordsRegex.containsMatchIn(prompt)) {
                            val intentPrompt = """
                                You are an Intent Classifier. Analyze the user prompt and determine the intent.
                                The possible intents are:
                                - call: User explicitly requests to make a phone call to a person, using verbs like "call", "phone", or "dial" followed by a name, including familial or informal terms (e.g., "Call John", "Phone Sarah", "Dial Mummy", "Call Daddy", "Phone Brother").
                                - calendar: User requests to create or manage a calendar event (e.g., "Schedule a meeting with John tomorrow at 5pm", "Set an event").
                                - chat: User wants to engage in conversation, ask general or technical questions, or discuss topics not related to calls or calendar events (e.g., "What is a system call?", "Explain call by reference in C++").
                                Rules:
                                1. Output ONLY the intent name (call, calendar, or chat) in lowercase.
                                2. Do NOT include any other text, explanation, or comments.
                                3. If the intent is unclear, output "chat".
                                4. For "call" intent:
                                   - Requires explicit verbs "call", "phone", or "dial" followed by a person's name, which can be formal (e.g., "John", "Sarah"), familial (e.g., "Mummy", "Daddy", "Brother", "Sister"), or informal (e.g., "Buddy", "Mate").
                                   - Exclude technical terms like "system call", "function call", or "call by reference".
                                   - Examples: "Call Mummy" -> call, "Phone Daddy" -> call, "Dial Brother" -> call, "What is a system call?" -> chat.
                                5. For "calendar" intent:
                                   - Look for keywords like "schedule", "set", "meeting", "event", "appointment", or "calendar".
                                   - Prioritize prompts with time references (e.g., "tomorrow", "5pm", "4pm evening") or date references.
                                   - Examples: "Schedule a meeting with my boss at 4pm tomorrow" -> calendar, "Set an event for next week" -> calendar.
                                6. For "chat" intent:
                                   - Covers general questions, technical queries, programming topics, or anything not explicitly a phone call or calendar event.
                                   - Examples: "What is call by reference?" -> chat, "Tell me about calendars" -> chat.
                                7. If the prompt contains both call and calendar keywords, prioritize "calendar" if time/date references are present.
                                8. Ensure familial terms like "Mummy", "Daddy", "Brother", "Sister" are correctly identified as names for the "call" intent, not confused with other intents.
                            """.trimIndent()

                            val intentChatMessages = listOf(
                                "system" to intentPrompt,
                                "user" to prompt
                            )
                            val formattedIntentPrompt =
                                formatChatTemplate(intentChatMessages, addGenerationPrompt = true)
                            Log.d("MessageHandler", "Formatted intent prompt: $formattedIntentPrompt")

                            params.llama.send(formattedIntentPrompt, formatChat = true)
                                .collect { partialResponse ->
                                    responseBuilder.append(partialResponse)
                                }
                            responseBuilder.toString().trim().lowercase()
                        } else {
                            Log.d(
                                "MessageHandler",
                                "No ambiguous words found or checkIntent disabled, defaulting to chat"
                            )
                            "chat"
                        }
                    }
                }
                Log.d("MessageHandler", "Determined intent: $intent")

                when (intent) {
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
                                responseBuilder.append(partialResponse)
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
                                "${params.assistantName}: Failed to call $nameToCall"
                            }
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
                                responseBuilder.append(partialResponse)
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
                        val chatPrompt = """
                            You are ${params.assistantName}, a helpful AI assistant to ${params.userName}. Follow these guidelines:
                            - Be concise yet thorough
                            - Use clear, simple language
                            - Format responses neatly with proper spacing
                            - If unsure, say "I don't know" rather than guessing
                            - For coding questions, provide complete examples
                            - Always maintain a helpful, professional tone
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
                        val formattedChatPrompt =
                            formatChatTemplate(chatMessages, addGenerationPrompt = true)
                        Log.d("MessageHandler", "Formatted chat prompt: $formattedChatPrompt")

                        responseBuilder.clear()
                        params.llama.send(formattedChatPrompt, formatChat = true)
                            .collect { partialResponse ->
                                responseBuilder.append(partialResponse)
                                val currentResponse = responseBuilder.toString().trim()

                                if (currentResponse.isNotEmpty() &&
                                    !currentResponse.endsWith("...") &&
                                    currentResponse != params.messages.lastOrNull()
                                        ?.removePrefix("${params.assistantName}: ")?.trim()
                                ) {
                                    val formattedResponse = currentResponse.replace("\n\n", "\n")
                                    params.messages[params.messages.lastIndex] =
                                        "${params.assistantName}: $formattedResponse"
                                    Log.d(
                                        "MessageHandler",
                                        "Updated chat response: $formattedResponse"
                                    )
                                }
                            }
                    }
                }
            } finally {
                params.isGenerating.value = false
                sendJob = null
                Log.d("MessageHandler", "Sending finished, saving chat")
                saveCurrentChat(
                    params.messages,
                    params.userName,
                    params.currentChatId,
                    params.chatHistories,
                    params.chatsDir
                )
            }
        }
    }
}
