package com.example.llama

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.llama.model.ChatHistory // Import your existing ChatHistory
import android.llama.cpp.LLamaAndroid
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.*

class MainViewModel(private val context: Context) : ViewModel() {
    val userName = mutableStateOf("User")
    val assistantName = mutableStateOf("Assistant")
    val message = mutableStateOf("")
    val messages = mutableStateListOf<String>()
    val chatHistories = mutableStateListOf<ChatHistory>()
    val modelPath = mutableStateOf("")
    val importError = mutableStateOf("")
    val isLoadingModel = mutableStateOf(false)
    val isGenerating = mutableStateOf(false)
    private lateinit var chatsDir: File
    private val llama = LLamaAndroid.instance().apply {
        setContextSize(8192)
    }
    private var sendJob: Job? = null
    var currentChatId: String? = null // Track the current chat being edited

    fun loadPrefs(prefs: SharedPreferences) {
        userName.value = prefs.getString("userName", "User") ?: "User"
        assistantName.value = prefs.getString("assistantName", "Assistant") ?: "Assistant"
        modelPath.value = prefs.getString("modelPath", "") ?: ""
        chatsDir = File(context.filesDir, "chat_histories").apply {
            mkdirs()
            Log.d("MainViewModel", "Initialized chatsDir: $absolutePath")
        }
        loadChatHistories()
        llama.initialize(userName.value, assistantName.value)
        if (modelPath.value.isNotEmpty()) {
            load(modelPath.value)
        }
    }

    fun savePrefs(prefs: SharedPreferences) {
        with(prefs.edit()) {
            putString("userName", userName.value)
            putString("assistantName", assistantName.value)
            putString("modelPath", modelPath.value)
            putBoolean("isFirstLaunch", false)
            putString("historyDir", chatsDir.parent ?: context.filesDir.absolutePath)
            apply()
        }
        llama.initialize(userName.value, assistantName.value)
    }

    private fun loadChatHistories() {
        chatHistories.clear()
        val files = chatsDir.listFiles()
        Log.d("MainViewModel", "Loading chat histories, found ${files?.size ?: 0} files")
        files?.forEach { file ->
            try {
                val json = file.readText()
                val chatHistory = Json.decodeFromString<ChatHistory>(json)
                chatHistories.add(chatHistory)
                Log.d("MainViewModel", "Loaded chat: ${chatHistory.title} (ID: ${chatHistory.id})")
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to load chat history from ${file.name}: ${e.message}")
            }
        }
        chatHistories.sortByDescending { it.date }
        Log.d("MainViewModel", "Chat histories loaded, total: ${chatHistories.size}")
    }

    fun saveCurrentChat() {
        if (messages.isEmpty()) {
            Log.d("MainViewModel", "No messages to save")
            return
        }

        val chatId = currentChatId ?: UUID.randomUUID().toString() // Use existing ID or create new

        // Trim user name from the first message for the title
        val userPrefix = "${userName.value}: "
        val firstMsg = messages.first()
        val trimmedTitle = if (firstMsg.startsWith(userPrefix)) {
            firstMsg.removePrefix(userPrefix).trimStart()
        } else {
            firstMsg
        }
        val title = trimmedTitle.take(30)

        val chatHistory = ChatHistory(
            id = chatId,
            title = title,
            date = Date(), // Update date to reflect last modification
            messages = messages.toList()
        )
        val file = File(chatsDir, "$chatId.json")

        try {
            val json = Json.encodeToString(chatHistory)
            file.writeText(json)
            Log.d("MainViewModel", "Chat saved to: ${file.absolutePath}")

            // Update chatHistories list
            val existingIndex = chatHistories.indexOfFirst { it.id == chatId }
            if (existingIndex != -1) {
                chatHistories[existingIndex] = chatHistory
                Log.d("MainViewModel", "Updated existing chat: $chatId")
            } else {
                chatHistories.add(chatHistory)
                currentChatId = chatId // Set new chat ID if created
                Log.d("MainViewModel", "Added new chat: $chatId")
            }
            chatHistories.sortByDescending { it.date }
            Log.d("MainViewModel", "Chat list updated, size: ${chatHistories.size}")
        } catch (e: Exception) {
            Log.e("MainViewModel", "Failed to save chat $chatId: ${e.stackTraceToString()}")
        }
    }

    fun loadChat(chatId: String) {
        val file = File(chatsDir, "$chatId.json")
        if (file.exists()) {
            try {
                val json = file.readText()
                val chatHistory = Json.decodeFromString<ChatHistory>(json)
                messages.clear()
                messages.addAll(chatHistory.messages)
                currentChatId = chatId // Set the current chat ID
                Log.d("MainViewModel", "Loaded chat $chatId with ${chatHistory.messages.size} messages")
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to load chat $chatId: ${e.message}")
            }
        } else {
            Log.e("MainViewModel", "Chat file not found: ${file.absolutePath}")
        }
    }

