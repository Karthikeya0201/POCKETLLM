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


// Add this function to parse message segments
fun parseMessageSegmentspdfDisplay(message: String): List<MessageSegment> {
    val segments = mutableListOf<MessageSegment>()
    val codeBlockRegex = Regex("```(\\w+)?\\n([\\s\\S]*?)```", RegexOption.MULTILINE)
    var lastIndex = 0

    codeBlockRegex.findAll(message).forEach { match ->
        if (match.range.first > lastIndex) {
            val textContent = message.substring(lastIndex, match.range.first).trim()
            if (textContent.isNotEmpty()) {
                segments.add(MessageSegment.TextSegment(textContent))
            }
        }

        val language = match.groupValues[1].takeIf { it.isNotEmpty() }
        val code = match.groupValues[2]
        segments.add(MessageSegment.CodeSegment(language, code))

        lastIndex = match.range.last + 1
    }

    if (lastIndex < message.length) {
        val textContent = message.substring(lastIndex).trim()
        if (textContent.isNotEmpty()) {
            segments.add(MessageSegment.TextSegment(textContent))
        }
    }

    if (segments.isEmpty()) {
        segments.add(MessageSegment.TextSegment(message))
    }

    return segments
}

fun generateChatPdf(context: android.content.Context, chatId: String, messages: List<String>, onPermissionDenied: () -> Unit) {
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
    val bubblePadding = 15f
    val bubbleRadius = 15f
    val timestampHeight = 25f
    val messageSpacing = 20f

    // Header settings
    val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    var yPosition = borderMargin + 120f

    fun drawPageHeader(canvas: Canvas) {
        // Draw date badge (only on first page)
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
        }

        // Draw header icon
        try {
            val svg = SVG.getFromResource(context.resources, R.raw.header_icon)
            val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            val svgCanvas = Canvas(bitmap)
            svg.setDocumentWidth(100f)
            svg.setDocumentHeight(100f)
            svg.renderToCanvas(svgCanvas)
            canvas.drawBitmap(bitmap, borderMargin + contentWidth - 110f, borderMargin + 10f, paint)
            bitmap.recycle()
        } catch (e: Exception) {
            Log.e("ChatHistoryDrawer", "Error loading SVG: ${e.message}")
        }
    }

    fun drawPageNumber(canvas: Canvas, pageNum: Int) {
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
        drawPageNumber(canvas, pageNumber)
        document.finishPage(page)
        pageNumber++
        page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
        canvas = page.canvas
        drawPageHeader(canvas)
        yPosition = borderMargin + 80f
        return canvas
    }

    // Updated calculateTextHeight function
    fun calculateTextHeight(text: String, paint: TextPaint, maxWidth: Float): Float {
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, maxWidth.toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.2f)
            .setIncludePad(false)
            .build()
        return layout.height.toFloat() + 15f // Add extra padding for safety
    }

    // Updated calculateCodeHeight function with proper wrapping calculation
    fun calculateCodeHeight(code: String, language: String?): Float {
        val codeTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 9f
            typeface = Typeface.MONOSPACE
            color = Color.BLACK
        }
        val languageHeight = if (language != null) 25f else 0f // Increased from 25f
        val separatorHeight = if (language != null) 8f else 0f
        val maxCodeWidth = maxBubbleWidth - 2 * bubblePadding - 20f // Account for padding

        val codeLines = code.lines()
        var totalWrappedLines = 0

        codeLines.forEach { line ->
            if (line.isEmpty()) {
                totalWrappedLines += 1
            } else if (codeTextPaint.measureText(line) <= maxCodeWidth) {
                totalWrappedLines += 1
            } else {
                // Calculate how many lines this will wrap to
                var remainingLine = line
                while (remainingLine.isNotEmpty()) {
                    var breakPoint = remainingLine.length

                    // Find the best break point
                    for (i in remainingLine.length - 1 downTo 1) {
                        val substring = remainingLine.substring(0, i)
                        if (codeTextPaint.measureText(substring) <= maxCodeWidth) {
                            breakPoint = i
                            break
                        }
                    }

                    // If even single character is too wide, force break at 1 character
                    if (breakPoint == remainingLine.length && remainingLine.length > 1) {
                        breakPoint = maxOf(1, (maxCodeWidth / codeTextPaint.measureText("M")).toInt())
                    }

                    totalWrappedLines += 1
                    remainingLine = remainingLine.substring(breakPoint)
                }
            }
        }

        val lineHeight = codeTextPaint.getFontSpacing() * 1.2f // Increased line spacing
        val totalCodeHeight = totalWrappedLines * lineHeight
        val padding = 16f // Increased padding

        return languageHeight + separatorHeight + totalCodeHeight + padding
    }
    // Updated drawTextContent function
    fun drawTextContent(text: String, bubbleRect: RectF, startY: Float): Float {
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, (maxBubbleWidth - 2 * bubblePadding).toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.2f)
            .setIncludePad(false)
            .build()

        canvas.save()
        canvas.translate(bubbleRect.left + bubblePadding, startY)
        layout.draw(canvas)
        canvas.restore()

        return startY + layout.height + 5f // Reduced extra spacing since we add it in drawMessageBubble
    }

    // Updated drawCodeContent function
    fun drawCodeContent(language: String?, code: String, bubbleRect: RectF, startY: Float): Float {
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
            0f // Will be calculated
        )

        var currentY = startY + 8f // Increased initial padding
        val lineHeight = codeTextPaint.getFontSpacing() * 1.2f // Increased line spacing
        val maxCodeWidth = codeRect.width() - 20f // Account for padding

        // Draw language header if present
        if (language != null) {
            canvas.drawText(language, codeRect.left + 10f, currentY + lineHeight - 3f, languagePaint)
            currentY += 20f

            // Draw separator line
            paint.color = Color.parseColor("#E0E0E0")
            paint.strokeWidth = 1f
            canvas.drawLine(codeRect.left + 10f, currentY, codeRect.right - 10f, currentY, paint)
            currentY += 8f
        }

        // Calculate code area height with line wrapping (same logic as calculateCodeHeight)
        val codeLines = code.lines()
        val wrappedLines = mutableListOf<String>()

        codeLines.forEach { line ->
            if (line.isEmpty()) {
                wrappedLines.add("")
            } else if (codeTextPaint.measureText(line) <= maxCodeWidth) {
                wrappedLines.add(line)
            } else {
                // Break long lines into multiple lines
                var remainingLine = line
                while (remainingLine.isNotEmpty()) {
                    var breakPoint = remainingLine.length

                    // Find the best break point
                    for (i in remainingLine.length - 1 downTo 1) {
                        val substring = remainingLine.substring(0, i)
                        if (codeTextPaint.measureText(substring) <= maxCodeWidth) {
                            breakPoint = i
                            break
                        }
                    }

                    // If even single character is too wide, force break at 1 character
                    if (breakPoint == remainingLine.length && remainingLine.length > 1) {
                        breakPoint = maxOf(1, (maxCodeWidth / codeTextPaint.measureText("M")).toInt())
                    }

                    wrappedLines.add(remainingLine.substring(0, breakPoint))
                    remainingLine = remainingLine.substring(breakPoint)
                }
            }
        }

        val codeHeight = wrappedLines.size * lineHeight + 12f

        // Update code rect with final height
        codeRect.bottom = currentY + codeHeight

        // Draw code background
        paint.color = codeBackgroundColor
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(codeRect, 8f, 8f, paint)

        // Draw wrapped code lines
        currentY += 6f
        wrappedLines.forEach { line ->
            canvas.drawText(line, codeRect.left + 10f, currentY + lineHeight - 3f, codeTextPaint)
            currentY += lineHeight
        }

        return codeRect.bottom + 5f // Reduced extra spacing since we add it in drawMessageBubble
    }

    // Updated drawMessageBubble function with better height calculation
    fun drawMessageBubble(
        segments: List<MessageSegment>,
        isUserMessage: Boolean,
        timestamp: String,
        messageIndex: Int
    ) {
        // Calculate total content height for all segments with more accurate calculation
        var totalContentHeight = timestampHeight + 8f // Add extra space after timestamp

        segments.forEach { segment ->
            when (segment) {
                is MessageSegment.TextSegment -> {
                    val textHeight = calculateTextHeight(segment.content, textPaint, maxBubbleWidth - 2 * bubblePadding)
                    totalContentHeight += textHeight + 10f // Add spacing between segments
                }
                is MessageSegment.CodeSegment -> {
                    val codeHeight = calculateCodeHeight(segment.code, segment.language)
                    totalContentHeight += codeHeight + 10f // Add spacing between segments
                }
            }
        }

        val totalBubbleHeight = totalContentHeight + 2 * bubblePadding + 10f // Add extra padding

        // Check if entire message fits on current page
        if (yPosition + totalBubbleHeight > pageInfo.pageHeight - borderMargin - 50f) {
            canvas = createNewPage()
        }

        // Calculate bubble position
        val bubbleLeft = if (isUserMessage) borderMargin + contentWidth - maxBubbleWidth - 20f else borderMargin + 20f
        val bubbleRight = bubbleLeft + maxBubbleWidth
        val bubbleTop = yPosition
        val bubbleBottom = yPosition + totalBubbleHeight
        val bubbleRect = RectF(bubbleLeft, bubbleTop, bubbleRight, bubbleBottom)

        // Draw bubble background
        paint.color = if (isUserMessage) Color.parseColor("#E8F5E8") else Color.parseColor("#F0F0FF")
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(bubbleRect, bubbleRadius, bubbleRadius, paint)

        // Draw bubble tail
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

        // Draw timestamp
        val timestampPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 8f
            typeface = Typeface.DEFAULT
            color = Color.GRAY
        }
        canvas.drawText(timestamp, bubbleRect.left + bubblePadding, bubbleRect.top + bubblePadding + 12f, timestampPaint)

        // Draw content segments
        var currentY = bubbleRect.top + bubblePadding + timestampHeight + 3f // Add extra space after timestamp

        segments.forEach { segment ->
            when (segment) {
                is MessageSegment.TextSegment -> {
                    currentY = drawTextContent(segment.content, bubbleRect, currentY)
                    currentY += 10f // Add spacing between segments
                }
                is MessageSegment.CodeSegment -> {
                    currentY = drawCodeContent(segment.language, segment.code, bubbleRect, currentY)
                    currentY += 10f // Add spacing between segments
                }
            }
        }

        yPosition = bubbleBottom + messageSpacing
    }

    // Start generating PDF
    drawPageHeader(canvas)

    messages.forEachIndexed { index, message ->
        val timestamp = timeFormat.format(Date(System.currentTimeMillis() + index * 60000))
        val isUserMessage = index % 2 == 0
        val segments = parseMessageSegmentspdfDisplay(message.trim())

        drawMessageBubble(segments, isUserMessage, timestamp, index)
    }

    // Finish last page
    drawPageNumber(canvas, pageNumber)
    document.finishPage(page)

    // Save PDF
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
