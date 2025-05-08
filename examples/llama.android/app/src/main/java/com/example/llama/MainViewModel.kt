package com.example.llama

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.llama.model.ChatHistory
import android.llama.cpp.LLamaAndroid
import androidx.compose.runtime.State
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.*

class MainViewModel(private val context: Context) : ViewModel() {
    private val sharedPrefs = context.getSharedPreferences("PocketLLMPrefs", Context.MODE_PRIVATE)
    var userName = mutableStateOf("")
    var assistantName = mutableStateOf("")
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
    var currentChatId: String? = null

    val selectedUri = mutableStateOf<Uri?>(null)
    val selectedFileName = mutableStateOf<String?>(null)

    private val _isModelLoaded = mutableStateOf(false)
    val isModelLoaded: State<Boolean> = _isModelLoaded

    init {
        loadPrefs(sharedPrefs)
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

    fun loadPrefs(prefs: SharedPreferences) {
        userName.value = prefs.getString("userName", "") ?: ""
        assistantName.value = prefs.getString("assistantName", "") ?: ""
        modelPath.value = prefs.getString("modelPath", "") ?: ""
        chatsDir = File(context.filesDir, "chat_histories").apply {
            mkdirs()
            Log.d("MainViewModel", "Initialized chatsDir: $absolutePath")
        }
        loadChatHistories()
        llama.initialize(userName.value, assistantName.value)
        Log.d("MainViewModel", "Loaded preferences: userName=${userName.value}, assistantName=${assistantName.value}, modelPath=${modelPath.value}")

        // Initialize selectedUri and selectedFileName based on modelPath
        if (modelPath.value.isNotEmpty()) {
            val modelFile = File(modelPath.value)
            Log.d("MainViewModel", "Checking model file: path=${modelPath.value}, exists=${modelFile.exists()}, absolutePath=${modelFile.absolutePath}")
            if (modelFile.exists()) {
                selectedUri.value = Uri.fromFile(modelFile)
                selectedFileName.value = modelPath.value.substringAfterLast('/')
                Log.d("MainViewModel", "Initialized selectedUri=${selectedUri.value}, selectedFileName=${selectedFileName.value}")
                runBlocking {
                    try {
                        isLoadingModel.value = true
                        Log.d("MainViewModel", "Attempting to load model from path: ${modelPath.value}")
                        try {
                            llama.unload()
                            Log.d("MainViewModel", "Unloaded any existing model")
                        } catch (e: Exception) {
                            Log.e("MainViewModel", "Error unloading model: ${e.message}")
                        }
                        llama.load(modelPath.value)
                        setModelLoaded(true)
                        Log.d("MainViewModel", "Model successfully loaded from saved path: ${modelPath.value}, isModelLoaded=${isModelLoaded.value}")
                    } catch (e: Exception) {
                        importError.value = "Failed to load model on startup: ${e.message}"
                        setModelLoaded(false)
                        // Clear modelPath to force re-selection
                        modelPath.value = ""
                        selectedUri.value = null
                        selectedFileName.value = null
                        with(sharedPrefs.edit()) {
                            putString("modelPath", "")
                            putBoolean("isModelLoaded", false)
                            apply()
                        }
                        Log.e("MainViewModel", "Model load error on startup: ${e.stackTraceToString()}, cleared modelPath, isModelLoaded=${isModelLoaded.value}")
                    } finally {
                        isLoadingModel.value = false
                    }
                }
            } else {
                setModelLoaded(false)
                // Clear modelPath to force re-selection
                modelPath.value = ""
                selectedUri.value = null
                selectedFileName.value = null
                with(sharedPrefs.edit()) {
                    putString("modelPath", "")
                    putBoolean("isModelLoaded", false)
                    apply()
                }
                Log.e("MainViewModel", "Model file does not exist at saved path: ${modelPath.value}, cleared modelPath, isModelLoaded=${isModelLoaded.value}")
            }
        } else {
            setModelLoaded(false)
            selectedUri.value = null
            selectedFileName.value = null
            Log.d("MainViewModel", "No model path saved in preferences, isModelLoaded=${isModelLoaded.value}")
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
        Log.d("MainViewModel", "Saved preferences: userName=${userName.value}, assistantName=${assistantName.value}, modelPath=${modelPath.value}")
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

        val chatId = currentChatId ?: UUID.randomUUID().toString()

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
            date = Date(),
            messages = messages.toList()
        )
        val file = File(chatsDir, "$chatId.json")

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

    fun loadChat(chatId: String) {
        val file = File(chatsDir, "$chatId.json")
        if (file.exists()) {
            try {
                val json = file.readText()
                val chatHistory = Json.decodeFromString<ChatHistory>(json)
                messages.clear()
                messages.addAll(chatHistory.messages)
                currentChatId = chatId
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

    fun setModelPath(modelSource: Any) {
        viewModelScope.launch {
            try {
                isLoadingModel.value = true
                importError.value = ""
                val modelPathStr: String
                when (modelSource) {
                    is String -> {
                        val file = File(modelSource)
                        Log.d("MainViewModel", "setModelPath: modelSource=$modelSource, file.exists=${file.exists()}")
                        if (file.exists()) {
                            if (!file.name.endsWith(".gguf", ignoreCase = true)) {
                                importError.value = "Model file must be a .gguf file: ${file.name}"
                                setModelLoaded(false)
                                Log.e("MainViewModel", "Invalid file type: ${file.name}, isModelLoaded=${isModelLoaded.value}")
                                return@launch
                            }
                            modelPathStr = modelSource
                        } else {
                            importError.value = "Model file does not exist: $modelSource"
                            setModelLoaded(false)
                            Log.e("MainViewModel", "Model file does not exist: $modelSource, isModelLoaded=${isModelLoaded.value}")
                            return@launch
                        }
                    }
                    is Uri -> {
                        val uri = modelSource
                        val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                if (displayNameIndex != -1) cursor.getString(displayNameIndex) else null
                            } else null
                        } ?: uri.lastPathSegment ?: "model.gguf"
                        if (!fileName.endsWith(".gguf", ignoreCase = true)) {
                            importError.value = "Please select a .gguf file"
                            setModelLoaded(false)
                            Log.e("MainViewModel", "Invalid file type: $fileName, isModelLoaded=${isModelLoaded.value}")
                            return@launch
                        }
                        val internalFile = File(context.filesDir, fileName)
                        context.contentResolver.openInputStream(uri)?.use { inputStream ->
                            FileOutputStream(internalFile).use { outputStream ->
                                inputStream.copyTo(outputStream)
                            }
                        }
                        if (internalFile.exists()) {
                            modelPathStr = internalFile.absolutePath
                            Log.d("MainViewModel", "Copied file from URI: $uri, new path: $modelPathStr")
                        } else {
                            importError.value = "Failed to copy model file from $uri"
                            setModelLoaded(false)
                            Log.e("MainViewModel", "Failed to copy file from URI: $uri, isModelLoaded=${isModelLoaded.value}")
                            return@launch
                        }
                    }
                    else -> {
                        importError.value = "Invalid model source: $modelSource"
                        setModelLoaded(false)
                        Log.e("MainViewModel", "Invalid model source: $modelSource, isModelLoaded=${isModelLoaded.value}")
                        return@launch
                    }
                }
                Log.d("MainViewModel", "Attempting to load model from path: $modelPathStr")
                if (_isModelLoaded.value) {
                    Log.d("MainViewModel", "Unloading existing model before loading new one")
                    try {
                        llama.unload()
                        Log.d("MainViewModel", "Unloaded existing model")
                    } catch (e: Exception) {
                        Log.e("MainViewModel", "Error unloading model: ${e.message}")
                    }
                }
                llama.load(modelPathStr)
                modelPath.value = modelPathStr
                selectedFileName.value = modelPathStr.substringAfterLast('/')
                selectedUri.value = Uri.fromFile(File(modelPathStr))
                setModelLoaded(true)
                savePrefs(sharedPrefs) // Ensure the path is saved
                Log.d("MainViewModel", "Model loaded successfully: $modelPathStr, selectedFileName=${selectedFileName.value}, isModelLoaded=${isModelLoaded.value}")
            } catch (e: Exception) {
                importError.value = "Failed to load model: ${e.message}"
                setModelLoaded(false)
                Log.e("MainViewModel", "Model load error: ${e.stackTraceToString()}, isModelLoaded=${isModelLoaded.value}")
            } finally {
                isLoadingModel.value = false
            }
        }
    }

    fun startNewChat() {
        Log.d("MainViewModel", "Starting new chat")
        saveCurrentChat()
        messages.clear()
        currentChatId = null
        sendJob?.cancel()
        isGenerating.value = false
        if (!_isModelLoaded.value && modelPath.value.isNotEmpty() && File(modelPath.value).exists()) {
            viewModelScope.launch {
                try {
                    Log.d("MainViewModel", "Model not loaded, attempting to load: ${modelPath.value}")
                    try {
                        llama.unload()
                        Log.d("MainViewModel", "Unloaded any existing model")
                    } catch (e: Exception) {
                        Log.e("MainViewModel", "Error unloading model: ${e.message}")
                    }
                    llama.load(modelPath.value)
                    setModelLoaded(true)
                    Log.d("MainViewModel", "Model loaded successfully in startNewChat: ${modelPath.value}, isModelLoaded=${isModelLoaded.value}")
                } catch (e: Exception) {
                    setModelLoaded(false)
                    Log.e("MainViewModel", "Model load error in startNewChat: ${e.stackTraceToString()}, isModelLoaded=${isModelLoaded.value}")
                }
            }
        } else {
            Log.d("MainViewModel", "Model already loaded or no model to load: isModelLoaded=${_isModelLoaded.value}, modelPath=${modelPath.value}")
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
        messages.add("${userName.value}: $prompt")
        sendJob = viewModelScope.launch {
            try {
                Log.d("MainViewModel", "Sending prompt: $prompt")
                Log.d("MainViewModel", "Current model state: modelPath=${modelPath.value}, isModelLoaded=${isModelLoaded.value}")
                if (modelPath.value.isEmpty() || !isModelLoaded.value) {
                    messages.add("${assistantName.value}: No model loaded. Please load a model from Settings.")
                    isGenerating.value = false
                    Log.d("MainViewModel", "No model loaded: modelPath=${modelPath.value}, isModelLoaded=${isModelLoaded.value}")
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
            setModelLoaded(false)
            Log.d("MainViewModel", "ViewModel cleared, unloaded LLM")
        }
    }
}
