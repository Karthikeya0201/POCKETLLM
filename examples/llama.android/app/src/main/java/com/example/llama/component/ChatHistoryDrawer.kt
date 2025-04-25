package com.example.llama.component

import android.Manifest
import android.content.ContentValues
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Velocity
import androidx.core.content.ContextCompat
import com.caverock.androidsvg.SVG
import com.example.llama.R
import com.example.llama.model.ChatHistory
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatHistoryDrawer(
    histories: List<ChatHistory>,
    onHistorySelected: (String) -> Unit,
    onNewChat: () -> Unit,
    onDeleteChat: (String) -> Unit,
    getChatMessages: (String) -> List<String>,
    requestPermissions: (Array<String>) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var isActionMode by remember { mutableStateOf(false) }
    var selectedAction by remember { mutableStateOf<String?>(null) }
    val selectedChats = remember { mutableStateListOf<String>() }
    val context = LocalContext.current
    var showPermissionDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    Log.d("ChatHistoryDrawer", "Rendering drawer with ${histories.size} chat histories")

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override suspend fun onPreFling(available: Velocity): Velocity {
                return Velocity.Zero
            }
        }
    }

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

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(280.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp)
            .nestedScroll(nestedScrollConnection)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Chat History",
                style = MaterialTheme.typography.headlineSmall
            )
            Row {
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Filled.MoreVert, "Menu")
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("New Chat") },
                            onClick = {
                                Log.d("ChatHistoryDrawer", "New chat selected")
                                onNewChat()
                                showMenu = false
                                isActionMode = false
                                selectedChats.clear()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Download Chats") },
                            onClick = {
                                Log.d("ChatHistoryDrawer", "Download chats selected")
                                selectedAction = "download"
                                isActionMode = true
                                showMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete Chats") },
                            onClick = {
                                Log.d("ChatHistoryDrawer", "Delete chats selected")
                                selectedAction = "delete"
                                isActionMode = true
                                showMenu = false
                            }
                        )
                    }
                }
                if (isActionMode) {
                    IconButton(onClick = {
                        when (selectedAction) {
                            "delete" -> {
                                if (selectedChats.isNotEmpty()) {
                                    Log.d("ChatHistoryDrawer", "Deleting selected chats: $selectedChats")
                                    selectedChats.forEach { chatId -> onDeleteChat(chatId) }
                                    selectedChats.clear()
                                }
                            }
                            "download" -> {
                                if (selectedChats.isNotEmpty()) {
                                    Log.d("ChatHistoryDrawer", "Downloading selected chats: $selectedChats")
                                    selectedChats.forEach { chatId ->
                                        val messages = getChatMessages(chatId)
                                        generateChatPdf(context, chatId, messages)
                                    }
                                    selectedChats.clear()
                                }
                            }
                        }
                        isActionMode = false
                        selectedAction = null
                    }) {
                        Icon(Icons.Filled.Check, "Confirm Action", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        Divider(modifier = Modifier.padding(vertical = 12.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (histories.isEmpty()) {
                item {
                    Log.d("ChatHistoryDrawer", "No chat histories to display")
                    Text(
                        text = "No chat history yet",
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        color = ComposeColor.Gray,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                items(histories) { history ->
                    val dateFormat = SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault())
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isActionMode) {
                                Checkbox(
                                    checked = selectedChats.contains(history.id),
                                    onCheckedChange = { isChecked ->
                                        if (isChecked) {
                                            selectedChats.add(history.id)
                                            Log.d("ChatHistoryDrawer", "Selected chat: ${history.id}")
                                        } else {
                                            selectedChats.remove(history.id)
                                            Log.d("ChatHistoryDrawer", "Deselected chat: ${history.id}")
                                        }
                                    },
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                            }
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable(enabled = !isActionMode) {
                                        if (!isActionMode) {
                                            Log.d("ChatHistoryDrawer", "Selected chat: ${history.id}")
                                            onHistorySelected(history.id)
                                        }
                                    }
                            ) {
                                Text(text = history.title, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = dateFormat.format(history.date),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ComposeColor.Gray
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

fun generateChatPdf(context: android.content.Context, chatId: String, messages: List<String>) {
    val document = PdfDocument()
    var pageNumber = 1
    val pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNumber).create()
    var page = document.startPage(pageInfo)
    var canvas = page.canvas
    val paint = Paint()
    val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 8f // Reduced font size
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
    var yPosition = borderMargin + 170f // Below header for page 1

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

    drawPageHeader(canvas)

    messages.forEachIndexed { index, message ->
        val timestamp = timeFormat.format(Date(System.currentTimeMillis() + index * 60000))
        // Alternate between user and AI based on index (even index = user, odd index = AI)
        val isUserMessage = index % 2 == 0 // Assumes user messages are at even indices
        val cleanMessage = message.trim()

        Log.d("ChatHistoryDrawer", "Message $index: $message, isUserMessage: $isUserMessage")

        var lines = wrapText(cleanMessage, paint, maxBubbleWidth - 2 * bubblePadding)
        var lineIndex = 0
        while (lineIndex < lines.size) {
            val remainingHeight = (pageInfo.pageHeight - borderMargin) - yPosition
            val maxLines = ((remainingHeight - 2 * bubblePadding - (if (lineIndex == 0) timestampHeight else 0f)) / lineHeight).toInt().coerceAtLeast(1)
            val linesForBubble = lines.subList(lineIndex, minOf(lineIndex + maxLines, lines.size))
            val text = linesForBubble.joinToString("\n")
            val layout = StaticLayout(text, textPaint, (maxBubbleWidth - 2 * bubblePadding).toInt(), Layout.Alignment.ALIGN_NORMAL, 1.2f, 3.0f, false)
            val contentHeightBubble = layout.height.toFloat()
            val extraHeight = if (lineIndex == 0) timestampHeight else 0f
            val bubbleHeight = contentHeightBubble + 2 * bubblePadding + extraHeight

            if (yPosition + bubbleHeight > pageInfo.pageHeight - borderMargin) {
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
                continue
            }

            val contentWidthLayout = layout.width.toFloat()
            val timestampWidth = if (lineIndex == 0) textPaint.measureText(timestamp) else 0f
            val maxContentWidth = maxOf(contentWidthLayout, timestampWidth)
            val bubbleWidth = maxContentWidth + 2 * bubblePadding
            // User on the right, assistant on the left
            val left = if (isUserMessage) borderMargin + contentWidth - bubbleWidth - 20f else borderMargin + 20f
            val right = left + bubbleWidth
            val top = yPosition
            val bottom = yPosition + bubbleHeight
            val bubbleRect = RectF(left, top, right, bottom)
            paint.color = if (isUserMessage) Color.parseColor("#F1F8E9") else Color.parseColor("#E0F7FA")
            canvas.drawRoundRect(bubbleRect, bubbleRadius, bubbleRadius, paint)
            val tailPath = Path()
            // Tail directions: right tail for user (right side), left tail for assistant (left side)
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
            if (lineIndex == 0) {
                textPaint.color = Color.GRAY
                canvas.drawText(timestamp, bubbleRect.left + bubblePadding, yPosition + bubblePadding + 12f, textPaint)
                textPaint.color = Color.BLACK
            }
            val startY = yPosition + bubblePadding + (if (lineIndex == 0) timestampHeight else 0f)
            canvas.save()
            canvas.translate(bubbleRect.left + bubblePadding, startY)
            layout.draw(canvas)
            canvas.restore()
            yPosition += bubbleHeight + 30f
            lineIndex += linesForBubble.size
        }
    }

    drawPageNumber(canvas, pageNumber)
    document.finishPage(page)

    // Save to Downloads using MediaStore
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
        document.close()
    }
}
