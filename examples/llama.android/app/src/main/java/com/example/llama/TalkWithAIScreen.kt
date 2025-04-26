// imports remain unchanged
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

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

    var textToSpeech by remember { mutableStateOf<TextToSpeech?>(null) }

    val speechRecognizer = remember {
        try {
            SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { errorMessage = null }
                    override fun onBeginningOfSpeech() { isListening = true; isThinking = false; isSpeaking = false }
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { isListening = false; isThinking = true }
                    override fun onError(error: Int) {
                        isListening = false; isThinking = false; isSpeaking = false
                        errorMessage = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected. Please try again."
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy. Please wait and try again."
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
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
                            coroutineScope.launch {
                                val userMessage = "${viewModel.userName.value}: $recognizedText"
                                viewModel.send(userMessage)
                                isThinking = true
                            }
                        }
                    }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        } catch (e: Exception) {
            null
        }
    }

    LaunchedEffect(Unit) {
        textToSpeech = TextToSpeech(context) { status ->
            ttsInitialized = status == TextToSpeech.SUCCESS
            if (ttsInitialized) textToSpeech?.language = Locale.getDefault()
        }
    }

    var lastIsGenerating by remember { mutableStateOf(false) }
    LaunchedEffect(viewModel.isGenerating.value, viewModel.messages.size) {
        val isGenerating = viewModel.isGenerating.value
        val lastMessage = viewModel.messages.lastOrNull()
        if (lastIsGenerating && !isGenerating && lastMessage != null &&
            lastMessage.startsWith("${viewModel.assistantName.value}:")
        ) {
            isThinking = false
            val aiResponse = lastMessage.substringAfter("${viewModel.assistantName.value}: ").trim()
            if (ttsInitialized && textToSpeech != null) {
                isSpeaking = true
                textToSpeech?.speak(aiResponse, TextToSpeech.QUEUE_FLUSH, null, "AI_RESPONSE")
                lastSpokenMessage = lastMessage
            }
        }
        lastIsGenerating = isGenerating
    }

    DisposableEffect(textToSpeech) {
        val listener = TextToSpeech.OnUtteranceCompletedListener {
            isSpeaking = false
        }
        textToSpeech?.setOnUtteranceCompletedListener(listener)
        onDispose { textToSpeech?.setOnUtteranceCompletedListener(null) }
    }

    val pulseScale = remember { Animatable(1f) }
    val thinkingScale = rememberInfiniteTransition()
    val thinkingPulse by thinkingScale.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    LaunchedEffect(isListening) {
        if (isListening) {
            while (isListening) {
                pulseScale.animateTo(
                    targetValue = 1.08f,
                    animationSpec = keyframes {
                        durationMillis = 3000
                        1.00f at 0 with LinearOutSlowInEasing
                        1.08f at 1500 with FastOutSlowInEasing
                        1.00f at 3000
                    }
                )
            }
        } else {
            pulseScale.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 500,
                    easing = FastOutSlowInEasing
                )
            )
        }
    }

    fun startListening(speechRecognizer: SpeechRecognizer?) {
        if (speechRecognizer == null) {
            errorMessage = "Speech recognition is not available on this device."
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            }
            speechRecognizer.startListening(intent)
        } catch (e: Exception) {
            isListening = false
            errorMessage = "Failed to start listening: ${e.message}"
        }
    }

    LaunchedEffect(Unit) {
        if (viewModel.currentChatId == null) {
            viewModel.currentChatId = UUID.randomUUID().toString()
            viewModel.messages.clear()
            viewModel.messages.add("${viewModel.assistantName.value}: Hello, ${viewModel.userName.value}! I'm here to assist you.")
        }
    }

    val infiniteTransition = rememberInfiniteTransition()
    val gradientOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    val gradientBrush = when {
        isListening -> Brush.linearGradient(
            colors = listOf(Color(0xFF1976D2), Color(0xFF64B5F6)),
            start = Offset(0f, gradientOffset * 200f),
            end = Offset(200f, (1f - gradientOffset) * 200f)
        )
        isThinking -> Brush.linearGradient(
            colors = listOf(Color(0xFFFFF176), Color(0xFFFFA000)),
            start = Offset(0f, gradientOffset * 200f),
            end = Offset(200f, (1f - gradientOffset) * 200f)
        )
        isSpeaking -> Brush.linearGradient(
            colors = listOf(Color(0xFF388E3C), Color(0xFF81C784)),
            start = Offset(0f, gradientOffset * 200f),
            end = Offset(200f, (1f - gradientOffset) * 200f)
        )
        else -> Brush.linearGradient(
            colors = listOf(Color(0xFF757575), Color(0xFFBDBDBD)),
            start = Offset(0f, gradientOffset * 200f),
            end = Offset(200f, (1f - gradientOffset) * 200f)
        )
    }

    val cloudCount = 6
    val cloudStates = remember {
        List(cloudCount) {
            mutableStateOf(Random.nextFloat() * 0.5f + 0.5f)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            cloudStates.forEach { cloud -> cloud.value = Random.nextFloat() * 0.5f + 0.5f }
            delay(3000L)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Talk to Pocket LLM", color = MaterialTheme.colorScheme.primary) }, // Rephrased title
                navigationIcon = {
                    IconButton(onClick = {
                        speechRecognizer?.stopListening()
                        textToSpeech?.stop()
                        isListening = false
                        isThinking = false
                        isSpeaking = false
                        navController.popBackStack()
                    }) {
                        Icon(
                            Icons.Filled.ArrowBack, // Use arrow symbol
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.primary // Match Settings/Chat header icon color
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) // Match Settings header bg
                )
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
            Box(
                modifier = Modifier
                    .size(320.dp) // Increased from 280.dp to 320.dp for a larger circle
                    .clip(CircleShape)
                    .background(gradientBrush)
                    .clickable {
                        if (!isListening && !isThinking && !isSpeaking) startListening(speechRecognizer)
                    },
                contentAlignment = Alignment.Center
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    val canvasWidth = size.width
                    val canvasHeight = size.height
                    val waveSpeed = gradientOffset * 2 * PI.toFloat()

                    cloudStates.forEachIndexed { index, cloudScale ->
                        val x = (index + gradientOffset) * canvasWidth / cloudCount
                        val y = (0.3f + 0.4f * Random.nextFloat()) * canvasHeight
                        drawCircle(
                            color = Color.White.copy(alpha = 0.2f),
                            radius = 20f * cloudScale.value,
                            center = Offset(x % canvasWidth, y)
                        )
                    }

                    val waveCount = 3
                    for (i in 0 until waveCount) {
                        val path = Path()
                        val yOffset = canvasHeight / (waveCount + 1) * (i + 1)
                        path.moveTo(0f, yOffset)

                        for (x in 0..canvasWidth.toInt() step 10) {
                            val angle = (x.toFloat() / canvasWidth) * 2 * PI.toFloat() + waveSpeed
                            val y = (sin(angle.toDouble()) * 15f).toFloat() + yOffset
                            path.lineTo(x.toFloat(), y)
                        }
                        drawPath(path, color = Color.White.copy(alpha = 0.5f), style = Stroke(width = 2f))
                    }
                }

                when {
                    isListening -> {
                        Icon(
                            imageVector = Icons.Filled.Mic,
                            contentDescription = "Mic",
                            tint = Color.White,
                            modifier = Modifier.size(64.dp)
                        )
                    }
                    isSpeaking -> {
                        IconButton(
                            onClick = {
                                textToSpeech?.stop()
                                isSpeaking = false
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Pause,
                                contentDescription = "Pause",
                                tint = Color.White,
                                modifier = Modifier.size(64.dp)
                            )
                        }
                    }
                    isThinking -> {
                        // No icon when thinking
                    }
                    else -> {
                        Icon(
                            imageVector = Icons.Filled.TouchApp,
                            contentDescription = "Tap to talk",
                            tint = Color.White,
                            modifier = Modifier.size(64.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            if (errorMessage != null) {
                Text(errorMessage!!, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
