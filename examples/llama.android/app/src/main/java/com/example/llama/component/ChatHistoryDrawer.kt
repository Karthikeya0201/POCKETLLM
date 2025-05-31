package com.example.llama.component

import android.content.ContentValues
import android.content.Intent
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.caverock.androidsvg.SVG
import com.example.llama.MainViewModel
import com.example.llama.R
import com.example.llama.model.ChatHistory
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
    var selectedAction by remember { mutableStateOf<String?>(null) } // Track "download" or "delete"
    var showPermissionDialog by remember { mutableStateOf(false) } // Moved permission dialog state here
    val context = LocalContext.current
    val density = LocalDensity.current

    Log.d("ChatHistoryDrawer", "Rendering drawer with ${histories.size} chat histories")

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override suspend fun onPreFling(available: Velocity): Velocity {
                return Velocity.Zero
            }
        }
    }

    // Permission Dialog
    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = { Text("Storage Permission Required") },
            text = { Text("Please allow 'All files access' in Settings to save PDFs to Downloads.") },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionDialog = false
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    intent.data = Uri.parse("package:${context.packageName}")
                    context.startActivity(intent)
                }) {
                    Text("Go to Settings")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Filter chats based on search query
    val filteredHistories = histories.filter {
        it.title.lowercase().contains(searchQuery.lowercase())
    }

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
            onValueChange = { searchQuery = it },
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
                        // Perform actions based on selected chats
                        when (selectedAction) {
                            "delete" -> {
                                if (selectedChats.isNotEmpty()) {
                                    Log.d("ChatHistoryDrawer", "Deleting selected chats: $selectedChats")
                                    selectedChats.forEach { chatId -> onDeleteChat(chatId) }
                                }
                            }
                            "download" -> {
                                if (selectedChats.isNotEmpty()) {
                                    Log.d("ChatHistoryDrawer", "Downloading selected chats: $selectedChats")
                                    selectedChats.forEach { chatId ->
                                        val messages = getChatMessages(chatId)
                                        generateChatPdf(context, chatId, messages) { showPermissionDialog = true }
                                    }
                                }
                            }
                        }
                        // Exit selection mode
                        selectionMode = false
                        selectedChats = emptySet()
                        selectedAction = null
                    }) {
                        Icon(Icons.Filled.Check, contentDescription = "Confirm Action", tint = MaterialTheme.colorScheme.primary)
                    }
                } else {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
                    }
                }
                // Dropdown Menu positioned below the 3-dot icon
                if (showMenu) {
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        offset = DpOffset(x = (-16).dp, y = 4.dp) // Align below the icon
                    ) {
                        DropdownMenuItem(
                            text = { Text("Download Chats") },
                            onClick = {
                                Log.d("ChatHistoryDrawer", "Download chats selected")
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
                                Log.d("ChatHistoryDrawer", "Delete chats selected")
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
                    Log.d("ChatHistoryDrawer", "No chat histories to display")
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
                                    selectedChats = if (isChecked)
                                        selectedChats - history.id
                                    else
                                        selectedChats + history.id
                                } else {
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
                                    selectedChats = if (checked)
                                        selectedChats + history.id
                                    else
                                        selectedChats - history.id
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
                .clickable { navController.navigate("settings") }
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
                Text(
                    text = viewModel.userName.value.firstOrNull()?.uppercase() ?: "U",
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

fun generateChatPdf(context: android.content.Context, chatId: String, messages: List<String>, onPermissionDenied: () -> Unit) {
    val document = PdfDocument()
    var pageNumber = 1
    val pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNumber).create()
    var page = document.startPage(pageInfo)
    var canvas = page.canvas
    val paint = Paint()
    val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 8f
        typeface = Typeface.SERIF
        color = Color.BLACK
    }
    val timestampHeight = 20f
    val bubblePadding = 15f
    val bubbleRadius = 15f
    val maxBubbleWidth = 350f
    val lineHeight = textPaint.getFontSpacing()

    // Border settings
    val borderMargin = 40f
    val contentWidth = pageInfo.pageWidth - 2 * borderMargin
    val contentHeight = pageInfo.pageHeight - 2 * borderMargin
    val borderRect = RectF(borderMargin, borderMargin, borderMargin + contentWidth, borderMargin + contentHeight)

    // Header settings
    val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    var yPosition = borderMargin + 170f

    fun drawPageHeader(canvas: Canvas) {
        if (pageNumber == 1) {
            paint.color = Color.parseColor("#EADDFF")
            paint.style = Paint.Style.FILL
            val dateText = dateFormat.format(Date())
            paint.textSize = 10f
            paint.typeface = Typeface.SERIF
            val textWidth = paint.measureText(dateText)
            val rectWidth = textWidth + 20f
            val rectHeight = 30f
            val dateRect = RectF(borderMargin + 10f, borderMargin, borderMargin + 10f + rectWidth, borderMargin + rectHeight)
            canvas.drawRoundRect(dateRect, 10f, 10f, paint)

            paint.color = Color.parseColor("#6750A4")
            canvas.drawText(dateText, dateRect.left + 10f, dateRect.top + 20f, paint)
        }

        try {
            val svg = SVG.getFromResource(context.resources, R.raw.header_icon)
            val bitmap = Bitmap.createBitmap(250, 250, Bitmap.Config.ARGB_8888)
            val svgCanvas = Canvas(bitmap)
            svg.setDocumentWidth(250f)
            svg.setDocumentHeight(250f)
            svg.renderToCanvas(svgCanvas)
            canvas.drawBitmap(bitmap, borderMargin + contentWidth - 210f, borderMargin - 100f, paint)
            bitmap.recycle()
            Log.d("ChatHistoryDrawer", "SVG header icon drawn successfully")
        } catch (e: Exception) {
            Log.e("ChatHistoryDrawer", "Error loading SVG: ${e.message}")
            paint.textSize = 12f
            canvas.drawText("Header Image Missing", borderMargin + contentWidth - 150f, borderMargin + 10f, paint)
        }
    }

    fun drawPageNumber(canvas: Canvas, pageNum: Int) {
        paint.color = Color.parseColor("#EADDFF")
        paint.style = Paint.Style.FILL
        val boxWidth = 16f
        val boxHeight = 16f
        val boxRect = RectF(
            (contentWidth / 2) - (boxWidth / 2) + borderMargin,
            pageInfo.pageHeight - boxHeight - 20f,
            (contentWidth / 2) + (boxWidth / 2) + borderMargin,
            pageInfo.pageHeight - 20f
        )
        canvas.drawOval(boxRect, paint)

        paint.color = Color.parseColor("#6750A4")
        paint.textSize = 6f
        paint.typeface = Typeface.SERIF
        paint.textAlign = Paint.Align.CENTER
        val text = "$pageNum"
        val fontMetrics = paint.fontMetrics
        val textOffset = (fontMetrics.descent + fontMetrics.ascent) / 2
        canvas.drawText(
            text,
            boxRect.left + (boxWidth / 2),
            boxRect.top + (boxHeight / 2) - textOffset,
            paint
        )
    }

    fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val lines = mutableListOf<String>()
        val paragraphs = text.split("\n")
        paragraphs.forEach { paragraph ->
            val words = paragraph.split(" ")
            var currentLine = StringBuilder()
            words.forEach { word ->
                val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
                if (paint.measureText(testLine) <= maxWidth) {
                    currentLine = StringBuilder(testLine)
                } else {
                    if (currentLine.isNotEmpty()) lines.add(currentLine.toString())
                    currentLine = StringBuilder(word)
                }
            }
            if (currentLine.isNotEmpty()) lines.add(currentLine.toString())
        }
        return lines
    }

    fun calculateSegmentHeight(segment: MessageSegment, maxWidth: Float): Float {
        return when (segment) {
            is MessageSegment.TextSegment -> {
                val layout = StaticLayout(
                    segment.content,
                    textPaint,
                    (maxWidth - 2 * bubblePadding).toInt(),
                    Layout.Alignment.ALIGN_NORMAL,
                    1.2f,
                    3.0f,
                    false
                )
                layout.height.toFloat() + 8f // 8f for bottom spacing
            }
            is MessageSegment.CodeSegment -> {
                val languagePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = 8f
                    typeface = Typeface.SERIF
                    color = Color.GRAY
                }
                val codeTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = 8f
                    typeface = Typeface.MONOSPACE
                    color = Color.BLACK
                }
                val codeLayout = StaticLayout(
                    segment.code,
                    codeTextPaint,
                    (maxWidth - 4 * bubblePadding).toInt(), // Extra padding for code block
                    Layout.Alignment.ALIGN_NORMAL,
                    1.2f,
                    3.0f,
                    false
                )
                val languageHeight = languagePaint.getFontSpacing()
                val horizontalLineHeight = 5f // Increased line height
                val codeBlockPadding = 15f
                // Total: language header + padding + line + padding + code content + padding + bottom spacing
                languageHeight + codeBlockPadding + horizontalLineHeight + codeBlockPadding + codeLayout.height.toFloat() + codeBlockPadding + 8f
            }
        }
    }

    drawPageHeader(canvas)

    messages.forEachIndexed { index, message ->
        val timestamp = timeFormat.format(Date(System.currentTimeMillis() + index * 60000))
        val isUserMessage = index % 2 == 0

        // Parse the message into segments (text or code)
        val segments = parseMessageSegments(message.trim())

        // Calculate total height needed for all segments in this message
        val segmentsHeight = segments.sumOf { segment ->
            calculateSegmentHeight(segment, maxBubbleWidth).toDouble()
        }.toFloat()

        val totalBubbleHeight = segmentsHeight + 2 * bubblePadding + timestampHeight

        // Check if entire message bubble fits on current page
        if (yPosition + totalBubbleHeight > pageInfo.pageHeight - borderMargin) {
            drawPageNumber(canvas, pageNumber)
            document.finishPage(page)
            pageNumber++
            page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
            canvas = page.canvas
            paint.textSize = 8f
            paint.typeface = Typeface.SERIF
            paint.style = Paint.Style.FILL
            drawPageHeader(canvas)
            yPosition = if (pageNumber == 1) borderMargin + 170f else borderMargin + 100f
        }

        // Calculate bubble dimensions
        val bubbleWidth = maxBubbleWidth
        val left = if (isUserMessage) borderMargin + contentWidth - bubbleWidth - 20f else borderMargin + 20f
        val right = left + bubbleWidth
        val top = yPosition
        val bottom = yPosition + totalBubbleHeight
        val bubbleRect = RectF(left, top, right, bottom)

        // Draw main message bubble background - Fixed AI message color to light purple
        paint.color = if (isUserMessage) Color.parseColor("#F1F8E9") else Color.parseColor("#E6E6FA") // Light purple for AI
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(bubbleRect, bubbleRadius, bubbleRadius, paint)

        // Draw the tail
        val tailPath = Path()
        if (isUserMessage) {
            tailPath.moveTo(bubbleRect.right - bubbleRadius, bubbleRect.bottom - bubbleRadius)
            tailPath.lineTo(bubbleRect.right + 10f, bubbleRect.bottom)
            tailPath.lineTo(bubbleRect.right - bubbleRadius, bubbleRect.bottom)
        } else {
            tailPath.moveTo(bubbleRect.left + bubbleRadius, bubbleRect.bottom - bubbleRadius)
            tailPath.lineTo(bubbleRect.left - 10f, bubbleRect.bottom)
            tailPath.lineTo(bubbleRect.left + bubbleRadius, bubbleRect.bottom)
        }
        tailPath.close()
        canvas.drawPath(tailPath, paint)

        // Draw timestamp
        textPaint.color = Color.GRAY
        canvas.drawText(timestamp, bubbleRect.left + bubblePadding, yPosition + bubblePadding + 12f, textPaint)
        textPaint.color = Color.BLACK

        // Draw all segments within the bubble
        var currentY = yPosition + bubblePadding + timestampHeight

        segments.forEach { segment ->
            when (segment) {
                is MessageSegment.TextSegment -> {
                    val layout = StaticLayout(
                        segment.content,
                        textPaint,
                        (maxBubbleWidth - 2 * bubblePadding).toInt(),
                        Layout.Alignment.ALIGN_NORMAL,
                        1.2f,
                        3.0f,
                        false
                    )

                    canvas.save()
                    canvas.translate(bubbleRect.left + bubblePadding, currentY)
                    layout.draw(canvas)
                    canvas.restore()

                    currentY += layout.height.toFloat() + 8f // Add spacing after text
                }
                is MessageSegment.CodeSegment -> {
                    val language = segment.language ?: "Code"
                    val code = segment.code

                    // Code block styling
                    val codeBlockBackground = Color.parseColor("#F5F5F5") // White background
                    val codeTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                        textSize = 8f
                        typeface = Typeface.MONOSPACE
                        color = Color.BLACK
                    }
                    val languagePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                        textSize = 8f
                        typeface = Typeface.SERIF
                        color = Color.GRAY
                    }
                    val codeBlockPadding = 15f
                    val horizontalLineHeight = 5f // Increased line height for better visibility
                    val codeBlockRadius = 8f

                    // Calculate code block dimensions
                    val codeLayout = StaticLayout(
                        code,
                        codeTextPaint,
                        (maxBubbleWidth - 4 * bubblePadding).toInt(), // Extra padding for code block within bubble
                        Layout.Alignment.ALIGN_NORMAL,
                        1.2f,
                        3.0f,
                        false
                    )
                    val languageHeight = languagePaint.getFontSpacing()
                    val codeBlockHeight = languageHeight + codeBlockPadding + horizontalLineHeight + codeBlockPadding + codeLayout.height.toFloat() + codeBlockPadding

                    // Code block rectangle within the message bubble
                    val codeBlockRect = RectF(
                        bubbleRect.left + bubblePadding,
                        currentY,
                        bubbleRect.right - bubblePadding,
                        currentY + codeBlockHeight
                    )

                    // Draw code block background
                    paint.color = codeBlockBackground
                    canvas.drawRoundRect(codeBlockRect, codeBlockRadius, codeBlockRadius, paint)

                    // Draw language header
                    canvas.drawText(
                        language,
                        codeBlockRect.left + codeBlockPadding,
                        currentY + languageHeight - 2f,
                        languagePaint
                    )
                    currentY += languageHeight + codeBlockPadding

                    // Draw horizontal line (matching main bubble color) - FIXED: Properly divide the sections
                    paint.color = if (isUserMessage) Color.parseColor("#F1F8E9") else Color.parseColor("#E6E6FA") // Match bubble color
                    paint.style = Paint.Style.FILL
                    canvas.drawRect(
                        codeBlockRect.left, // Start from left edge of code block
                        currentY,
                        codeBlockRect.right, // End at right edge of code block
                        currentY + horizontalLineHeight,
                        paint
                    )
                    currentY += horizontalLineHeight + codeBlockPadding

                    // Draw code content
                    canvas.save()
                    canvas.translate(codeBlockRect.left + codeBlockPadding, currentY)
                    codeLayout.draw(canvas)
                    canvas.restore()

                    currentY += codeLayout.height.toFloat() + codeBlockPadding + 8f // Add spacing after code block
                }
            }
        }

        yPosition += totalBubbleHeight + 30f // Space between message bubbles
    }

    drawPageNumber(canvas, pageNumber)
    document.finishPage(page)

    val fileName = "Chat_$chatId.pdf"
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
                Log.d("ChatHistoryDrawer", "PDF saved to Downloads: $fileName")
            }
            contentValues.clear()
            contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
        } catch (e: Exception) {
            Log.e("ChatHistoryDrawer", "Error saving PDF: ${e.message}")
        } finally {
            document.close()
        }
    } ?: run {
        Log.e("ChatHistoryDrawer", "Failed to create file URI")
        onPermissionDenied()
        document.close()
    }
}
