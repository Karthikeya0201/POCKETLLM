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
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.ChatBubble
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.foundation.Image

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    navController: NavHostController,
    viewModel: MainViewModel,
    clipboardManager: ClipboardManager,
    requestPermissions: (Array<String>) -> Unit = {}
) {
    val scrollState = rememberLazyListState()
    val isGenerating by viewModel.isGenerating
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val currentDate = SimpleDateFormat("MMM dd, yyyy").format(Date())
    var isLoadingChat by remember { mutableStateOf(false) }

    val infiniteTransition = rememberInfiniteTransition()
    val dotOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spokenText = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.get(0)
            spokenText?.let {
                viewModel.message.value = it
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.messages.clear()
        viewModel.currentChatId = null
        Log.d("ChatScreen", "Started fresh chat with no welcome message")
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
                navController = navController, // Pass navController
                viewModel = viewModel, // Pass viewModel
                histories = viewModel.chatHistories,
                onHistorySelected = { chatId ->
                    scope.launch {
                        Log.d("ChatScreen", "Loading chat: $chatId")
                        isLoadingChat = true
                        viewModel.loadChat(chatId)
                        drawerState.close()
                        delay(200)
                        scrollState.scrollToItem(viewModel.messages.size - 1)
                        isLoadingChat = false
                        Log.d("ChatScreen", "Chat loaded, drawer closed")
                    }
                },
                onNewChat = {
                    scope.launch {
                        Log.d("ChatScreen", "New chat requested")
                        if (viewModel.messages.isNotEmpty()) {
                            viewModel.startNewChat()
                            Log.d("ChatScreen", "New chat started, drawer closed")
                        } else {
                            Log.d("ChatScreen", "Current chat is empty, no new chat needed")
                        }
                        drawerState.close()
                    }
                },
                onDeleteChat = { chatId ->
                    Log.d("ChatScreen", "Deleting chat: $chatId")
                    viewModel.deleteChat(chatId)
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
            topBar = {
                CenterAlignedTopAppBar(
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
                                tint = MaterialTheme.colorScheme.onPrimaryContainer // Set menu icon color
                            )
                        }
                    },
                    title = {
                        Image(
                            painter = painterResource(id = R.drawable.pocket_llm_icon),
                            contentDescription = "Pocket LLM Logo",
                            modifier = Modifier.height(40.dp).padding(vertical = 4.dp)
                        )
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
                                }
                                drawerState.close()
                            }
                        }) {
                            Icon(
                                Icons.Outlined.ChatBubble,
                                contentDescription = "New Chat",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                if (isLoadingChat) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (viewModel.messages.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Hello, ${viewModel.userName.value}",
                            style = TextStyle(
                                fontSize = 40.sp, // Larger size for greeting
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.SansSerif, // Use a rounded sans-serif font
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
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
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
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth(),
                                horizontalArrangement = if (isUserMessage) Arrangement.End else Arrangement.Start
                            ) {
                                MessageBubble(
                                    text = if (isLoading) "..." else displayText,
                                    isUser = isUserMessage,
                                    modifier = Modifier
                                        .widthIn(max = 300.dp)
                                        .padding(horizontal = 8.dp),
                                    isLoading = isLoading
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                            RoundedCornerShape(24.dp)
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
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
                            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            disabledContainerColor = androidx.compose.ui.graphics.Color.Transparent
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
                        enabled = !isGenerating && !isLoadingChat
                    ) {
                        Icon(
                            Icons.Filled.Mic,
                            contentDescription = "Voice Input",
                            tint = MaterialTheme.colorScheme.primary
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
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
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
                            enabled = !isGenerating && viewModel.message.value.isNotBlank() && !isLoadingChat
                        ) {
                            Icon(
                                Icons.Filled.ArrowUpward,
                                contentDescription = "Send",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    } else {
                        IconButton(
                            onClick = { navController.navigate("talkWithAI") },
                            enabled = !isGenerating && !isLoadingChat
                        ) {
                            Icon(
                                Icons.Filled.GraphicEq,
                                contentDescription = "Talk with AI",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }
        }
    }
}
