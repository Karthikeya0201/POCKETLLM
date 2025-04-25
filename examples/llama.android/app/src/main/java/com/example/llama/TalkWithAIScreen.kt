package com.example.llama
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TalkWithAIScreen(
    navController: NavHostController,
    viewModel: MainViewModel,
    requestPermissions: (Array<String>) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var isListening by remember { mutableStateOf(false) }
    var isThinking by remember { mutableStateOf(false) }
    var isSpeaking by remember { mutableStateOf(false) }
    var isPaused by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var ttsInitialized by remember { mutableStateOf(false) }
    var lastSpokenMessage by remember { mutableStateOf<String?>(null) }

    // Use mutableStateOf to allow reinitialization of TextToSpeech
    var textToSpeech by remember { mutableStateOf<TextToSpeech?>(null) }

    // Initialize SpeechRecognizer
    val speechRecognizer = remember {
        try {
            SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.d("TalkWithAIScreen", "Ready for speech")
                        errorMessage = null
                    }

                    override fun onBeginningOfSpeech() {
                        Log.d("TalkWithAIScreen", "Speech started")
                        isListening = true
                        isThinking = false
                        isSpeaking = false
                    }

                    override fun onRmsChanged(rmsdB: Float) {}

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        Log.d("TalkWithAIScreen", "Speech ended")
                        isListening = false
                        isThinking = true // Transition to thinking state
                    }

                    override fun onError(error: Int) {
                        Log.e("TalkWithAIScreen", "Speech recognition error: $error")
                        isListening = false
                        isThinking = false
                        isSpeaking = false
                        errorMessage = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected. Please try again."
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy. Please wait and try again."
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                                errorMessage = "Microphone permission required."
                                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO))
                                "Microphone permission required. Please grant it."
                            }
                            else -> "Speech recognition error: $error"
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            val recognizedText = matches[0]
                            Log.d("TalkWithAIScreen", "Recognized text: $recognizedText")
                            coroutineScope.launch {
                                val userMessage = "${viewModel.userName.value}: $recognizedText"
                                viewModel.send(userMessage)
                                isThinking = true // Wait for AI response
                            }
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {}

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        } catch (e: Exception) {
            Log.e("TalkWithAIScreen", "Failed to create SpeechRecognizer: ${e.message}")
            null
        }
    }

    // Initialize TextToSpeech
    LaunchedEffect(Unit) {
        textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                Log.d("TalkWithAIScreen", "TextToSpeech initialized successfully")
                ttsInitialized = true
                textToSpeech?.language = Locale.getDefault()
            } else {
                Log.e("TalkWithAIScreen", "TextToSpeech initialization failed with status: $status")
                errorMessage = "Text-to-speech initialization failed. Check TTS settings."
            }
        }

        // Wait and reinitialize if necessary
        delay(1000)
        if (!ttsInitialized) {
            Log.w("TalkWithAIScreen", "Forcing TTS reinitialization")
            textToSpeech?.shutdown()
            textToSpeech = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ttsInitialized = true
                    textToSpeech?.language = Locale.getDefault()
                    Log.d("TalkWithAIScreen", "TTS reinitialized successfully")
                } else {
                    Log.e("TalkWithAIScreen", "TTS reinitialization failed with status: $status")
                    errorMessage = "TTS reinitialization failed. Please install a TTS engine."
                }
            }
        }
    }

    // Observe isGenerating and messages to trigger TTS when response is ready
    var lastIsGenerating by remember { mutableStateOf(false) }
    LaunchedEffect(viewModel.isGenerating.value, viewModel.messages.size) {
        val isGenerating = viewModel.isGenerating.value
        val lastMessage = viewModel.messages.lastOrNull()
        // Detect transition from generating -> not generating (response complete)
        if (lastIsGenerating && !isGenerating && lastMessage != null &&
            lastMessage.startsWith("${viewModel.assistantName.value}:")
        ) {
            isThinking = false
            val aiResponse = lastMessage.substringAfter("${viewModel.assistantName.value}: ").trim()
            if (ttsInitialized && textToSpeech != null &&
                textToSpeech!!.isLanguageAvailable(Locale.getDefault()) >= TextToSpeech.LANG_AVAILABLE
            ) {
                isSpeaking = true
                textToSpeech?.speak(aiResponse, TextToSpeech.QUEUE_FLUSH, null, "AI_RESPONSE")
                lastSpokenMessage = lastMessage
            } else {
                errorMessage = "TTS not ready or language unsupported. Please check settings."
            }
        }
        // Update for next transition
        lastIsGenerating = isGenerating
    }

    // Optionally, listen for TTS completion to set isSpeaking = false
    DisposableEffect(textToSpeech) {
        val listener = object : TextToSpeech.OnUtteranceCompletedListener {
            override fun onUtteranceCompleted(utteranceId: String?) {
                isSpeaking = false
            }
        }
        textToSpeech?.setOnUtteranceCompletedListener(listener)
        onDispose { textToSpeech?.setOnUtteranceCompletedListener(null) }
    }

    // Handle TextToSpeech pause/resume
    LaunchedEffect(isPaused) {
        if (isPaused && isSpeaking) {
            textToSpeech?.stop()
            isSpeaking = false
        } else if (!isPaused && viewModel.messages.lastOrNull()?.startsWith(viewModel.assistantName.value) == true) {
            val lastMessage = viewModel.messages.last()
            val aiResponse = lastMessage.substringAfter("${viewModel.assistantName.value}: ")
            if (ttsInitialized && textToSpeech != null && textToSpeech!!.isLanguageAvailable(Locale.getDefault()) >= TextToSpeech.LANG_AVAILABLE) {
                textToSpeech?.speak(aiResponse, TextToSpeech.QUEUE_ADD, null, "AI_RESPONSE")
                isSpeaking = true
            }
        }
    }

    // Function to start listening
    fun startListening(speechRecognizer: SpeechRecognizer?) {
        if (speechRecognizer == null) {
            errorMessage = "Speech recognition is not available on this device."
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            errorMessage = "Microphone permission required."
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            speechRecognizer.startListening(intent)
        } catch (e: Exception) {
            Log.e("TalkWithAIScreen", "Failed to start listening: ${e.message}")
            isListening = false
            errorMessage = "Failed to start listening: ${e.message}"
        }
    }

    // Ensure single chat session
    LaunchedEffect(Unit) {
        if (viewModel.currentChatId == null) {
            viewModel.currentChatId = UUID.randomUUID().toString()
            val welcomeMessage = "${viewModel.assistantName.value}: Hello, ${viewModel.userName.value}! I'm here to assist you. How can I help you today?"
            viewModel.messages.clear()
            viewModel.messages.add(welcomeMessage)
            Log.d("TalkWithAIScreen", "Started new chat session with ID: ${viewModel.currentChatId}")
        }
    }

    // Animations for different states
    val infiniteTransition = rememberInfiniteTransition()
    val gradientOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    // Gradient for the bubble based on state (improved colors)
    val gradientBrush = when {
        isListening -> Brush.linearGradient(
            colors = listOf(Color(0xFF1976D2), Color(0xFF64B5F6)), // Blue shades
            start = Offset(0f, gradientOffset * 200f),
            end = Offset(200f, (1f - gradientOffset) * 200f)
        )
        isThinking -> Brush.radialGradient(
            colors = listOf(Color(0xFFFFF176), Color(0xFFFFA000)), // Yellow to Orange
            center = Offset(100f, 100f),
            radius = pulseScale * 100f
        )
        isSpeaking -> Brush.linearGradient(
            colors = listOf(Color(0xFF388E3C), Color(0xFF81C784)), // Green shades
            start = Offset(0f, gradientOffset * 200f),
            end = Offset(200f, (1f - gradientOffset) * 200f)
        )
        else -> Brush.linearGradient(
            colors = listOf(Color(0xFF757575), Color(0xFFBDBDBD)), // Gray shades for idle
            start = Offset(0f, 0f),
            end = Offset(200f, 200f)
        )
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Talk with AI") },
                navigationIcon = {
                    IconButton(onClick = {
                        speechRecognizer?.stopListening()
                        textToSpeech?.stop()
                        isListening = false
                        isThinking = false
                        isSpeaking = false
                        navController.popBackStack()
                    }) {
                        Icon(Icons.Filled.Cancel, contentDescription = "Cancel")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Gradient Bubble with clickable to activate mic
            Box(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .clip(CircleShape)
                    .background(gradientBrush)
                    .padding(16.dp)
                    .clickable {
                        if (!isListening) startListening(speechRecognizer)
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = when {
                        isListening -> "Listening..."
                        isThinking -> "Thinking..."
                        isSpeaking -> "Speaking..."
                        else -> "Tap to Speak"
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Error message
            if (errorMessage != null) {
                Text(
                    text = errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Mic and Pause Buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mic Button
                IconButton(
                    onClick = {
                        if (isListening) {
                            speechRecognizer?.stopListening()
                            isListening = false
                        } else {
                            startListening(speechRecognizer)
                        }
                    },
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(if (isListening) MaterialTheme.colorScheme.primary else Color.Gray)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Mic,
                        contentDescription = "Mic",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }

                // Pause/Play Button
                IconButton(
                    onClick = {
                        isPaused = !isPaused
                    },
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(if (isPaused) Color.Gray else MaterialTheme.colorScheme.secondary),
                    enabled = isSpeaking || isPaused
                ) {
                    Icon(
                        imageVector = if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = if (isPaused) "Play" else "Pause",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }

    // Cleanup
    DisposableEffect(Unit) {
        onDispose {
            speechRecognizer?.destroy()
            textToSpeech?.shutdown()
            isThinking = false // <-- Add this line if you want to be extra sure
        }
    }
}
