package com.example.llama.component

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Composable for displaying a chat message bubble with text and code block support.
 *
 * @param text The message text, potentially containing code blocks (e.g., ```language\ncode\n```).
 * @param isUser Whether the message is from the user (affects styling and alignment).
 * @param modifier Modifier for the bubble.
 * @param isLoading Whether the bubble is in a loading state (shows animated dots).
 */
@Composable
fun MessageBubble(
    text: String,
    isUser: Boolean,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false
) {
    val bubbleColor = if (isUser)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.secondaryContainer

    val textColor = if (isUser)
        MaterialTheme.colorScheme.onPrimaryContainer
    else
        MaterialTheme.colorScheme.onSecondaryContainer

    val dotAnimatable = remember { Animatable(0f) }

    LaunchedEffect(isLoading) {
        if (isLoading) {
            dotAnimatable.animateTo(
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 1200
                        0f at 0 with LinearEasing
                        1f at 400 with LinearEasing
                        0f at 800 with LinearEasing
                    },
                    repeatMode = RepeatMode.Restart
                )
            )
        } else {
            dotAnimatable.snapTo(0f)
        }
    }

    // State for message segments, updated dynamically
    var messageSegments by remember { mutableStateOf(listOf<MessageSegment>()) }

    // Reparse text whenever it changes to simulate streaming
    LaunchedEffect(text) {
        messageSegments = parseMessageSegments(text)
    }

    Box(modifier = modifier) {
        // Main bubble
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp) // Constrain maximum width
                .background(bubbleColor, RoundedCornerShape(16.dp))
                .align(Alignment.Center)
        ) {
            if (isLoading) {
                Row(
                    modifier = Modifier.padding(14.dp), // Apply padding to content
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (i in 0..2) {
                        val alpha = when ((dotAnimatable.value + (i * 0.33f)) % 1f) {
                            in 0f..0.33f -> 0.3f
                            in 0.33f..0.66f -> 1f
                            else -> 0.3f
                        }
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(
                                    Color.Gray.copy(alpha = alpha),
                                    RoundedCornerShape(50)
                                )
                                .padding(end = 4.dp)
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier.padding(14.dp) // Apply padding to content
                ) {
                    messageSegments.forEach { segment ->
                        when (segment) {
                            is MessageSegment.TextSegment -> {
                                Text(
                                    text = segment.content,
                                    color = textColor,
                                    fontSize = 16.sp,
                                    fontFamily = FontFamily.SansSerif,
                                    fontWeight = FontWeight.Normal,
                                    modifier = Modifier
                                        .padding(bottom = 8.dp),
                                    softWrap = true,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Clip
                                )
                            }
                            is MessageSegment.CodeSegment -> {
                                CodeBlock(
                                    language = segment.language ?: "Code",
                                    code = segment.code,
                                    bubbleColor = bubbleColor,
                                    modifier = Modifier
                                        .padding(bottom = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Tail Canvas
        Canvas(
            modifier = Modifier
                .align(if (isUser) Alignment.BottomEnd else Alignment.BottomStart)
                .offset(x = if (isUser) (-4).dp else 4.dp, y = (-4).dp)
        ) {
            val tailHeight = 12.dp.toPx()
            val tailWidth = 16.dp.toPx()
            val path = Path().apply {
                if (isUser) {
                    moveTo(0f, 0f)
                    lineTo(tailWidth, -tailHeight / 2)
                    lineTo(0f, -tailHeight)
                } else {
                    moveTo(0f, 0f)
                    lineTo(-tailWidth, -tailHeight / 2)
                    lineTo(0f, -tailHeight)
                }
                close()
            }
            drawPath(
                path = path,
                color = bubbleColor
            )
        }
    }
}

/**
 * Sealed class representing segments of a message (text or code).
 */
sealed class MessageSegment {
    data class TextSegment(val content: String) : MessageSegment()
    data class CodeSegment(val language: String?, val code: String) : MessageSegment()
}

/**
 * Parses a message into text and code block segments, starting a code block as soon as an opening ``` is detected.
 *
 * @param text The input message text.
 * @return List of [MessageSegment] (TextSegment or CodeSegment).
 */
private fun parseMessageSegments(text: String): List<MessageSegment> {
    val segments = mutableListOf<MessageSegment>()
    var currentIndex = 0
    var isInCodeBlock = false
    var codeBlockStartIndex = 0
    var language: String? = null

    while (currentIndex < text.length) {
        if (!isInCodeBlock) {
            // Look for opening ```
            val openingIndex = text.indexOf("```", currentIndex)
            if (openingIndex == -1) {
                // No more code blocks, add remaining text
                val remainingText = text.substring(currentIndex)
                if (remainingText.isNotBlank()) {
                    segments.add(MessageSegment.TextSegment(remainingText))
                }
                break
            }

            // Add text before the code block
            if (openingIndex > currentIndex) {
                val beforeText = text.substring(currentIndex, openingIndex)
                if (beforeText.isNotBlank()) {
                    segments.add(MessageSegment.TextSegment(beforeText))
                }
            }

            // Process opening ```
            currentIndex = openingIndex + 3 // Skip ```
            // Extract language (until newline)
            val newlineIndex = text.indexOf('\n', currentIndex).takeIf { it != -1 } ?: text.length
            language = text.substring(currentIndex, newlineIndex).trim().takeIf { it.isNotBlank() }
            currentIndex = newlineIndex + 1 // Skip newline
            codeBlockStartIndex = currentIndex
            isInCodeBlock = true
        } else {
            // Look for closing ```
            val closingIndex = text.indexOf("```", currentIndex)
            if (closingIndex == -1) {
                // No closing ```, treat rest as code
                val code = text.substring(codeBlockStartIndex)
                if (code.isNotBlank()) {
                    segments.add(MessageSegment.CodeSegment(language, code.trimEnd()))
                }
                break
            }

            // Add code up to closing ```
            val code = text.substring(codeBlockStartIndex, closingIndex)
            if (code.isNotBlank()) {
                segments.add(MessageSegment.CodeSegment(language, code.trimEnd()))
            }
            currentIndex = closingIndex + 3 // Skip ```
            isInCodeBlock = false
            language = null
        }
    }

    // Handle empty text
    if (segments.isEmpty() && text.isNotBlank()) {
        segments.add(MessageSegment.TextSegment(text))
    }

    return segments
}

/**
 * Composable for rendering a code block with a language header, copy button, and a horizontal line.
 *
 * @param language The programming language (e.g., "kotlin", "python").
 * @param code The code content.
 * @param bubbleColor The background color of the message bubble, used for the horizontal line.
 * @param modifier Modifier for the code block.
 */
@Composable
fun CodeBlock(
    language: String,
    code: String,
    bubbleColor: Color,
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val codeBlockBackground = Color(0xFFF5F5F5)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(codeBlockBackground, RoundedCornerShape(8.dp))
    ) {
        // Header with language and copy button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp), // Increased padding
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = language,
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.Gray
                ),
                modifier = Modifier.weight(1f) // Push the copy button to the right
            )
            Box(
                modifier = Modifier
                    .clickable {
                        clipboardManager.setText(AnnotatedString(code))
                        android.widget.Toast.makeText(context, "Code copied!", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    .padding(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy Code",
                        tint = Color.Gray,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Copy",
                        style = TextStyle(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.Gray
                        )
                    )
                }
            }
        }
        // Thick horizontal line matching the message bubble color
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(bubbleColor)
        )
        Spacer(modifier = Modifier.height(8.dp)) // Increased spacer height
        Text(
            text = code,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
                color = Color.Black,
                textAlign = TextAlign.Start,
                lineHeight = 21.sp
            ),
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 15.dp), // Increased padding
            softWrap = true
        )
    }
}
