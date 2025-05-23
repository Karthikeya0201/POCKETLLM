package com.example.llama.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.delay

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

    // State to control the visibility of the "Copy" button
    var showCopyButton by remember { mutableStateOf(false) }

    // Clipboard manager to copy text
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

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

    // Auto-hide the "Copy" button after 3 seconds
    LaunchedEffect(showCopyButton) {
        if (showCopyButton) {
            delay(3000)
            showCopyButton = false
        }
    }

    Box(modifier = modifier) {
        // Main bubble with long press detection
        Box(
            modifier = Modifier
                .background(bubbleColor, RoundedCornerShape(16.dp))
                .padding(14.dp)
                .align(Alignment.Center)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = {
                            if (!isLoading) { // Only show copy button if not loading
                                showCopyButton = true
                            }
                        },
                        onTap = {
                            // Hide the copy button if the user taps the bubble while it's visible
                            if (showCopyButton) {
                                showCopyButton = false
                            }
                        }
                    )
                }
        ) {
            if (isLoading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                Text(
                    text = text,
                    color = textColor,
                    fontSize = 16.sp,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Normal
                )
            }
        }

        // "Copy" button, shown above the bubble when long pressed
        if (showCopyButton && !isLoading) {
            Button(
                onClick = {
                    clipboardManager.setText(AnnotatedString(text))
                    showCopyButton = false
                    // Optional: Show a toast to confirm the text was copied
                    android.widget.Toast.makeText(context, "Text copied!", android.widget.Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier
                    .align(if (isUser) Alignment.TopEnd else Alignment.TopStart)
                    .offset(y = (-40).dp)
                    .shadow(4.dp, RoundedCornerShape(8.dp)),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "Copy",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Tail Canvas - positioned relative to bubble edge
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
