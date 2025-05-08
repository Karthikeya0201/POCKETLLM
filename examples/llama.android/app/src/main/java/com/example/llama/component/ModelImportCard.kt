package com.example.llama.component

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Divider
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import com.example.llama.MainViewModel
import com.example.llama.R
import com.example.llama.model.Downloadable
import com.example.llama.ui.theme.Black
import com.example.llama.ui.theme.PurplePrimary
import com.example.llama.ui.theme.PurpleLight
import com.example.llama.ui.theme.PurpleDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.cancelAndJoin

private fun checkModelInLocalStorage(context: Context): Boolean {
    val filesDir = context.filesDir
    return filesDir.listFiles()?.any { file ->
        file.name.endsWith(".gguf", ignoreCase = true)
    } ?: false
}

@Composable
fun ModelImportCard(
    viewModel: MainViewModel,
    downloadManager: DownloadManager,
    modelOptions: List<Downloadable>,
    onImportClick: () -> Unit,
    onSave: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isCopying by remember { mutableStateOf(false) }
    var copyingFileName by remember { mutableStateOf<String?>(null) }
    var copyProgress by remember { mutableStateOf(0f) }
    var downloadedModels by remember { mutableStateOf(setOf<String>()) }
    var importedModel by remember { mutableStateOf<String?>(null) }
    var copyJob by remember { mutableStateOf<Job?>(null) }
    var selectedModel by remember { mutableStateOf<String?>(null) }

    // Track the previous model to restore if the user cancels
    val previousModelName by remember { mutableStateOf(viewModel.selectedFileName.value) }
    val previousUri by remember { mutableStateOf(viewModel.selectedUri.value) }

    LaunchedEffect(Unit) {
        val existingModels = modelOptions.filter { model ->
            File(context.filesDir, "${model.name.lowercase().replace(" ", "_")}.gguf").exists()
        }.map { it.name }
        downloadedModels = downloadedModels + existingModels

        // If a model is already loaded, pre-select it
        val currentModel = viewModel.selectedFileName.value
        if (currentModel != null) {
            if (downloadedModels.contains(currentModel)) {
                selectedModel = currentModel
            } else if (File(context.filesDir, currentModel).exists()) {
                importedModel = currentModel
                selectedModel = currentModel
            }
        }
    }

    fun copySelectedFile() {
        viewModel.selectedUri.value?.let { uri ->
            val fileName = viewModel.selectedFileName.value ?: return@let
            val job = scope.launch {
                try {
                    isCopying = true
                    copyingFileName = fileName
                    copyProgress = 0f
                    val fileSize = context.contentResolver.openFileDescriptor(uri, "r")?.statSize ?: 0L
                    val internalFile = File(context.filesDir, fileName)
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { inputStream ->
                            FileOutputStream(internalFile).use { outputStream ->
                                val buffer = ByteArray(8192)
                                var bytesCopied = 0L
                                var bytesRead: Int
                                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                    outputStream.write(buffer, 0, bytesRead)
                                    bytesCopied += bytesRead
                                    if (fileSize > 0) {
                                        withContext(Dispatchers.Main) {
                                            copyProgress = bytesCopied.toFloat() / fileSize
                                        }
                                    }
                                }
                            }
                        }
                    }
                    importedModel = fileName
                    selectedModel = fileName
                } catch (e: Exception) {
                    viewModel.importError.value = "Error copying file: ${e.message}"
                    importedModel = null
                    selectedModel = previousModelName
                } finally {
                    isCopying = false
                    copyingFileName = null
                    copyProgress = 0f
                    copyJob = null
                }
            }
            copyJob = job
        }
    }

    fun cancelCopy() {
        copyJob?.let { job ->
            scope.launch {
                job.cancelAndJoin()
                File(context.filesDir, copyingFileName ?: "").delete() // Clean up partial file
                isCopying = false
                copyingFileName = null
                copyProgress = 0f
                copyJob = null
                viewModel.importError.value = "Import cancelled"
                // Restore the previous model state
                viewModel.selectedUri.value = previousUri
                viewModel.selectedFileName.value = previousModelName
                selectedModel = previousModelName
                importedModel = if (previousModelName != null && !downloadedModels.contains(previousModelName)) previousModelName else null
            }
        }
    }

    val isModelLoaded by remember {
        derivedStateOf {
            when {
                importedModel != null && File(context.filesDir, importedModel).exists() -> true
                downloadedModels.isNotEmpty() -> true
                else -> false
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.setModelLoaded(checkModelInLocalStorage(context))
    }

    LaunchedEffect(viewModel.selectedUri.value) {
        viewModel.selectedUri.value?.let {
            copySelectedFile()
        }
    }

    fun handleModelDownloadComplete(modelName: String) {
        downloadedModels = downloadedModels + modelName
        selectedModel = modelName
    }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFAF2FB)),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Color(0xFFD9BDFE)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_ai_chip),
                contentDescription = "AI Model",
                tint = PurplePrimary,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Choose your AI Model",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontSize = 24.sp,
                    color = Color(0xFF1C1B1F)
                )
            )
            Spacer(modifier = Modifier.height(32.dp))

            modelOptions.forEachIndexed { index, model ->
                ModelOptionItem(
                    model = model,
                    viewModel = viewModel,
                    downloadManager = downloadManager,
                    onDownloadStart = { /* No longer needed */ },
                    onProgressUpdate = { /* Track progress if needed */ },
                    onDownloadComplete = { handleModelDownloadComplete(model.name) },
                    isLastItem = index == modelOptions.lastIndex,
                    isSelected = selectedModel == model.name,
                    onSelect = { selectedModel = model.name }
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            Text(
                text = "or",
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFF1C1B1F).copy(alpha = 0.6f),
                modifier = Modifier.padding(vertical = 16.dp)
            )

            if (importedModel != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, Color(0xFFD9BDFE))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = CircleShape,
                                color = Color(0xFFD9BDFE),
                                modifier = Modifier.size(40.dp)
                            ) {
                                Image(
                                    painter = painterResource(id = R.drawable.ic_ai_chip_import_model),
                                    contentDescription = "Model Loaded",
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(8.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(
                                    text = "Model Loaded",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color(0xFF1C1B1F)
                                )
                                Text(
                                    text = importedModel ?: "",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color(0xFF1C1B1F).copy(alpha = 0.6f)
                                )
                            }
                        }
                        Checkbox(
                            checked = selectedModel == importedModel,
                            onCheckedChange = { isChecked ->
                                if (isChecked) {
                                    selectedModel = importedModel
                                } else {
                                    selectedModel = null
                                }
                            },
                            colors = CheckboxDefaults.colors(
                                checkedColor = PurplePrimary,
                                uncheckedColor = Color(0xFF1C1B1F).copy(alpha = 0.6f),
                                checkmarkColor = Color.White
                            ),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            } else if (isCopying && copyingFileName != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, PurplePrimary.copy(alpha = 0.12f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFFD9BDFE),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_ai_chip_import_model),
                                contentDescription = "Model Copying",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(8.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Model Copying",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color(0xFF1C1B1F)
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = copyingFileName ?: "",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color(0xFF1C1B1F).copy(alpha = 0.6f)
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${(copyProgress * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = PurplePrimary
                            )
                        }
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(40.dp)
                                .clickable { cancelCopy() }
                        ) {
                            CircularProgressIndicator(
                                progress = copyProgress,
                                modifier = Modifier.fillMaxSize(),
                                color = PurplePrimary,
                                strokeWidth = 4.dp
                            )
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cancel Import",
                                tint = PurplePrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            } else {
                OutlinedButton(
                    onClick = { onImportClick() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color(0xFFD9BDFE)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color(0xFFFAF2FB)
                    )
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = CircleShape,
                                color = Color(0xFFD9BDFE),
                                modifier = Modifier.size(40.dp)
                            ) {
                                Image(
                                    painter = painterResource(id = R.drawable.ic_ai_chip_import_model),
                                    contentDescription = "Import Model",
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(8.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(
                                    text = "Import Model",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Black
                                )
                                Text(
                                    text = "Please select a .gguf file",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Black
                                )
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = PurplePrimary,
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_upload),
                                contentDescription = "Upload",
                                tint = Color.White,
                                modifier = Modifier
                                    .size(18.dp)
                                    .padding(2.dp)
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = {
                    selectedModel?.let { modelName ->
                        val modelPath = File(context.filesDir, modelName).absolutePath
                        // Update viewModel state only when the user confirms the selection
                        viewModel.selectedFileName.value = modelName
                        viewModel.selectedUri.value = Uri.fromFile(File(modelPath))
                        onSave(modelPath, downloadedModels.contains(modelName))
                    }
                },
                enabled = isModelLoaded && selectedModel != null && !isCopying,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PurplePrimary,
                    disabledContainerColor = Color.Gray.copy(alpha = 0.3f)
                )
            ) {
                Text(
                    "Done",
                    color = if (isModelLoaded && selectedModel != null && !isCopying) Color.White else Color.Gray,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}

@Composable
private fun ModelOptionItem(
    model: Downloadable,
    viewModel: MainViewModel,
    downloadManager: DownloadManager,
    onDownloadStart: () -> Unit,
    onProgressUpdate: (Float) -> Unit,
    onDownloadComplete: () -> Unit,
    isLastItem: Boolean,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    var progress by remember { mutableStateOf(0f) }
    var isDownloading by remember { mutableStateOf(false) }
    var isDownloadComplete by remember { mutableStateOf(false) }
    var downloadId by remember { mutableStateOf<Long?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val modelFile = File(context.filesDir, "${model.name.lowercase().replace(" ", "_")}.gguf")
        isDownloadComplete = modelFile.exists()
    }

    fun handleSuccessfulDownload() {
        isDownloading = false
        isDownloadComplete = true
        downloadId = null
        onDownloadComplete()
    }

    fun cancelDownload() {
        downloadId?.let { id ->
            downloadManager.remove(id)
            isDownloading = false
            progress = 0f
            downloadId = null
            val modelFile = File(context.filesDir, "${model.name.lowercase().replace(" ", "_")}.gguf")
            modelFile.delete() // Clean up partial download
            viewModel.importError.value = "Download cancelled"
        }
    }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = when (model.name) {
                        "Orca Mini-3B", "Mistral-7B" -> Color(0xFFD9BDFE)
                        "Gemma 3-4B" -> Color(0xFF1D2E40)
                        else -> Color.Transparent
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    Image(
                        painter = painterResource(id = model.iconResId),
                        contentDescription = model.name,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(2.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = model.name,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Medium
                        ),
                        color = Color(0xFF1C1B1F)
                    )
                    Text(
                        text = "${model.size}GB",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF1C1B1F).copy(alpha = 0.6f)
                    )
                    if (isDownloading) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${(progress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = PurplePrimary
                        )
                    }
                }
            }
            if (isDownloadComplete) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { isChecked ->
                        if (isChecked) {
                            onSelect()
                        }
                    },
                    colors = CheckboxDefaults.colors(
                        checkedColor = PurplePrimary,
                        uncheckedColor = Color(0xFF1C1B1F).copy(alpha = 0.6f),
                        checkmarkColor = Color.White
                    ),
                    modifier = Modifier.size(24.dp)
                )
            } else if (isDownloading) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .clickable { cancelDownload() }
                ) {
                    CircularProgressIndicator(
                        progress = progress,
                        modifier = Modifier.fillMaxSize(),
                        color = PurplePrimary,
                        strokeWidth = 4.dp
                    )
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel Download",
                        tint = PurplePrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            } else {
                IconButton(
                    onClick = {
                        isDownloading = true
                        onDownloadStart()
                        downloadId = model.download(
                            viewModel = viewModel,
                            downloadManager = downloadManager,
                            scope = scope
                        ) { currentProgress ->
                            progress = currentProgress
                            onProgressUpdate(currentProgress)
                            if (currentProgress >= 1f) {
                                handleSuccessfulDownload()
                            }
                        }
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_download),
                        contentDescription = "Download",
                        tint = PurplePrimary
                    )
                }
            }
        }
        if (!isLastItem) {
            Divider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = Color(0xFF1C1B1F).copy(alpha = 0.12f)
            )
        }
    }
}
