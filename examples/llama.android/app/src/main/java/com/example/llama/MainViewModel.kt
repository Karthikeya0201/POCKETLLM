package com.example.llama

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.CalendarContract
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.State
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.llama.handler.IntentCall
import com.example.llama.model.ChatHistory
import android.llama.cpp.LLamaAndroid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class MainViewModel(private val context: Context) : ViewModel() {
    private val sharedPrefs = context.getSharedPreferences("PocketLLMPrefs", Context.MODE_PRIVATE)
    var userName = mutableStateOf("")
    var assistantName = mutableStateOf("")
    val message = mutableStateOf("")
    val messages = mutableStateListOf<String>() // Reverted to List<String>
    val chatHistories = mutableStateListOf<ChatHistory>()
    val modelPath = mutableStateOf("")
    val importError = mutableStateOf("")
    val isLoadingModel = mutableStateOf(false)
    val isGenerating = mutableStateOf(false)
    val showImportSuccess = mutableStateOf(false)
    val calendarList = mutableStateOf<List<Pair<Long, String>>>(emptyList())
    val selectedCalendarId = mutableStateOf<Long?>(null)
    private lateinit var chatsDir: File
    private val llama = LLamaAndroid.instance().apply {
        setContextSize(8192)
    }
    private var sendJob: Job? = null
    var currentChatId: String? = null

    val selectedUri = mutableStateOf<Uri?>(null)
    val selectedFileName = mutableStateOf<String?>(null)

    private val _isModelLoaded = mutableStateOf(false)
    val isModelLoaded: State<Boolean> = _isModelLoaded

    private val _downloadedModels: MutableState<List<String>> = mutableStateOf(emptyList())
    val downloadedModels: State<List<String>> = _downloadedModels

    private val _isInitialized = mutableStateOf(false)
    val isInitialized: State<Boolean> get() = _isInitialized

    private val _isFirstLaunch = mutableStateOf(true)
    val isFirstLaunch: State<Boolean> = _isFirstLaunch

    init {
        initialize()
    }

    private fun initialize() {
        viewModelScope.launch {
            try {
                loadPrefs(sharedPrefs)
            } catch (e: Exception) {
                Log.e("MainViewModel", "Initialization failed: ${e.stackTraceToString()}")
            }
            _isInitialized.value = true
            Log.d("MainViewModel", "Initialization complete, isModelLoaded=${isModelLoaded.value}")
        }
    }

    fun setModelLoaded(loaded: Boolean) {
        _isModelLoaded.value = loaded
        Log.d("MainViewModel", "Model loaded state updated: $loaded")
        with(sharedPrefs.edit()) {
            putBoolean("isModelLoaded", loaded)
            apply()
        }
    }

    fun getModelFileName(modelName: String): String {
        return "${modelName.lowercase().replace(" ", "_")}.gguf"
    }

    fun getModelDisplayName(fileName: String): String {
        return fileName.removeSuffix(".gguf").replace("-", " ").let { name ->
            name.split(" ").joinToString(" ") { word ->
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
        }
    }

    suspend fun loadPrefs(prefs: SharedPreferences) {
        userName.value = prefs.getString("userName", "") ?: ""
        assistantName.value = prefs.getString("assistantName", "") ?: ""
        modelPath.value = prefs.getString("modelPath", "") ?: ""
        _isModelLoaded.value = prefs.getBoolean("isModelLoaded", false)
        _isFirstLaunch.value = prefs.getBoolean("isFirstLaunch", true)
        val calendarId = prefs.getLong("selectedCalendarId", -1L)
        if (calendarId != -1L) selectedCalendarId.value = calendarId
        val downloadedModelsJson = prefs.getString("downloadedModels", null)
        _downloadedModels.value = if (downloadedModelsJson != null) {
            Json.decodeFromString<List<String>>(downloadedModelsJson)
        } else {
            emptyList()
        }
        chatsDir = File(context.filesDir, "chat_histories").apply {
            mkdirs()
            Log.d("MainViewModel", "Initialized chatsDir: $absolutePath")
        }
        loadChatHistories()
        try {
            llama.initialize(userName.value, assistantName.value)
            Log.d(
                "MainViewModel",
                "LLaMA initialized with userName=${userName.value}, assistantName=${assistantName.value}"
            )
        } catch (e: Exception) {
            Log.e("MainViewModel", "Failed to initialize LLaMA: ${e.stackTraceToString()}")
            importError.value = "Failed to initialize model library: ${e.message}"
        }
        Log.d(
            "MainViewModel",
            "Loaded preferences: userName=${userName.value}, assistantName=${assistantName.value}, modelPath=${modelPath.value}, isModelLoaded=${isModelLoaded.value}, downloadedModels=${_downloadedModels.value}, selectedCalendarId=${selectedCalendarId.value}"
        )

        if (modelPath.value.isNotEmpty()) {
            val modelFile = File(modelPath.value)
            Log.d(
                "MainViewModel",
                "Checking model file: path=${modelPath.value}, exists=${modelFile.exists()}, absolutePath=${modelFile.absolutePath}, size=${if (modelFile.exists()) modelFile.length() else 0} bytes"
            )
            if (modelFile.exists()) {
                selectedUri.value = Uri.fromFile(modelFile)
                selectedFileName.value = getModelDisplayName(modelPath.value.substringAfterLast('/'))
                Log.d(
                    "MainViewModel",
                    "Initialized selectedUri=${selectedUri.value}, selectedFileName=${selectedFileName.value}"
                )
                try {
                    isLoadingModel.value = true
                    Log.d(
                        "MainViewModel",
                        "Attempting to reload model from path: ${modelPath.value}"
                    )
                    try {
                        llama.unload()
                        Log.d("MainViewModel", "Unloaded any existing model")
                    } catch (e: Exception) {
                        Log.e("MainViewModel", "Error unloading model: ${e.stackTraceToString()}")
                    }
                    llama.load(modelPath.value)
                    setModelLoaded(true)
                    Log.d(
                        "MainViewModel",
                        "Model successfully reloaded: ${modelPath.value}, isModelLoaded=${isModelLoaded.value}"
                    )
                } catch (e: Exception) {
                    importError.value = "Failed to reload model on startup: ${e.stackTraceToString()}"
                    setModelLoaded(false)
                    modelPath.value = ""
                    selectedUri.value = null
                    selectedFileName.value = null
                    with(sharedPrefs.edit()) {
                        putString("modelPath", "")
                        putString("selectedFileName", "")
                        apply()
                    }
                    Log.e(
                        "MainViewModel",
                        "Model reload error on startup: ${e.stackTraceToString()}, isModelLoaded=${isModelLoaded.value}"
                    )
                } finally {
                    isLoadingModel.value = false
                }
            } else {
                setModelLoaded(false)
                modelPath.value = ""
                selectedUri.value = null
                selectedFileName.value = null
                importError.value = "Model file not found at ${modelPath.value}. Please select a new model."
                with(sharedPrefs.edit()) {
                    putString("modelPath", "")
                    putString("selectedFileName", "")
                    apply()
                }
                Log.e(
                    "MainViewModel",
                    "Model file does not exist at saved path: ${modelPath.value}, isModelLoaded=${isModelLoaded.value}"
                )
            }
        } else {
            setModelLoaded(false)
            selectedUri.value = null
            selectedFileName.value = null
            Log.d(
                "MainViewModel",
                "No model path saved in preferences, isModelLoaded=${isModelLoaded.value}"
            )
        }
    }

    fun savePrefs(prefs: SharedPreferences) {
        with(prefs.edit()) {
            putString("userName", userName.value)
            putString("assistantName", assistantName.value)
            putString("modelPath", modelPath.value)
            putBoolean("isModelLoaded", _isModelLoaded.value)
            putString("historyDir", chatsDir.parent ?: context.filesDir.absolutePath)
            putString("downloadedModels", Json.encodeToString(_downloadedModels.value))
            putString("selectedFileName", selectedFileName.value)
            selectedCalendarId.value?.let { putLong("selectedCalendarId", it) }
            apply()
        }
        Log.d(
            "MainViewModel",
            "Saved preferences: userName=${userName.value}, assistantName=${assistantName.value}, modelPath=${modelPath.value}, isModelLoaded=${_isModelLoaded.value}, downloadedModels=${_downloadedModels.value}, selectedFileName=${selectedFileName.value}, selectedCalendarId=${selectedCalendarId.value}"
        )
        try {
            llama.initialize(userName.value, assistantName.value)
            Log.d("MainViewModel", "LLaMA re-initialized after saving preferences")
        } catch (e: Exception) {
            Log.e(
                "MainViewModel",
                "Failed to re-initialize LLaMA after saving preferences: ${e.stackTraceToString()}"
            )
        }
    }

    private suspend fun loadChatHistories() {
        withContext(Dispatchers.IO) {
            chatHistories.clear()
            val files = chatsDir.listFiles()
            Log.d("MainViewModel", "Loading chat histories, found ${files?.size ?: 0} files")
            files?.forEach { file ->
                try {
                    val json = file.readText()
                    val chatHistory = Json.decodeFromString<ChatHistory>(json)
                    chatHistories.add(chatHistory)
                    Log.d(
                        "MainViewModel",
                        "Loaded chat: ${chatHistory.title} (ID: ${chatHistory.id})"
                    )
                } catch (e: Exception) {
                    Log.e(
                        "MainViewModel",
                        "Failed to load chat history from ${file.name}: ${e.stackTraceToString()}"
                    )
                }
            }
            chatHistories.sortByDescending { it.date }
            Log.d("MainViewModel", "Chat histories loaded, total: ${chatHistories.size}")
        }
    }

    suspend fun saveCurrentChat() {
        if (messages.isEmpty()) {
            Log.d("MainViewModel", "No messages to save")
            return
        }

        val chatId = currentChatId ?: UUID.randomUUID().toString()

        val userPrefix = "${userName.value}: "
        val firstMsg = messages.firstOrNull { it.startsWith(userPrefix) } ?: messages.firstOrNull() ?: ""
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
                Log.d("MainViewModel", "Chat saved to: ${file.absolutePath}")

                val existingIndex = chatHistories.indexOfFirst { it.id == chatId }
                if (existingIndex != -1) {
                    chatHistories[existingIndex] = chatHistory
                    Log.d("MainViewModel", "Updated existing chat: $chatId")
                } else {
                    chatHistories.add(chatHistory)
                    currentChatId = chatId
                    Log.d("MainViewModel", "Added new chat: $chatId")
                }
                chatHistories.sortByDescending { it.date }
                Log.d("MainViewModel", "Chat list updated, size: ${chatHistories.size}")
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to save chat $chatId: ${e.stackTraceToString()}")
            }
        }
    }

    suspend fun loadChat(chatId: String) {
        while (!_isInitialized.value) {
            Log.d(
                "MainViewModel",
                "Waiting for initialization to complete before loading chat $chatId"
            )
            delay(100)
        }

        withContext(Dispatchers.IO) {
            val file = File(chatsDir, "$chatId.json")
            if (file.exists()) {
                try {
                    val json = file.readText()
                    val chatHistory = Json.decodeFromString<ChatHistory>(json)
                    messages.clear()
                    messages.addAll(chatHistory.messages)
                    currentChatId = chatId
                    Log.d(
                        "MainViewModel",
                        "Loaded chat $chatId with ${chatHistory.messages.size} messages"
                    )
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Failed to load chat $chatId: ${e.stackTraceToString()}")
                    throw e
                }
            } else {
                Log.e("MainViewModel", "Chat file not found: ${file.absolutePath}")
                throw Exception("Chat file not found: ${file.absolutePath}")
            }
        }
    }

    suspend fun deleteChat(chatId: String) {
        withContext(Dispatchers.IO) {
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
                    Log.e(
                        "MainViewModel",
                        "Failed to delete chat $chatId: ${e.stackTraceToString()}"
                    )
                }
            } else {
                Log.e("MainViewModel", "Chat file not found for deletion: ${file.absolutePath}")
            }
        }
    }

    fun setModelPath(modelSource: Any) {
        viewModelScope.launch {
            try {
                isLoadingModel.value = true
                importError.value = ""
                val modelPathStr: String
                when (modelSource) {
                    is String -> {
                        val file = File(modelSource)
                        Log.d(
                            "MainViewModel",
                            "setModelPath: modelSource=$modelSource, file.exists=${file.exists()}"
                        )
                        if (file.exists()) {
                            if (!file.name.endsWith(".gguf", ignoreCase = true)) {
                                importError.value = "Model file must be a .gguf file: ${file.name}"
                                setModelLoaded(false)
                                Log.e(
                                    "MainViewModel",
                                    "Invalid file type: ${file.name}, isModelLoaded=${isModelLoaded.value}"
                                )
                                return@launch
                            }
                            modelPathStr = modelSource
                        } else {
                            importError.value = "Model file does not exist: $modelSource"
                            setModelLoaded(false)
                            Log.e(
                                "MainViewModel",
                                "Model file does not exist: $modelSource, isModelLoaded=${isModelLoaded.value}"
                            )
                            return@launch
                        }
                    }

                    is Uri -> {
                        val uri = modelSource
                        val fileName = context.contentResolver.query(uri, null, null, null, null)
                            ?.use { cursor ->
                                if (cursor.moveToFirst()) {
                                    val displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                    if (displayNameIndex != -1) cursor.getString(displayNameIndex) else null
                                } else null
                            } ?: uri.lastPathSegment ?: "model.gguf"
                        if (!fileName.endsWith(".gguf", ignoreCase = true)) {
                            importError.value = "Please select a .gguf file"
                            setModelLoaded(false)
                            Log.e(
                                "MainViewModel",
                                "Invalid file type: $fileName, isModelLoaded=${isModelLoaded.value}"
                            )
                            return@launch
                        }
                        val internalFile = File(context.filesDir, fileName)
                        if (!internalFile.exists()) {
                            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                                FileOutputStream(internalFile).use { outputStream ->
                                    inputStream.copyTo(outputStream)
                                }
                            }
                            Log.d(
                                "MainViewModel",
                                "Copied file from URI: $uri, new path: ${internalFile.absolutePath}"
                            )
                        } else {
                            Log.d(
                                "MainViewModel",
                                "File already exists, skipping copy: ${internalFile.absolutePath}"
                            )
                        }
                        if (internalFile.exists()) {
                            modelPathStr = internalFile.absolutePath
                        } else {
                            importError.value = "Failed to copy model file from $uri"
                            setModelLoaded(false)
                            Log.e("ModelImportCard", "File copy failed: $fileName")
                            return@launch
                        }
                    }

                    else -> {
                        importError.value = "Invalid model source: $modelSource"
                        setModelLoaded(false)
                        Log.e(
                            "MainViewModel",
                            "Invalid model source: $modelSource, isModelLoaded=${isModelLoaded.value}"
                        )
                        return@launch
                    }
                }
                Log.d("MainViewModel", "Attempting to load model from path: $modelPathStr")
                try {
                    llama.initialize(userName.value, assistantName.value)
                    Log.d("MainViewModel", "LLaMA initialized before loading model")
                } catch (e: Exception) {
                    importError.value = "Failed to initialize model library before loading: ${e.message}"
                    setModelLoaded(false)
                    Log.e(
                        "MainViewModel",
                        "Failed to initialize LLaMA before loading: ${e.stackTraceToString()}"
                    )
                    return@launch
                }
                if (_isModelLoaded.value) {
                    Log.d("MainViewModel", "Unloading existing model before loading new one")
                    try {
                        llama.unload()
                        Log.d("MainViewModel", "Unloaded existing model")
                    } catch (e: Exception) {
                        Log.e("MainViewModel", "Error unloading model: ${e.stackTraceToString()}")
                    }
                }
                llama.load(modelPathStr)
                modelPath.value = modelPathStr
                selectedFileName.value = getModelDisplayName(modelPathStr.substringAfterLast('/'))
                selectedUri.value = Uri.fromFile(File(modelPathStr))
                setModelLoaded(true)
                savePrefs(sharedPrefs)
                Log.d(
                    "MainViewModel",
                    "Model loaded successfully: $modelPathStr, selectedFileName=${selectedFileName.value}, isModelLoaded=${isModelLoaded.value}"
                )
            } catch (e: Exception) {
                importError.value = "Failed to load model: ${e.stackTraceToString()}"
                setModelLoaded(false)
                savePrefs(sharedPrefs)
                Log.e(
                    "MainViewModel",
                    "Model load error: ${e.stackTraceToString()}, isModelLoaded=${isModelLoaded.value}"
                )
            } finally {
                isLoadingModel.value = false
            }
        }
    }

    fun addDownloadedModel(modelName: String) {
        _downloadedModels.value = _downloadedModels.value + modelName
        savePrefs(sharedPrefs)
        Log.d(
            "MainViewModel",
            "Added downloaded model: $modelName, total: ${_downloadedModels.value}"
        )
    }

    fun startNewChat() {
        Log.d("MainViewModel", "Starting new chat")
        viewModelScope.launch {
            saveCurrentChat()
            messages.clear()
            currentChatId = null
            sendJob?.cancel()
            isGenerating.value = false
            if (!_isModelLoaded.value && modelPath.value.isNotEmpty() && File(modelPath.value).exists()) {
                try {
                    Log.d(
                        "MainViewModel",
                        "Model not loaded, attempting to load: ${modelPath.value}"
                    )
                    try {
                        llama.initialize(userName.value, assistantName.value)
                        Log.d("MainViewModel", "LLaMA initialized in startNewChat")
                    } catch (e: Exception) {
                        Log.e(
                            "MainViewModel",
                            "Failed to initialize LLaMA in startNewChat: ${e.stackTraceToString()}"
                        )
                    }
                    try {
                        llama.unload()
                        Log.d("MainViewModel", "Unloaded any existing model")
                    } catch (e: Exception) {
                        Log.e("MainViewModel", "Error unloading model: ${e.stackTraceToString()}")
                    }
                    llama.load(modelPath.value)
                    setModelLoaded(true)
                    Log.d(
                        "MainViewModel",
                        "Model loaded successfully in startNewChat: ${modelPath.value}, isModelLoaded=${isModelLoaded.value}"
                    )
                } catch (e: Exception) {
                    setModelLoaded(false)
                    Log.e(
                        "MainViewModel",
                        "Model load error in startNewChat: ${e.stackTraceToString()}, isModelLoaded=${isModelLoaded.value}"
                    )
                }
            } else {
                Log.d(
                    "MainViewModel",
                    "Model already loaded or no model to load: isModelLoaded=${_isModelLoaded.value}, modelPath=${modelPath.value}"
                )
            }
            Log.d("MainViewModel", "New chat started, messages cleared")
        }
    }

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

    // Helper function to convert messages to role-based pairs
    private fun messagesToPairs(messages: List<String>, currentPrompt: String? = null): List<Pair<String, String>> {
        val pairs = mutableListOf<Pair<String, String>>()
        messages.forEach { msg ->
            when {
                msg.startsWith("${userName.value}: ") -> {
                    pairs.add("user" to msg.removePrefix("${userName.value}: ").trim())
                }
                msg.startsWith("${assistantName.value}: ") -> {
                    pairs.add("assistant" to msg.removePrefix("${assistantName.value}: ").trim())
                }
                else -> {
                    // Assume assistant if no clear prefix (fallback)
                    pairs.add("assistant" to msg.trim())
                }
            }
        }
        if (currentPrompt != null) {
            pairs.add("user" to currentPrompt.trim())
        }
        return pairs
    }

    fun send(prompt: String = message.value.trim()) {
        if (prompt.isBlank()) {
            Log.d("MainViewModel", "Empty prompt, skipping send")
            return
        }
        isGenerating.value = true
        sendJob?.cancel()
        messages.add("${userName.value}: $prompt")
        sendJob = viewModelScope.launch {
            try {
                Log.d("MainViewModel", "Sending prompt: $prompt")
                if (modelPath.value.isEmpty()) {
                    messages.add("${assistantName.value}: No model loaded. Please load a model from Settings.")
                    isGenerating.value = false
                    Log.d("MainViewModel", "No model loaded")
                    return@launch
                }

                val intent = when {
                    Regex("(?i)\\bcall\\b").containsMatchIn(prompt) -> "call"
                    Regex("(?i)\\bcalendar\\b").containsMatchIn(prompt) -> "calendar"
                    else -> "main"
                }

                val responseBuilder = StringBuilder()
                messages.add("${assistantName.value}: ")

                if (intent == "main") {
                    val systemPrompt = """
                        You are ${assistantName.value}, a helpful AI assistant. Follow these guidelines:
                        - Be concise yet thorough
                        - Use clear, simple language
                        - Format responses neatly with proper spacing
                        - If unsure, say "I don't know" rather than guessing
                        - For coding questions, provide complete examples
                        - Always maintain a helpful, professional tone
                    """.trimIndent()

                    // Convert messages to pairs for chat template
                    val chatMessages = messagesToPairs(messages.dropLast(1), prompt).toMutableList()
                    if (chatMessages.none { it.first == "system" }) {
                        chatMessages.add(0, "system" to systemPrompt)
                    }
                    val formattedMessage = formatChatTemplate(chatMessages, addGenerationPrompt = true)
                    Log.d("MainViewModel", "Formatted main prompt: $formattedMessage")

                    llama.send(formattedMessage, formatChat = true).collect { partialResponse ->
                        responseBuilder.append(partialResponse)
                        val currentResponse = responseBuilder.toString().trim()

                        if (currentResponse.isNotEmpty() &&
                            !currentResponse.endsWith("...") &&
                            currentResponse != messages.lastOrNull()
                                ?.removePrefix("${assistantName.value}: ")?.trim()
                        ) {
                            val formattedResponse = currentResponse.replace("\n\n", "\n")
                            if (messages.lastOrNull()?.startsWith("${assistantName.value}: ") == true) {
                                messages[messages.lastIndex] = "${assistantName.value}: $formattedResponse"
                            } else {
                                messages.add("${assistantName.value}: $formattedResponse")
                            }
                            Log.d("MainViewModel", "Updated response: $formattedResponse")
                        }
                    }
                } else if (intent == "call") {
                    val systemPrompt = """
                        You are a Caller Name Extractor. Follow these rules exactly:
                        1. The User Prompt will give a call (e.g., "Call John").
                        2. Extract ONLY the name of the person to call.
                        3. Output MUST be in this 1-line format:
                           Call <Name>
                        6. Do NOT include any other text.
                        7. Do NOT explain, greet, or comment. Output only the required format.
                    """.trimIndent()

                    val chatMessages = listOf(
                        "system" to systemPrompt,
                        "user" to prompt
                    )
                    val formattedMessage = formatChatTemplate(chatMessages, addGenerationPrompt = true)
                    Log.d("MainViewModel", "Formatted call prompt: $formattedMessage")

                    llama.send(formattedMessage, formatChat = true).collect { partialResponse ->
                        responseBuilder.append(partialResponse)
                    }
                    val callPerson = responseBuilder.toString().trim()

                    val nameToCall = callPerson.removePrefix("Call ").trim()
                    if (nameToCall.isBlank()) {
                        messages[messages.lastIndex] = "${assistantName.value}: Invalid contact name"
                    } else {
                        val intentCall = IntentCall(context)
                        val callSuccess = intentCall.initiateCall(nameToCall)
                        messages[messages.lastIndex] = if (callSuccess) {
                            "${assistantName.value}: Calling $nameToCall..."
                        } else {
                            "${assistantName.value}: Failed to call $nameToCall"
                        }
                    }
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
            it.startsWith("${assistantName.value}: ") &&
                (it.length < "${assistantName.value}: ".length + 10 || it.endsWith("..."))
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
            try {
                llama.unload()
                setModelLoaded(false)
                Log.d("MainViewModel", "ViewModel cleared, unloaded LLM")
            } catch (e: Exception) {
                Log.e(
                    "MainViewModel",
                    "Error unloading LLM on ViewModel clear: ${e.stackTraceToString()}"
                )
            }
        }
    }
}