    fun deleteChat(chatId: String) {
        val file = File(chatsDir, "$chatId.json")
        if (file.exists()) {
            try {
                file.delete()
                chatHistories.removeAll { it.id == chatId }
                if (currentChatId == chatId) {
                    messages.clear()
                    currentChatId = null
                }
                Log.d("MainViewModel", "Deleted chat $chatId, remaining: ${chatHistories.size}")
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to delete chat $chatId: ${e.message}")
            }
        } else {
            Log.e("MainViewModel", "Chat file not found for deletion: ${file.absolutePath}")
        }
    }

    fun setModelPath(path: String) {
        if (!path.endsWith(".gguf")) {
            importError.value = "Please select a .gguf file"
            Log.d("MainViewModel", "Invalid model path: $path")
            return
        }
        isLoadingModel.value = true
        importError.value = ""
        viewModelScope.launch {
            try {
                modelPath.value = path
                llama.load(path)
                Log.d("MainViewModel", "Model loaded: $path")
            } catch (e: Exception) {
                importError.value = "Failed to load model: ${e.message}"
                Log.e("MainViewModel", "Model load error: ${e.stackTraceToString()}")
            } finally {
                isLoadingModel.value = false
            }
        }
    }

    fun startNewChat() {
        Log.d("MainViewModel", "Starting new chat")
        saveCurrentChat() // Save the current chat before starting a new one
        messages.clear()
        currentChatId = null // Reset for a new chat
        sendJob?.cancel()
        isGenerating.value = false
        viewModelScope.launch {
            llama.unload()
            if (modelPath.value.isNotEmpty()) {
                llama.load(modelPath.value)
                Log.d("MainViewModel", "Reloaded model: ${modelPath.value}")
            }
        }
        Log.d("MainViewModel", "New chat started, messages cleared")
    }

    fun send(prompt: String = message.value.trim()) {
        if (prompt.isBlank()) {
            Log.d("MainViewModel", "Empty prompt, skipping send")
            return
        }
        isGenerating.value = true
        sendJob?.cancel()
        messages.add("${userName.value}: $prompt") // Add user message here with dynamic username
        sendJob = viewModelScope.launch {
            try {
                Log.d("MainViewModel", "Sending prompt: $prompt")
                if (modelPath.value.isEmpty()) {
                    messages.add("${assistantName.value}: No model loaded. Please load a model from Settings.")
                    isGenerating.value = false
                    Log.d("MainViewModel", "No model loaded")
                    return@launch
                }

                val responseBuilder = StringBuilder()
                messages.add("${assistantName.value}: ")
                Log.d("MainViewModel", "Added initial assistant message marker")

                llama.send(prompt).collect { partialResponse ->
                    responseBuilder.append(partialResponse)
                    val currentResponse = responseBuilder.toString().trim()

                    if (currentResponse.isNotEmpty() &&
                        !currentResponse.endsWith("...") &&
                        currentResponse != messages.lastOrNull()?.removePrefix("${assistantName.value}: ")?.trim()
                    ) {
                        val formattedResponse = currentResponse
                            .replace("\n\n", "\n")
                            .replace(Regex(" {2,}"), " ")
                        if (messages.lastOrNull()?.startsWith("${assistantName.value}:") == true) {
                            messages[messages.lastIndex] = "${assistantName.value}: $formattedResponse"
                        } else {
                            messages.add("${assistantName.value}: $formattedResponse")
                        }
                        Log.d("MainViewModel", "Updated response: $formattedResponse")
                    }
                }

                val fullResponse = responseBuilder.toString().trim()
                if (fullResponse.isNotEmpty() && messages.lastOrNull() != "${assistantName.value}: $fullResponse") {
                    messages[messages.lastIndex] = "${assistantName.value}: $fullResponse"
                }
                Log.d("MainViewModel", "Response completed: $fullResponse")
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    messages.add("${assistantName.value}: Error: ${e.message}")
                    Log.e("MainViewModel", "Send error: ${e.stackTraceToString()}")
                }
            } finally {
                isGenerating.value = false
                sendJob = null
                Log.d("MainViewModel", "Sending finished, saving chat")
                saveCurrentChat()
            }
        }
    }

    fun pause() {
        sendJob?.cancel()
        isGenerating.value = false
        messages.removeAll {
            it.startsWith("${assistantName.value}:") &&
                (it.length < 10 || it.endsWith("..."))
        }
        Log.d("MainViewModel", "Paused, cleaned partial responses")
    }

    fun log(message: String) {
        Log.d("MainViewModel", "Custom log: $message")
    }

    fun load(path: String) {
        setModelPath(path)
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            llama.unload()
            Log.d("MainViewModel", "ViewModel cleared, unloaded LLM")
        }
    }
}