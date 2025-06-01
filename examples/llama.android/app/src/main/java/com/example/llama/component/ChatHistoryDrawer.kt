package com.example.llama.component

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import com.caverock.androidsvg.SVG
import com.example.llama.MainViewModel
import com.example.llama.R
import com.example.llama.model.ChatHistory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatHistoryDrawer(
    navController: NavHostController,
    viewModel: MainViewModel,
    histories: List<ChatHistory>,
    onHistorySelected: (String) -> Unit,
    onNewChat: () -> Unit,
    onDeleteChat: (String) -> Unit,
    getChatMessages: (String) -> List<String>,
    requestPermissions: (Array<String>) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedChats by remember { mutableStateOf(setOf<String>()) }
    var selectedAction by remember { mutableStateOf<String?>(null) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val density = LocalDensity.current

    // State for PDF export
    var showExportDialog by remember { mutableStateOf(false) }
    var showSuccessDialog by remember { mutableStateOf(false) }
    var showErrorDialog by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    val progressState = remember { MutableStateFlow(0f) }
    val progress by progressState.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    var exportJob by remember { mutableStateOf<Job?>(null) }

    // Log when the composable is rendered
    Log.d("ChatHistoryDrawer", "Rendering ChatHistoryDrawer with ${histories.size} chat histories")

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override suspend fun onPreFling(available: Velocity): Velocity {
                Log.d("ChatHistoryDrawer", "NestedScrollConnection onPreFling called with velocity: $available")
                return Velocity.Zero
            }
        }
    }

    // Permission Dialog
    if (showPermissionDialog) {
        Log.i("ChatHistoryDrawer", "Showing permission dialog due to lack of storage access")
        AlertDialog(
            onDismissRequest = {
                Log.d("ChatHistoryDrawer", "Permission dialog dismissed")
                showPermissionDialog = false
            },
            title = { Text("Storage Permission Required") },
            text = { Text("Please allow 'All files access' in Settings to save PDFs to Downloads.") },
            confirmButton = {
                TextButton(onClick = {
                    Log.i("ChatHistoryDrawer", "User clicked 'Go to Settings' for permission")
                    showPermissionDialog = false
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    intent.data = Uri.parse("package:${context.packageName}")
                    context.startActivity(intent)
                }) {
                    Text("Go to Settings")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    Log.d("ChatHistoryDrawer", "User cancelled permission dialog")
                    showPermissionDialog = false
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Export Progress Dialog
    if (showExportDialog) {
        Log.d("ChatHistoryDrawer", "Showing export progress dialog with progress: ${(progress * 100).toInt()}%")
        AlertDialog(
            onDismissRequest = { /* Non-dismissible while exporting */ },
            title = { Text("Exporting PDF") },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Processing... ${(progress * 100).toInt()}%")
                    LinearProgressIndicator(
                        progress = progress,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = {
                    Log.w("ChatHistoryDrawer", "User cancelled PDF export")
                    exportJob?.cancel()
                    showExportDialog = false
                    progressState.value = 0f
                    exportJob = null
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Success Dialog
    if (showSuccessDialog) {
        Log.i("ChatHistoryDrawer", "Showing success dialog after PDF export")
        AlertDialog(
            onDismissRequest = {
                Log.d("ChatHistoryDrawer", "Success dialog dismissed")
                showSuccessDialog = false
            },
            title = { Text("Success") },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Success",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Text("PDF exported successfully!")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    Log.d("ChatHistoryDrawer", "User acknowledged PDF export success")
                    showSuccessDialog = false
                }) {
                    Text("OK")
                }
            },
            dismissButton = {}
        )
    }

    // Error Dialog
    if (showErrorDialog) {
        Log.w("ChatHistoryDrawer", "Showing error dialog with message: $errorMessage")
        AlertDialog(
            onDismissRequest = {
                Log.d("ChatHistoryDrawer", "Error dialog dismissed")
                showErrorDialog = false
            },
            title = { Text("Error") },
            text = { Text(errorMessage) },
            confirmButton = {
                TextButton(onClick = {
                    Log.d("ChatHistoryDrawer", "User acknowledged export error")
                    showErrorDialog = false
                }) {
                    Text("OK")
                }
            },
            dismissButton = {}
        )
    }

    // Filter chats based on search query
    val filteredHistories = histories.filter {
        it.title.lowercase().contains(searchQuery.lowercase())
    }
    Log.d("ChatHistoryDrawer", "Filtered histories: ${filteredHistories.size} chats after search query '$searchQuery'")

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(280.dp)
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        // Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = {
                Log.d("ChatHistoryDrawer", "Search query changed to: $it")
                searchQuery = it
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            placeholder = { Text("Search", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)) },
            leadingIcon = {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = "Search Icon",
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Text),
            colors = TextFieldDefaults.outlinedTextFieldColors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                focusedTextColor = MaterialTheme.colorScheme.onBackground,
                unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            ),
            shape = RoundedCornerShape(24.dp)
        )

        // Chats heading with 3-dot or tick
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Chats",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Box {
                if (selectionMode) {
                    IconButton(onClick = {
                        Log.d("ChatHistoryDrawer", "Confirming action: $selectedAction for chats: $selectedChats")
                        when (selectedAction) {
                            "delete" -> {
                                if (selectedChats.isNotEmpty()) {
                                    Log.i("ChatHistoryDrawer", "Deleting selected chats: $selectedChats")
                                    selectedChats.forEach { chatId -> onDeleteChat(chatId) }
                                } else {
                                    Log.w("ChatHistoryDrawer", "No chats selected for deletion")
                                }
                                selectionMode = false
                                selectedChats = emptySet()
                                selectedAction = null
                                Log.d("ChatHistoryDrawer", "Reset selection mode and cleared selected chats")
                            }
                            "download" -> {
                                if (selectedChats.isNotEmpty()) {
                                    Log.i("ChatHistoryDrawer", "Initiating PDF download for selected chats: $selectedChats")
                                    // Check for storage permissions before starting export
                                    val hasStoragePermission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                                        val hasPermission = Environment.isExternalStorageManager()
                                        Log.d("ChatHistoryDrawer", "Checking storage permission (API >= 30): $hasPermission")
                                        hasPermission
                                    } else {
                                        val hasPermission = ContextCompat.checkSelfPermission(
                                            context,
                                            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                                        ) == PackageManager.PERMISSION_GRANTED
                                        Log.d("ChatHistoryDrawer", "Checking storage permission (API < 30): $hasPermission")
                                        hasPermission
                                    }

                                    if (!hasStoragePermission) {
                                        Log.w("ChatHistoryDrawer", "Storage permission not granted, showing permission dialog")
                                        showPermissionDialog = true
                                        selectionMode = false
                                        selectedChats = emptySet()
                                        selectedAction = null
                                        return@IconButton
                                    }

                                    // Check if an export job is already running
                                    if (exportJob?.isActive == true) {
                                        Log.w("ChatHistoryDrawer", "An export job is already running, cancelling it")
                                        exportJob?.cancel()
                                    }

                                    showExportDialog = true
                                    progressState.value = 0f
                                    exportJob = coroutineScope.launch {
                                        try {
                                            Log.d("ChatHistoryDrawer", "Starting export job for chats: $selectedChats")
                                            if (selectedChats.isEmpty()) {
                                                Log.w("ChatHistoryDrawer", "No chats to export, aborting")
                                                showExportDialog = false
                                                showErrorDialog = true
                                                errorMessage = "No chats selected to export."
                                                progressState.value = 0f
                                                return@launch
                                            }

                                            val totalMessages = selectedChats.sumOf { chatId ->
                                                getChatMessages(chatId).size
                                            }.toFloat()
                                            Log.d("ChatHistoryDrawer", "Total messages to process across all chats: $totalMessages")
                                            var processedMessages = 0

                                            selectedChats.forEachIndexed { index, chatId ->
                                                Log.d("ChatHistoryDrawer", "Processing chat $chatId (${index + 1}/${selectedChats.size})")
                                                val messages = getChatMessages(chatId)
                                                Log.d("ChatHistoryDrawer", "Retrieved ${messages.size} messages for chat $chatId")
                                                generateChatPdf(
                                                    context = context,
                                                    chatId = chatId,
                                                    messages = messages,
                                                    progressState = progressState,
                                                    totalMessages = totalMessages,
                                                    processedMessages = processedMessages,
                                                    onProgressIncrement = { increment ->
                                                        processedMessages += increment
                                                        val progress = processedMessages / totalMessages
                                                        Log.v("ChatHistoryDrawer", "Updated progress: ${(progress * 100).toInt()}% ($processedMessages/$totalMessages messages)")
                                                        progressState.value = progress.coerceIn(0f, 1f)
                                                    },
                                                    onPermissionDenied = {
                                                        Log.w("ChatHistoryDrawer", "Permission denied while saving PDF for chat $chatId")
                                                        showExportDialog = false
                                                        showErrorDialog = true
                                                        errorMessage = "Permission denied: Unable to save PDF."
                                                        progressState.value = 0f
                                                    }
                                                )
                                            }
                                            Log.i("ChatHistoryDrawer", "PDF export completed successfully for all chats")
                                            showExportDialog = false
                                            showSuccessDialog = true
                                            progressState.value = 0f
                                        } catch (e: CancellationException) {
                                            Log.w("ChatHistoryDrawer", "Export job cancelled: ${e.message}")
                                            showExportDialog = false
                                            progressState.value = 0f
                                        } catch (e: Exception) {
                                            Log.e("ChatHistoryDrawer", "Error during PDF export: ${e.message}", e)
                                            showExportDialog = false
                                            showErrorDialog = true
                                            errorMessage = "Failed to export PDF: ${e.message}"
                                            progressState.value = 0f
                                        } finally {
                                            exportJob = null
                                            selectionMode = false
                                            selectedChats = emptySet()
                                            selectedAction = null
                                            Log.d("ChatHistoryDrawer", "Export job finished or cancelled, reset selection mode and cleared selected chats")
                                        }
                                    }
                                } else {
                                    Log.w("ChatHistoryDrawer", "No chats selected for PDF download")
                                    selectionMode = false
                                    selectedChats = emptySet()
                                    selectedAction = null
                                    Log.d("ChatHistoryDrawer", "Reset selection mode and cleared selected chats")
                                }
                            }
                        }
                    }) {
                        Icon(Icons.Filled.Check, contentDescription = "Confirm Action", tint = MaterialTheme.colorScheme.primary)
                    }
                } else {
                    IconButton(onClick = {
                        Log.d("ChatHistoryDrawer", "Menu icon clicked, showing dropdown menu")
                        showMenu = true
                    }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
                    }
                }
                if (showMenu) {
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = {
                            Log.d("ChatHistoryDrawer", "Dropdown menu dismissed")
                            showMenu = false
                        },
                        offset = DpOffset(x = (-16).dp, y = 4.dp)
                    ) {
                        DropdownMenuItem(
                            text = { Text("Download Chats") },
                            onClick = {
                                Log.i("ChatHistoryDrawer", "User selected 'Download Chats' from menu")
                                selectedAction = "download"
                                selectionMode = true
                                showMenu = false
                            },
                            leadingIcon = {
                                Icon(Icons.Filled.Download, contentDescription = "Download")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete Chats") },
                            onClick = {
                                Log.i("ChatHistoryDrawer", "User selected 'Delete Chats' from menu")
                                selectedAction = "delete"
                                selectionMode = true
                                showMenu = false
                            },
                            leadingIcon = {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete")
                            }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Chat list with checkboxes in selection mode
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (filteredHistories.isEmpty()) {
                item {
                    Log.w("ChatHistoryDrawer", "No chat histories to display after filtering")
                    Text(
                        text = if (searchQuery.isEmpty()) "No chat history yet" else "No chats found",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                items(filteredHistories) { history ->
                    val isChecked = selectedChats.contains(history.id)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isChecked) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                                else ComposeColor.Transparent,
                                RoundedCornerShape(12.dp)
                            )
                            .clickable {
                                if (selectionMode) {
                                    selectedChats = if (isChecked) {
                                        Log.d("ChatHistoryDrawer", "Deselecting chat: ${history.id}")
                                        selectedChats - history.id
                                    } else {
                                        Log.d("ChatHistoryDrawer", "Selecting chat: ${history.id}")
                                        selectedChats + history.id
                                    }
                                } else {
                                    Log.i("ChatHistoryDrawer", "Navigating to chat: ${history.id}")
                                    onHistorySelected(history.id)
                                }
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (selectionMode) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { checked ->
                                    selectedChats = if (checked) {
                                        Log.d("ChatHistoryDrawer", "Checkbox selected for chat: ${history.id}")
                                        selectedChats + history.id
                                    } else {
                                        Log.d("ChatHistoryDrawer", "Checkbox deselected for chat: ${history.id}")
                                        selectedChats - history.id
                                    }
                                }
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = history.title,
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault()).format(history.date),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // Profile Button with Consistent Border and Color Changes
        Row(
            modifier = Modifier
                .fillMaxWidth()
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
                .clickable {
                    Log.i("ChatHistoryDrawer", "Navigating to settings screen")
                    navController.navigate("settings")
                }
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        color = MaterialTheme.colorScheme.primary,
                        shape = CircleShape
                    )
            ) {
                val userInitial = viewModel.userName.value.firstOrNull()?.uppercase() ?: "U"
                Log.d("ChatHistoryDrawer", "Displaying user initial: $userInitial")
                Text(
                    text = userInitial,
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 20.sp
                )
            }
            Text(
                text = viewModel.userName.value,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

fun parseMessageSegmentspdfDisplay(message: String): List<MessageSegment> {
    Log.d("ChatHistoryDrawer", "Parsing message segments for message: ${message.take(50)}...")
    val segments = mutableListOf<MessageSegment>()
    val codeBlockRegex = Regex("```(\\w+)?\\n([\\s\\S]*?)```", RegexOption.MULTILINE)
    var lastIndex = 0

    codeBlockRegex.findAll(message).forEach { match ->
        if (match.range.first > lastIndex) {
            val textContent = message.substring(lastIndex, match.range.first).trim()
            if (textContent.isNotEmpty()) {
                Log.v("ChatHistoryDrawer", "Adding text segment: ${textContent.take(50)}...")
                segments.add(MessageSegment.TextSegment(textContent))
            }
        }

        val language = match.groupValues[1].takeIf { it.isNotEmpty() }
        val code = match.groupValues[2]
        Log.v("ChatHistoryDrawer", "Adding code segment (language: $language): ${code.take(50)}...")
        segments.add(MessageSegment.CodeSegment(language, code))

        lastIndex = match.range.last + 1
    }

    if (lastIndex < message.length) {
        val textContent = message.substring(lastIndex).trim()
        if (textContent.isNotEmpty()) {
            Log.v("ChatHistoryDrawer", "Adding final text segment: ${textContent.take(50)}...")
            segments.add(MessageSegment.TextSegment(textContent))
        }
    }

    if (segments.isEmpty()) {
        Log.d("ChatHistoryDrawer", "No segments found, adding entire message as text segment")
        segments.add(MessageSegment.TextSegment(message))
    }

    Log.d("ChatHistoryDrawer", "Parsed ${segments.size} segments for message")
    return segments
}

suspend fun generateChatPdf(
    context: Context,
    chatId: String,
    messages: List<String>,
    progressState: MutableStateFlow<Float>,
    totalMessages: Float,
    processedMessages: Int,
    onProgressIncrement: (Int) -> Unit,
    onPermissionDenied: (Any?) -> Unit
) {
    withContext(Dispatchers.IO) {
        try {
            Log.i("ChatHistoryDrawer", "Starting PDF generation for chat $chatId with ${messages.size} messages")
            val document = PdfDocument()
            var pageNumber = 1
            val pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNumber).create()
            var page = document.startPage(pageInfo)
            var canvas = page.canvas
            val paint = Paint()
            val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 10f
                typeface = Typeface.DEFAULT
                color = Color.BLACK
            }

            // Layout constants
            val borderMargin = 40f
            val contentWidth = pageInfo.pageWidth - 2 * borderMargin
            val contentHeight = pageInfo.pageHeight - 2 * borderMargin
            val maxBubbleWidth = 350f
            val bubblePadding = 10f
            val bubbleRadius = 15f
            val timestampHeight = 25f
            val messageSpacing = 20f

            // Header settings
            val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            var yPosition = borderMargin + 120f

            fun drawPageHeader(canvas: Canvas) {
                Log.v("ChatHistoryDrawer", "Drawing page header for page $pageNumber")
                if (pageNumber == 1) {
                    paint.color = Color.parseColor("#EADDFF")
                    paint.style = Paint.Style.FILL
                    val dateText = dateFormat.format(Date())
                    paint.textSize = 12f
                    paint.typeface = Typeface.DEFAULT_BOLD
                    val textWidth = paint.measureText(dateText)
                    val rectWidth = textWidth + 20f
                    val rectHeight = 35f
                    val dateRect = RectF(borderMargin + 10f, borderMargin + 10f, borderMargin + 10f + rectWidth, borderMargin + 10f + rectHeight)
                    canvas.drawRoundRect(dateRect, 10f, 10f, paint)

                    paint.color = Color.parseColor("#6750A4")
                    canvas.drawText(dateText, dateRect.left + 10f, dateRect.top + 22f, paint)
                    Log.d("ChatHistoryDrawer", "Drew date header: $dateText")
                }

                try {
                    val svg = SVG.getFromResource(context.resources, R.raw.header_icon)
                    val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
                    val svgCanvas = Canvas(bitmap)
                    svg.setDocumentWidth(100f)
                    svg.setDocumentHeight(100f)
                    svg.renderToCanvas(svgCanvas)
                    canvas.drawBitmap(bitmap, borderMargin + contentWidth - 110f, borderMargin + 10f, paint)
                    bitmap.recycle()
                    Log.d("ChatHistoryDrawer", "Successfully drew SVG header icon")
                } catch (e: Exception) {
                    Log.e("ChatHistoryDrawer", "Error loading SVG header icon: ${e.message}", e)
                }
            }

            fun drawPageNumber(canvas: Canvas, pageNum: Int) {
                Log.v("ChatHistoryDrawer", "Drawing page number: $pageNum")
                paint.color = Color.parseColor("#EADDFF")
                paint.style = Paint.Style.FILL
                val boxSize = 20f
                val boxRect = RectF(
                    (pageInfo.pageWidth / 2) - (boxSize / 2),
                    pageInfo.pageHeight - boxSize - 25f,
                    (pageInfo.pageWidth / 2) + (boxSize / 2),
                    pageInfo.pageHeight - 25f
                )
                canvas.drawOval(boxRect, paint)

                paint.color = Color.parseColor("#6750A4")
                paint.textSize = 10f
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText(
                    "$pageNum",
                    boxRect.centerX(),
                    boxRect.centerY() + 3f,
                    paint
                )
                paint.textAlign = Paint.Align.LEFT
            }

            fun createNewPage(): Canvas {
                Log.d("ChatHistoryDrawer", "Creating new page (page number: $pageNumber)")
                drawPageNumber(canvas, pageNumber)
                document.finishPage(page)
                pageNumber++
                page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
                canvas = page.canvas
                drawPageHeader(canvas)
                yPosition = borderMargin + 80f
                return canvas
            }

            fun calculateTextHeight(text: String, paint: TextPaint, maxWidth: Float): Float {
                val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, maxWidth.toInt())
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, 1.2f)
                    .setIncludePad(false)
                    .build()
                val height = layout.height.toFloat() + 15f
                Log.v("ChatHistoryDrawer", "Calculated text height: $height for text: ${text.take(50)}...")
                return height
            }

            fun calculateCodeHeight(code: String, language: String?): Float {
                val codeTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = 9f
                    typeface = Typeface.MONOSPACE
                    color = Color.BLACK
                }
                val languageHeight = if (language != null) 25f else 0f
                val separatorHeight = if (language != null) 8f else 0f
                val maxCodeWidth = maxBubbleWidth - 2 * bubblePadding - 20f

                val codeLines = code.lines()
                var totalWrappedLines = 0

                codeLines.forEach { line ->
                    if (line.isEmpty()) {
                        totalWrappedLines += 1
                    } else if (codeTextPaint.measureText(line) <= maxCodeWidth) {
                        totalWrappedLines += 1
                    } else {
                        var remainingLine = line
                        while (remainingLine.isNotEmpty()) {
                            var breakPoint = remainingLine.length
                            for (i in remainingLine.length - 1 downTo 1) {
                                val substring = remainingLine.substring(0, i)
                                if (codeTextPaint.measureText(substring) <= maxCodeWidth) {
                                    breakPoint = i
                                    break
                                }
                            }
                            if (breakPoint == remainingLine.length && remainingLine.length > 1) {
                                breakPoint = maxOf(1, (maxCodeWidth / codeTextPaint.measureText("M")).toInt())
                            }
                            totalWrappedLines += 1
                            remainingLine = remainingLine.substring(breakPoint)
                        }
                    }
                }

                val lineHeight = codeTextPaint.getFontSpacing() * 1.2f
                val totalCodeHeight = totalWrappedLines * lineHeight
                val padding = 16f
                val totalHeight = languageHeight + separatorHeight + totalCodeHeight + padding
                Log.v("ChatHistoryDrawer", "Calculated code height: $totalHeight for code (language: $language): ${code.take(50)}...")
                return totalHeight
            }

            fun drawTextContent(text: String, bubbleRect: RectF, startY: Float): Float {
                Log.v("ChatHistoryDrawer", "Drawing text content at Y: $startY")
                val layout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, (maxBubbleWidth - 2 * bubblePadding).toInt())
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, 1.2f)
                    .setIncludePad(false)
                    .build()

                canvas.save()
                canvas.translate(bubbleRect.left + bubblePadding, startY)
                layout.draw(canvas)
                canvas.restore()

                val newY = startY + layout.height + 5f
                Log.v("ChatHistoryDrawer", "Text content drawn, new Y position: $newY")
                return newY
            }

            fun drawCodeContent(language: String?, code: String, bubbleRect: RectF, startY: Float): Float {
                Log.v("ChatHistoryDrawer", "Drawing code content (language: $language) at Y: $startY")
                val codeTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = 9f
                    typeface = Typeface.MONOSPACE
                    color = Color.BLACK
                }
                val languagePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = 9f
                    typeface = Typeface.DEFAULT_BOLD
                    color = Color.parseColor("#6750A4")
                }

                val codeBackgroundColor = Color.parseColor("#F8F8FA")
                val codeRect = RectF(
                    bubbleRect.left + bubblePadding,
                    startY,
                    bubbleRect.right - bubblePadding,
                    0f
                )

                var currentY = startY + 8f
                val lineHeight = codeTextPaint.getFontSpacing() * 1.2f
                val maxCodeWidth = codeRect.width() - 20f

                if (language != null) {
                    canvas.drawText(language, codeRect.left + 10f, currentY + lineHeight - 3f, languagePaint)
                    currentY += 20f

                    paint.color = Color.parseColor("#E0E0E0")
                    paint.strokeWidth = 1f
                    canvas.drawLine(codeRect.left + 10f, currentY, codeRect.right - 10f, currentY, paint)
                    currentY += 8f
                    Log.v("ChatHistoryDrawer", "Drew language header and separator for code block")
                }

                val codeLines = code.lines()
                val wrappedLines = mutableListOf<String>()

                codeLines.forEach { line ->
                    if (line.isEmpty()) {
                        wrappedLines.add("")
                    } else if (codeTextPaint.measureText(line) <= maxCodeWidth) {
                        wrappedLines.add(line)
                    } else {
                        var remainingLine = line
                        while (remainingLine.isNotEmpty()) {
                            var breakPoint = remainingLine.length
                            for (i in remainingLine.length - 1 downTo 1) {
                                val substring = remainingLine.substring(0, i)
                                if (codeTextPaint.measureText(substring) <= maxCodeWidth) {
                                    breakPoint = i
                                    break
                                }
                            }
                            if (breakPoint == remainingLine.length && remainingLine.length > 1) {
                                breakPoint = maxOf(1, (maxCodeWidth / codeTextPaint.measureText("M")).toInt())
                            }
                            wrappedLines.add(remainingLine.substring(0, breakPoint))
                            remainingLine = remainingLine.substring(breakPoint)
                        }
                    }
                }

                val codeHeight = wrappedLines.size * lineHeight + 12f
                codeRect.bottom = currentY + codeHeight

                paint.color = codeBackgroundColor
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(codeRect, 8f, 8f, paint)

                currentY += 6f
                wrappedLines.forEach { line ->
                    canvas.drawText(line, codeRect.left + 10f, currentY + lineHeight - 3f, codeTextPaint)
                    currentY += lineHeight
                }

                Log.v("ChatHistoryDrawer", "Code content drawn, new Y position: ${codeRect.bottom + 5f}")
                return codeRect.bottom + 5f
            }

            fun splitTextSegment(
                textSegment: MessageSegment.TextSegment,
                availableHeight: Float,
                includeTimestamp: Boolean
            ): Pair<List<MessageSegment.TextSegment>, String> {
                val words = textSegment.content.split(" ")
                val fittingSegments = mutableListOf<MessageSegment.TextSegment>()
                var currentText = ""
                var usedHeight = if (includeTimestamp) timestampHeight + 8f else 8f
                usedHeight += 2 * bubblePadding + 10f

                for (word in words) {
                    val testText = if (currentText.isEmpty()) word else "$currentText $word"
                    val textHeight = calculateTextHeight(testText, textPaint, maxBubbleWidth - 2 * bubblePadding)

                    if (usedHeight + textHeight + 10f <= availableHeight) {
                        currentText = testText
                    } else {
                        if (currentText.isNotEmpty()) {
                            fittingSegments.add(MessageSegment.TextSegment(currentText))
                        }
                        val remainingWords = words.subList(words.indexOf(word), words.size)
                        return Pair(fittingSegments, remainingWords.joinToString(" "))
                    }
                }

                if (currentText.isNotEmpty()) {
                    fittingSegments.add(MessageSegment.TextSegment(currentText))
                }
                return Pair(fittingSegments, "")
            }

            fun splitCodeSegment(
                codeSegment: MessageSegment.CodeSegment,
                availableHeight: Float,
                includeTimestamp: Boolean
            ): Pair<List<MessageSegment.CodeSegment>, String> {
                val codeTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = 9f
                    typeface = Typeface.MONOSPACE
                    color = Color.BLACK
                }
                val languageHeight = if (codeSegment.language != null) 25f else 0f
                val separatorHeight = if (codeSegment.language != null) 8f else 0f
                val lineHeight = codeTextPaint.getFontSpacing() * 1.2f
                val maxCodeWidth = maxBubbleWidth - 2 * bubblePadding - 20f
                var usedHeight = if (includeTimestamp) timestampHeight + 8f else 8f
                usedHeight += languageHeight + separatorHeight + 2 * bubblePadding + 10f

                val codeLines = codeSegment.code.lines()
                val wrappedLines = mutableListOf<String>()
                codeLines.forEach { line ->
                    if (line.isEmpty()) {
                        wrappedLines.add("")
                    } else if (codeTextPaint.measureText(line) <= maxCodeWidth) {
                        wrappedLines.add(line)
                    } else {
                        var remainingLine = line
                        while (remainingLine.isNotEmpty()) {
                            var breakPoint = remainingLine.length
                            for (i in remainingLine.length - 1 downTo 1) {
                                val substring = remainingLine.substring(0, i)
                                if (codeTextPaint.measureText(substring) <= maxCodeWidth) {
                                    breakPoint = i
                                    break
                                }
                            }
                            if (breakPoint == remainingLine.length && remainingLine.length > 1) {
                                breakPoint = maxOf(1, (maxCodeWidth / codeTextPaint.measureText("M")).toInt())
                            }
                            wrappedLines.add(remainingLine.substring(0, breakPoint))
                            remainingLine = remainingLine.substring(breakPoint)
                        }
                    }
                }

                val fittingLines = mutableListOf<String>()
                var lineCount = 0
                for (line in wrappedLines) {
                    val lineHeightToAdd = if (line.isEmpty()) lineHeight else lineHeight
                    if (usedHeight + lineHeightToAdd <= availableHeight) {
                        fittingLines.add(line)
                        usedHeight += lineHeightToAdd
                        lineCount++
                    } else {
                        break
                    }
                }

                val remainingLines = wrappedLines.subList(lineCount, wrappedLines.size)
                return Pair(
                    listOf(MessageSegment.CodeSegment(codeSegment.language, fittingLines.joinToString("\n"))),
                    remainingLines.joinToString("\n")
                )
            }

            fun drawSingleBubble(
                segments: List<MessageSegment>,
                isUserMessage: Boolean,
                timestamp: String,
                messageIndex: Int
            ) {
                if (segments.isEmpty()) return

                var totalContentHeight = if (timestamp.isNotEmpty()) timestampHeight + 8f else 8f

                segments.forEach { segment ->
                    when (segment) {
                        is MessageSegment.TextSegment -> {
                            val textHeight = calculateTextHeight(segment.content, textPaint, maxBubbleWidth - 2 * bubblePadding)
                            totalContentHeight += textHeight + 10f
                        }
                        is MessageSegment.CodeSegment -> {
                            val codeHeight = calculateCodeHeight(segment.code, segment.language)
                            totalContentHeight += codeHeight + 10f
                        }
                    }
                }

                val totalBubbleHeight = totalContentHeight + 1 * bubblePadding
                Log.v("ChatHistoryDrawer", "Drawing single bubble with height: $totalBubbleHeight")

                val bubbleLeft = if (isUserMessage) borderMargin + contentWidth - maxBubbleWidth - 20f else borderMargin + 20f
                val bubbleRight = bubbleLeft + maxBubbleWidth
                val bubbleTop = yPosition
                val bubbleBottom = yPosition + totalBubbleHeight
                val bubbleRect = RectF(bubbleLeft, bubbleTop, bubbleRight, bubbleBottom)

                paint.color = if (isUserMessage) Color.parseColor("#E8F5E8") else Color.parseColor("#F0F0FF")
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(bubbleRect, bubbleRadius, bubbleRadius, paint)

                if (timestamp.isNotEmpty()) {
                    val tailPath = Path()
                    if (isUserMessage) {
                        tailPath.moveTo(bubbleRect.right - bubbleRadius, bubbleRect.bottom - 20f)
                        tailPath.lineTo(bubbleRect.right + 15f, bubbleRect.bottom - 5f)
                        tailPath.lineTo(bubbleRect.right - 5f, bubbleRect.bottom - 5f)
                    } else {
                        tailPath.moveTo(bubbleRect.left + bubbleRadius, bubbleRect.bottom - 20f)
                        tailPath.lineTo(bubbleRect.left - 15f, bubbleRect.bottom - 5f)
                        tailPath.lineTo(bubbleRect.left + 5f, bubbleRect.bottom - 5f)
                    }
                    tailPath.close()
                    canvas.drawPath(tailPath, paint)
                }

                var currentY = bubbleRect.top + bubblePadding
                if (timestamp.isNotEmpty()) {
                    val timestampPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                        textSize = 8f
                        typeface = Typeface.DEFAULT
                        color = Color.GRAY
                    }
                    canvas.drawText(timestamp, bubbleRect.left + bubblePadding, currentY + 12f, timestampPaint)
                    currentY += timestampHeight + 3f
                } else {
                    currentY += 8f
                }

                segments.forEach { segment ->
                    when (segment) {
                        is MessageSegment.TextSegment -> {
                            currentY = drawTextContent(segment.content, bubbleRect, currentY)
                            currentY += 10f
                        }
                        is MessageSegment.CodeSegment -> {
                            currentY = drawCodeContent(segment.language, segment.code, bubbleRect, currentY)
                            currentY += 10f
                        }
                    }
                }

                yPosition = bubbleBottom + messageSpacing
                Log.v("ChatHistoryDrawer", "Single bubble drawn, updated Y position: $yPosition")
            }

            fun drawMessageBubble(
                segments: List<MessageSegment>,
                isUserMessage: Boolean,
                timestamp: String,
                messageIndex: Int
            ) {
                Log.d("ChatHistoryDrawer", "Drawing message bubble for message $messageIndex (isUser: $isUserMessage, timestamp: $timestamp)")

                val minBubbleHeight = timestampHeight + 2 * bubblePadding + 50f
                val availableHeight = pageInfo.pageHeight - borderMargin - 50f - yPosition

                if (availableHeight < minBubbleHeight) {
                    Log.d("ChatHistoryDrawer", "Not enough space for minimum bubble, creating new page")
                    canvas = createNewPage()
                }

                var currentSegmentIndex = 0
                var isFirstBubbleForMessage = true
                val allSegments = segments.toMutableList()

                while (currentSegmentIndex < allSegments.size) {
                    val remainingSegments = allSegments.subList(currentSegmentIndex, allSegments.size)
                    val currentAvailableHeight = pageInfo.pageHeight - borderMargin - 50f - yPosition

                    var segmentsToInclude = mutableListOf<MessageSegment>()
                    var totalContentHeight = if (isFirstBubbleForMessage) timestampHeight + 8f else 8f
                    var nextSegmentIndex = currentSegmentIndex

                    for (i in remainingSegments.indices) {
                        val segment = remainingSegments[i]
                        val segmentHeight = when (segment) {
                            is MessageSegment.TextSegment -> calculateTextHeight(segment.content, textPaint, maxBubbleWidth - 2 * bubblePadding) + 10f
                            is MessageSegment.CodeSegment -> calculateCodeHeight(segment.code, segment.language) + 10f
                        }

                        val bubbleHeightWithThisSegment = totalContentHeight + segmentHeight + 2 * bubblePadding + 10f

                        if (bubbleHeightWithThisSegment <= currentAvailableHeight || segmentsToInclude.isEmpty()) {
                            segmentsToInclude.add(segment)
                            totalContentHeight += segmentHeight
                            nextSegmentIndex = currentSegmentIndex + i + 1
                        } else {
                            break
                        }
                    }

                    if (segmentsToInclude.isEmpty() && remainingSegments.isNotEmpty()) {
                        val segment = remainingSegments[0]
                        when (segment) {
                            is MessageSegment.TextSegment -> {
                                val splitResult = splitTextSegment(segment, currentAvailableHeight, isFirstBubbleForMessage)
                                segmentsToInclude.addAll(splitResult.first)
                                val remainingText = splitResult.second
                                if (remainingText.isNotEmpty()) {
                                    allSegments[currentSegmentIndex] = MessageSegment.TextSegment(remainingText)
                                    nextSegmentIndex = currentSegmentIndex
                                } else {
                                    nextSegmentIndex = currentSegmentIndex + 1
                                }
                            }
                            is MessageSegment.CodeSegment -> {
                                val splitResult = splitCodeSegment(segment, currentAvailableHeight, isFirstBubbleForMessage)
                                segmentsToInclude.addAll(splitResult.first)
                                val remainingCode = splitResult.second
                                if (remainingCode.isNotEmpty()) {
                                    allSegments[currentSegmentIndex] = MessageSegment.CodeSegment(segment.language, remainingCode)
                                    nextSegmentIndex = currentSegmentIndex
                                } else {
                                    nextSegmentIndex = currentSegmentIndex + 1
                                }
                            }
                        }
                    }

                    drawSingleBubble(
                        segmentsToInclude,
                        isUserMessage,
                        if (isFirstBubbleForMessage) timestamp else "",
                        messageIndex
                    )

                    isFirstBubbleForMessage = false
                    currentSegmentIndex = nextSegmentIndex

                    if (currentSegmentIndex < allSegments.size) {
                        canvas = createNewPage()
                    }
                }
            }

            drawPageHeader(canvas)

            messages.forEachIndexed { index, message ->
                if (!isActive) {
                    Log.w("ChatHistoryDrawer", "PDF generation cancelled for chat $chatId at message $index")
                    return@withContext
                }

                Log.d("ChatHistoryDrawer", "Processing message ${index + 1}/${messages.size} for chat $chatId: ${message.take(50)}...")
                val timestamp = timeFormat.format(Date(System.currentTimeMillis() + index * 60000))
                val isUserMessage = index % 2 == 0
                val segments = parseMessageSegmentspdfDisplay(message.trim())

                drawMessageBubble(segments, isUserMessage, timestamp, index)
                onProgressIncrement(1)
            }

            drawPageNumber(canvas, pageNumber)
            document.finishPage(page)

            val fileName = "Chat_$chatId.pdf"
            Log.d("ChatHistoryDrawer", "Preparing to save PDF as: $fileName")
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }

            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val fileUri = resolver.insert(collection, contentValues)

            fileUri?.let { uri ->
                try {
                    resolver.openOutputStream(uri)?.use { outputStream ->
                        document.writeTo(outputStream)
                        outputStream.flush()
                        Log.i("ChatHistoryDrawer", "PDF successfully saved to Downloads: $fileName (URI: $uri)")
                    }
                    contentValues.clear()
                    contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                    Log.d("ChatHistoryDrawer", "Updated MediaStore entry for PDF: $fileName")
                } catch (e: Exception) {
                    Log.e("ChatHistoryDrawer", "Error saving PDF for chat $chatId: ${e.message}", e)
                    throw e
                } finally {
                    document.close()
                    Log.d("ChatHistoryDrawer", "PDF document closed for chat $chatId")
                }
            } ?: run {
                Log.e("ChatHistoryDrawer", "Failed to create file URI for chat $chatId")
                onPermissionDenied(null)
                document.close()
                Log.d("ChatHistoryDrawer", "PDF document closed after URI failure for chat $chatId")
            }
        } catch (e: Exception) {
            Log.e("ChatHistoryDrawer", "Exception in generateChatPdf for chat $chatId: ${e.message}", e)
            throw e
        }
    }
}
