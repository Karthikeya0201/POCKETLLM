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
    onModelSelected: (String) -> Unit
) {
    val context = LocalContext.current
    // List of models to display: (displayName, isDownloaded, fileName, path)
    var availableModels by remember { mutableStateOf<List<Quadruple<String, Boolean, String, String>>>(emptyList()) }

    LaunchedEffect(Unit) {
        // 1. Look for imported models in context.filesDir
        val internalDir = context.filesDir
        val importedFiles = internalDir.listFiles()
            ?.filter { it.name.endsWith(".gguf", ignoreCase = true) }
            ?.toList() ?: emptyList()

        // 2. Look for downloaded models in context.getExternalFilesDir(null)/Download
        val externalBaseDir = context.getExternalFilesDir(null) ?: context.filesDir // Fallback to filesDir if external storage is unavailable
        val downloadDir = File(externalBaseDir, "Download")
        if (!downloadDir.exists()) {
            downloadDir.mkdirs() // Create the directory if it doesn't exist
            Log.d("ModelSelectionDialog", "Created Download directory at: ${downloadDir.absolutePath}")
        }
        val downloadedFiles = downloadDir.listFiles()
            ?.filter { it.name.endsWith(".gguf", ignoreCase = true) }
            ?.toList() ?: emptyList()

        // 3. Combine the lists and remove duplicates based on file name
        val allFiles = (importedFiles + downloadedFiles).distinctBy { it.name }

        // 4. Build the list of models that are actually available
        val modelList = allFiles.mapNotNull { file ->
            val fileName = file.name
            val displayName = viewModel.getModelDisplayName(fileName)
            // Find the corresponding model in modelOptions to determine if it's a "downloaded" model
            val modelOption = modelOptions.find { getModelFileName(it.name) == fileName }
            // Check if the model is marked as downloaded in viewModel.downloadedModels
            val isDownloaded = modelOption?.let { viewModel.downloadedModels.value.contains(it.name) } ?: false
            // Since we're iterating over files that exist, we know the path is valid
            val path = file.absolutePath

            Quadruple(displayName, isDownloaded, fileName, path)
        }.distinctBy { it.third } // Avoid duplicates based on fileName

        availableModels = modelList
        Log.d("ModelSelectionDialog", "Available models (imported or downloaded): $availableModels")
        Log.d("ModelSelectionDialog", "Scanned internal dir: ${internalDir.absolutePath}")
        Log.d("ModelSelectionDialog", "Scanned download dir: ${downloadDir.absolutePath}")
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
                        items(availableModels) { (displayName, isDownloaded, fileName, path) ->
                            val isSelected = viewModel.selectedFileName.value == displayName

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        Log.d("ModelSelectionDialog", "Selecting model: $displayName, fileName=$fileName, path=$path, size=${File(path).length()} bytes")
                                        onModelSelected(path)
                                        onDismiss()
                                    },
                                shape = RoundedCornerShape(8.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) PurplePrimary.copy(alpha = 0.1f) else Color.White
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
                                                        "Qwen 2.5-0.5B" -> R.drawable.ic_qwen_icon
                                                        "Arcee-lite 1.78B" -> R.drawable.ic_arcee_lite_icon
                                                        "Orca Mini-3B" -> R.drawable.ic_orca_icon
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

// Helper data class to hold model information
data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)
