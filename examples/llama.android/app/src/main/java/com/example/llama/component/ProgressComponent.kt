package com.example.llama.component

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ProgressComponent(
    progress: Float,
    isLoading: Boolean,
    modifier: Modifier = Modifier
) {
    if (isLoading) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (progress > 0f && progress < 1f) {
                Text(
                    text = "Copying file... ${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium
                )
                LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
            } else {
                CircularProgressIndicator()
                Text(
                    text = "Loading model...",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}
