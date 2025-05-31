package com.example.llama

import com.example.llama.component.MessageBubble
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Intent
import android.speech.RecognizerIntent
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.example.llama.component.ChatHistoryDrawer
import com.example.llama.component.ModelSelectionDialog
import com.example.llama.model.Downloadable
import com.example.llama.ui.theme.PurplePrimary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TileMode
import java.io.File
import android.content.ClipData
import android.content.Context
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import com.example.llama.MainViewModel.RagDbState
import com.example.llama.MainViewModel.RagDbState.selectedRagDbIndex

private fun getModelFileName(modelName: String): String {
    return "${modelName.lowercase().replace(" ", "_")}.gguf"
}

sealed class ChatLoadingState {
    object Idle : ChatLoadingState()
    object Loading : ChatLoadingState()
    data class Success(val messages: List<String>) : ChatLoadingState()
    data class Error(val message: String) : ChatLoadingState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    navController: NavHostController,
    viewModel: MainViewModel,
    clipboardManager: ClipboardManager,
    modelOptions: List<Downloadable>,
    requestPermissions: (Array<String>) -> Unit = {}
) {
    val scrollState = rememberLazyListState()
    val isGenerating by viewModel.isGenerating
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var chatLoadingState by remember { mutableStateOf<ChatLoadingState>(ChatLoadingState.Idle) }
    var showModelDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spokenText = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.get(0)
            spokenText?.let {
                viewModel.message.value = it
            }
        }
    }

    var longPressedMessage by remember { mutableStateOf<String?>(null) }

    val loadedModel by remember(viewModel.modelPath.value, viewModel.isModelLoaded.value) {
        derivedStateOf {
            if (viewModel.isModelLoaded.value && viewModel.modelPath.value.isNotEmpty()) {
                val modelName = viewModel.getModelDisplayName(viewModel.modelPath.value.substringAfterLast('/'))
                val isDownloaded = modelOptions.any { getModelFileName(it.name) == File(viewModel.modelPath.value).name }
                Pair(modelName, isDownloaded)
            } else {
                null
            }
        }
    }

    LaunchedEffect(Unit) {
        Log.d("ChatScreen", "Initial state: modelPath=${viewModel.modelPath.value}, isModelLoaded=${viewModel.isModelLoaded.value}, selectedFileName=${viewModel.selectedFileName.value}")
        chatLoadingState = ChatLoadingState.Idle
        Log.d("ChatScreen", "Started fresh chat with no welcome message")

        if (!viewModel.isModelLoaded.value && viewModel.modelPath.value.isNotEmpty()) {
            val modelFile = File(viewModel.modelPath.value)
            if (modelFile.exists()) {
                Log.d("ChatScreen", "Attempting to reload model from path: ${viewModel.modelPath.value}")
                try {
                    viewModel.setModelPath(viewModel.modelPath.value)
                    Log.d("ChatScreen", "Model reloaded successfully: ${viewModel.modelPath.value}")
                } catch (e: Exception) {
                    Log.e("ChatScreen", "Failed to reload model: ${e.stackTraceToString()}")
                    viewModel.importError.value = "Failed to load model: ${e.message}"
                    chatLoadingState = ChatLoadingState.Error("Please select a model to continue.")
                }
            } else {
                Log.e("ChatScreen", "Model file not found: ${viewModel.modelPath.value}")
                viewModel.importError.value = "Model file not found. Please select a new model."
                viewModel.modelPath.value = ""
                viewModel.setModelLoaded(false)
                viewModel.savePrefs(context.getSharedPreferences("PocketLLMPrefs", Context.MODE_PRIVATE))
            }
        } else if (!viewModel.isModelLoaded.value) {
            Log.d("ChatScreen", "No model loaded, prompting user to select one")
            chatLoadingState = ChatLoadingState.Error("Please select a model to continue.")
        }
    }

    LaunchedEffect(chatLoadingState) {
        if (chatLoadingState is ChatLoadingState.Error && (chatLoadingState as ChatLoadingState.Error).message.contains("Please select a model")) {
            showModelDialog = true
            Log.d("ChatScreen", "Showing ModelSelectionDialog due to no model loaded")
        }
    }

    LaunchedEffect(viewModel.isGenerating.value, viewModel.messages.size) {
        if (viewModel.messages.isNotEmpty()) {
            scope.launch {
                try {
                    scrollState.scrollToItem(viewModel.messages.size - 1)
                    Log.d("ChatScreen", "Scrolled to latest message, size: ${viewModel.messages.size}")
                    if (viewModel.isGenerating.value) {
                        while (viewModel.isGenerating.value) {
                            delay(50)
                            val layoutInfo = scrollState.layoutInfo
                            val lastItem = layoutInfo.visibleItemsInfo.lastOrNull()
                            if (lastItem != null && lastItem.index == viewModel.messages.size - 1) {
                                val viewportHeight = layoutInfo.viewportEndOffset
                                val itemBottom = lastItem.offset + lastItem.size
                                if (itemBottom > viewportHeight) {
                                    scrollState.animateScrollToItem(viewModel.messages.size - 1, 0)
                                    Log.d("ChatScreen", "Auto-scrolling during generation")
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ChatScreen", "Scroll error: ${e.message}")
                }
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ChatHistoryDrawer(
                navController = navController,
                viewModel = viewModel,
                histories = viewModel.chatHistories,
                onHistorySelected = { chatId ->
                    scope.launch {
                        Log.d("ChatScreen", "Loading chat: $chatId")
                        chatLoadingState = ChatLoadingState.Loading
                        try {
                            viewModel.loadChat(chatId)
                            chatLoadingState = ChatLoadingState.Success(viewModel.messages.toList())
                            drawerState.close()
                            delay(200)
                            scrollState.scrollToItem(viewModel.messages.size - 1)
                            Log.d("ChatScreen", "Chat loaded successfully, drawer closed")
                        } catch (e: Exception) {
                            Log.e("ChatScreen", "Failed to load chat: ${e.message}")
                            chatLoadingState = ChatLoadingState.Error("Failed to load chat: ${e.message}")
                        }
                    }
                },
                onNewChat = {
                    scope.launch {
                        Log.d("ChatScreen", "New chat requested")
                        if (viewModel.messages.isNotEmpty()) {
                            viewModel.startNewChat()
                            chatLoadingState = ChatLoadingState.Idle
                            Log.d("ChatScreen", "New chat started, drawer closed")
                        } else {
                            Log.d("ChatScreen", "Current chat is empty, no new chat needed")
                        }
                        drawerState.close()
                    }
                },
                onDeleteChat = { chatId ->
                    scope.launch {
                        Log.d("ChatScreen", "Deleting chat: $chatId")
                        viewModel.deleteChat(chatId)
                    }
                },
                getChatMessages = { chatId ->
                    viewModel.chatHistories.find { it.id == chatId }?.messages ?: emptyList()
                },
                requestPermissions = requestPermissions,
                modifier = Modifier.fillMaxHeight()
            )
        }
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                CenterAlignedTopAppBar(
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.statusBars),
                    navigationIcon = {
                        IconButton(onClick = {
                            scope.launch {
                                Log.d("ChatScreen", "Opening drawer")
                                drawerState.open()
                            }
                        }) {
                            Icon(
                                Icons.Filled.Menu,
                                contentDescription = "Open Chat Drawer",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    },
                    title = {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .background(
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable { showModelDialog = true }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = Color(0xFFD9BDFE),
                                modifier = Modifier.size(30.dp)
                            ) {
                                if (loadedModel != null) {
                                    Image(
                                        painter = painterResource(
                                            id = if (loadedModel!!.second) {
                                                when (loadedModel!!.first) {
                                                    "Orca Mini-3B" -> R.drawable.ic_orca_icon
                                                    "Gemma 3-4B" -> R.drawable.ic_gemma_icon
                                                    "Mistral-7B" -> R.drawable.ic_mistral_icon
                                                    else -> R.drawable.ic_ai_chip
                                                }
                                            } else {
                                                R.drawable.ic_ai_chip
                                            }
                                        ),
                                        contentDescription = "Loaded Model",
                                        modifier = Modifier
                                            .padding(6.dp)
                                            .fillMaxSize()
                                    )
                                } else {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_ai_chip),
                                        contentDescription = "Choose Model",
                                        tint = PurplePrimary,
                                        modifier = Modifier
                                            .padding(6.dp)
                                            .fillMaxSize()
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = loadedModel?.first ?: "Choose a Model",
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center
                            )
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = Color.Transparent
                    ),
                    actions = {
                        IconButton(onClick = {
                            scope.launch {
                                Log.d("ChatScreen", "New chat requested from message icon")
                                if (viewModel.messages.isNotEmpty()) {
                                    viewModel.startNewChat()
                                    chatLoadingState = ChatLoadingState.Idle
                                }
                                drawerState.close()
                            }
                        }) {
                            Icon(
                                Icons.Filled.Chat,
                                contentDescription = "New Chat",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }
                )
            },
            bottomBar = {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars),
                    shadowElevation = 8.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                                RoundedCornerShape(24.dp)
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextField(
                                value = viewModel.message.value,
                                onValueChange = { newValue -> viewModel.message.value = newValue },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                textStyle = TextStyle(
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onBackground
                                ),
                                placeholder = {
                                    Text(
                                        "Ask anything",
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                                        fontSize = 14.sp
                                    )
                                },
                                colors = TextFieldDefaults.colors(
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    disabledIndicatorColor = Color.Transparent,
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    disabledContainerColor = Color.Transparent
                                )
                            )

                            Spacer(modifier = Modifier.width(8.dp))

                            IconButton(
                                onClick = {
                                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                                    intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak something...")
                                    try {
                                        launcher.launch(intent)
                                    } catch (e: ActivityNotFoundException) {
                                        Toast.makeText(context, "Speech recognition not supported", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                enabled = !isGenerating && chatLoadingState !is ChatLoadingState.Loading
                            ) {
                                Icon(
                                    Icons.Filled.Mic,
                                    contentDescription = "Voice Input",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(30.dp)
                                )
                            }

                            if (isGenerating) {
                                IconButton(
                                    onClick = { viewModel.pause() },
                                    enabled = true
                                ) {
                                    Icon(
                                        Icons.Filled.Pause,
                                        contentDescription = "Pause",
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(30.dp)
                                    )
                                }
                            } else if (viewModel.message.value.isNotBlank()) {
                                IconButton(
                                    onClick = {
                                        Log.d("ChatScreen", "Sending message: ${viewModel.message.value}")
                                        viewModel.send(viewModel.message.value)
                                        viewModel.message.value = ""
                                        focusManager.clearFocus()
                                    },
                                    enabled = !isGenerating && viewModel.message.value.isNotBlank() && chatLoadingState !is ChatLoadingState.Loading
                                ) {
                                    Icon(
                                        Icons.Filled.ArrowUpward,
                                        contentDescription = "Send",
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(30.dp)
                                    )
                                }
                            } else {
                                IconButton(
                                    onClick = { navController.navigate("talkWithAI") },
                                    enabled = !isGenerating && chatLoadingState !is ChatLoadingState.Loading
                                ) {
                                    Icon(
                                        Icons.Filled.GraphicEq,
                                        contentDescription = "Talk with AI",
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(30.dp)
                                    )
                                }
                            }
                        }

                        // RAG Button and Drop-up Menu
                        var showRagMenu by remember { mutableStateOf(false) }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Button(
                                onClick = {
                                    if (RagDbState.selectedRagDbIndex.value != 0) {
                                        RagDbState.selectedRagDbIndex.value = 0
                                        showRagMenu = false
                                        Log.d("ChatScreen", "RAG toggled to off state (index: 0)")
                                    } else {
                                        showRagMenu = !showRagMenu
                                    }
                                },
                                modifier = Modifier
                                    .height(36.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (RagDbState.selectedRagDbIndex.value == 0)
                                        Color.Gray
                                    else
                                        MaterialTheme.colorScheme.primary,
                                    contentColor = if (RagDbState.selectedRagDbIndex.value == 0)
                                        Color.White
                                    else
                                        MaterialTheme.colorScheme.onPrimary
                                )
                            ) {
                                Text(
                                    text = "RAG: ${
                                        if (RagDbState.selectedRagDbIndex.value == 0)
                                            "Off"
                                        else
                                            RagDbState.ragDbMap.value[RagDbState.selectedRagDbIndex.value]?.removeSuffix(".db") ?: "Select DB"
                                    }",
                                    fontSize = 14.sp
                                )
                            }

                            DropdownMenu(
                                expanded = showRagMenu,
                                onDismissRequest = { showRagMenu = false },
                                modifier = Modifier
                                    .background(MaterialTheme.colorScheme.surface)
                            ) {
                                RagDbState.ragDbMap.value.filter { it.key != 0 }.forEach { (index, dbName) ->
                                    DropdownMenuItem(
                                        text = { Text(dbName.removeSuffix(".db"), fontSize = 14.sp) },
                                        onClick = {
                                            RagDbState.selectedRagDbIndex.value = index
                                            showRagMenu = false
                                            Log.d("ChatScreen", "Selected RAG DB: $dbName (index: $index)")
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when (chatLoadingState) {
                    is ChatLoadingState.Loading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    is ChatLoadingState.Error -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = (chatLoadingState as ChatLoadingState.Error).message,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(
                                    onClick = {
                                        scope.launch {
                                            chatLoadingState = ChatLoadingState.Loading
                                            try {
                                                viewModel.loadChat(viewModel.currentChatId ?: return@launch)
                                                chatLoadingState = ChatLoadingState.Success(viewModel.messages.toList())
                                                scrollState.scrollToItem(viewModel.messages.size - 1)
                                            } catch (e: Exception) {
                                                chatLoadingState = ChatLoadingState.Error("Failed to load chat: ${e.message}")
                                            }
                                        }
                                    }
                                ) {
                                    Text("Retry")
                                }
                            }
                        }
                    }
                    is ChatLoadingState.Idle, is ChatLoadingState.Success -> {
                        if (viewModel.messages.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Hello, ${viewModel.userName.value}",
                                    style = TextStyle(
                                        fontSize = 40.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.SansSerif,
                                        brush = Brush.linearGradient(
                                            colors = listOf(
                                                MaterialTheme.colorScheme.primary,
                                                MaterialTheme.colorScheme.secondary
                                            ),
                                            tileMode = TileMode.Clamp
                                        ),
                                        textAlign = TextAlign.Center
                                    )
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                    .pointerInput(Unit) {
                                        detectTapGestures(
                                            onTap = {
                                                longPressedMessage = null
                                                Log.d("ChatScreen", "Tapped on LazyColumn, hiding user copy icon")
                                            }
                                        )
                                    },
                                state = scrollState,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(viewModel.messages) { message ->
                                    val isUserMessage = message.startsWith("${viewModel.userName.value}:")
                                    val displayText = if (isUserMessage) {
                                        message.removePrefix("${viewModel.userName.value}:").trimStart()
                                    } else {
                                        message.removePrefix("${viewModel.assistantName.value}:").trimStart()
                                    }
                                    val isLoading = !isUserMessage && message.endsWith(": ") && isGenerating
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 8.dp),
                                        horizontalAlignment = if (isUserMessage) Alignment.End else Alignment.Start
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .widthIn(max = 300.dp),
                                            horizontalAlignment = Alignment.End
                                        ) {
                                            MessageBubble(
                                                text = if (isLoading) "..." else displayText,
                                                isUser = isUserMessage,
                                                modifier = Modifier
                                                    .widthIn(max = 300.dp)
                                                    .pointerInput(Unit) {
                                                        if (isUserMessage) {
                                                            detectTapGestures(
                                                                onLongPress = {
                                                                    longPressedMessage = message
                                                                    Log.d("ChatScreen", "Long-pressed user message: $displayText")
                                                                },
                                                                onPress = {
                                                                    val wasLongPressed = longPressedMessage == message
                                                                    tryAwaitRelease()
                                                                    if (wasLongPressed) {
                                                                        longPressedMessage = null
                                                                        Log.d("ChatScreen", "Released long-press on user message")
                                                                    }
                                                                }
                                                            )
                                                        }
                                                    },
                                                isLoading = isLoading
                                            )
                                            if (isUserMessage && longPressedMessage == message) {
                                                IconButton(
                                                    onClick = {
                                                        clipboardManager.setPrimaryClip(
                                                            ClipData.newPlainText("User Message", displayText)
                                                        )
                                                        Toast.makeText(
                                                            context,
                                                            "Message copied to clipboard",
                                                            Toast.LENGTH_SHORT
                                                        ).show()
                                                        Log.d("ChatScreen", "Copied user message to clipboard: $displayText")
                                                        longPressedMessage = null
                                                    },
                                                    modifier = Modifier
                                                        .size(24.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Filled.ContentCopy,
                                                        contentDescription = "Copy Message",
                                                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            } else if (!isUserMessage && !isLoading) {
                                                IconButton(
                                                    onClick = {
                                                        clipboardManager.setPrimaryClip(
                                                            ClipData.newPlainText("Assistant Message", displayText)
                                                        )
                                                        Toast.makeText(
                                                            context,
                                                            "Message copied to clipboard",
                                                            Toast.LENGTH_SHORT
                                                        ).show()
                                                        Log.d("ChatScreen", "Copied assistant message to clipboard: $displayText")
                                                    },
                                                    modifier = Modifier
                                                        .size(24.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Filled.ContentCopy,
                                                        contentDescription = "Copy Message",
                                                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showModelDialog) {
        ModelSelectionDialog(
            onDismiss = { showModelDialog = false },
            onModelSelected = { modelPath ->
                scope.launch {
                    Log.d("ChatScreen", "Model selected: $modelPath")
                    chatLoadingState = ChatLoadingState.Loading
                    try {
                        viewModel.setModelPath(modelPath)
                        chatLoadingState = ChatLoadingState.Idle
                        Log.d("ChatScreen", "Model loaded successfully: $modelPath")
                    } catch (e: Exception) {
                        Log.e("ChatScreen", "Failed to load model: ${e.stackTraceToString()}")
                        chatLoadingState = ChatLoadingState.Error("Failed to load model: ${e.message}")
                    }
                }
            },
            modelOptions = modelOptions,
            viewModel = viewModel,
        )
    }
}
