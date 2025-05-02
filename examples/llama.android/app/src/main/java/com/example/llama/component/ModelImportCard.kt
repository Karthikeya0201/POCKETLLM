package com.example.llama.component

import android.app.DownloadManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.llama.MainViewModel
import com.example.llama.model.Downloadable
import com.example.llama.ui.theme.PurplePrimary
import com.example.llama.ui.theme.PurpleLight
import com.example.llama.ui.theme.PurpleDark
import java.io.File

@Composable
fun ModelImportCard(
    viewModel: MainViewModel,
    downloadManager: DownloadManager,
    modelOptions: List<Downloadable>,
    onImportClick: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isModelLoaded by remember { derivedStateOf { !viewModel.isLoadingModel.value && viewModel.importError.value.isEmpty() } }
    val loadedModelName by remember { derivedStateOf { viewModel.modelPath.value?.let { File(it).name } } }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = PurpleLight),
        shape = MaterialTheme.shapes.medium, // Matches design's rounded corners
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Choose your AI Model",
                style = MaterialTheme.typography.titleMedium,
                color = PurpleDark,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // List of predefined models
            modelOptions.forEach { model ->
                Downloadable.DownloadButton(
                    viewModel = viewModel,
                    downloadManager = downloadManager,
                    item = model,
                    onDownloadComplete = { path ->
                        viewModel.setModelPath(path)
                        // The save action will be triggered by the user clicking the "Save" button
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Import button
            Button(
                onClick = onImportClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = PurplePrimary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Import Model (.gguf)")
            }

            // Loading progress
            ProgressComponent(
                progress = 0f, // Progress is handled in Downloadable.DownloadButton or SetupScreen for imports
                isLoading = viewModel.isLoadingModel.value,
                modifier = Modifier.fillMaxWidth()
            )

            // Loaded model feedback and save button
            if (isModelLoaded && loadedModelName != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Model Loaded: $loadedModelName",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PurpleDark
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onSave,
                    enabled = isModelLoaded,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PurplePrimary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save")
                }
            }
        }
    }
}
