package com.example.llama.component

import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.llama.MainViewModel
import com.example.llama.R
import com.example.llama.model.Downloadable
import com.example.llama.ui.theme.PurplePrimary
import java.io.File

private fun getModelFileName(modelName: String): String {
    return "${modelName.lowercase().replace(" ", "_")}.gguf"
}

@Composable
fun ModelSelectionDialog(
    viewModel: MainViewModel,
    modelOptions: List<Downloadable>,
    onDismiss: () -> Unit,
    onModelSelected: (String, Boolean) -> Unit
) {
    val context = LocalContext.current
    var availableModels by remember { mutableStateOf<List<Triple<String, Boolean, String>>>(emptyList()) }

    LaunchedEffect(Unit) {
        val filesDir = context.filesDir
        val ggufFiles = filesDir.listFiles()?.filter { it.name.endsWith(".gguf", ignoreCase = true) }?.toList() ?: emptyList()
        val modelList = ggufFiles.map { file ->
            val modelName = file.name.removeSuffix(".gguf")
            val isDownloaded = modelOptions.any {
                getModelFileName(it.name) == file.name
            }
            val displayName = viewModel.getModelDisplayName(file.name)
            Triple(displayName, isDownloaded, file.name)
        }
        availableModels = modelList
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .wrapContentHeight()
                .background(Color.Transparent),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Select Model",
                        style = MaterialTheme.typography.headlineSmall,
                        color = PurplePrimary
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = PurplePrimary
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                if (availableModels.isEmpty()) {
                    Text(
                        text = "No models available. Please download or import a model.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(availableModels) { (displayName, isDownloaded, actualFileName) ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val file = File(context.filesDir, actualFileName)
                                        if (file.exists()) {
                                            Log.d("ModelSelectionDialog", "Selecting model: $displayName, fileName=$actualFileName, path=${file.absolutePath}, size=${file.length()} bytes")
                                            onModelSelected(file.absolutePath, isDownloaded)
                                            onDismiss()
                                        } else {
                                            Log.e("ModelSelectionDialog", "File does not exist: $actualFileName")
                                        }
                                    },
                                shape = RoundedCornerShape(8.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (viewModel.selectedFileName.value == displayName) PurplePrimary.copy(alpha = 0.1f) else Color.White
                                ),
                                border = BorderStroke(1.dp, Color(0xFFD9BDFE))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = Color(0xFFD9BDFE),
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Image(
                                            painter = painterResource(
                                                id = if (isDownloaded) {
                                                    when (displayName) {
                                                        "Orca Mini-3B" -> R.drawable.ic_orca_icon
                                                        "Gemma 3-4B" -> R.drawable.ic_gemma_icon
                                                        "Mistral-7B" -> R.drawable.ic_mistral_icon
                                                        else -> R.drawable.ic_ai_chip
                                                    }
                                                } else {
                                                    R.drawable.ic_ai_chip
                                                }
                                            ),
                                            contentDescription = displayName,
                                            modifier = Modifier
                                                .padding(8.dp)
                                                .fillMaxSize()
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = displayName,
                                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                        color = Color(0xFF1C1B1F)
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
