package com.example.llama.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text


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

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .background(bubbleColor, RoundedCornerShape(16.dp))
                .padding(14.dp)
                .align(Alignment.Center)
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
