package com.example.llama.component

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.util.Log
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llama.MainViewModel
import com.example.llama.R
import com.example.llama.model.Downloadable
import com.example.llama.ui.theme.Black
import com.example.llama.ui.theme.PurplePrimary
import com.example.llama.ui.theme.PurpleLight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay

private fun checkModelInLocalStorage(context: Context): Boolean {
    val filesDir = context.filesDir
    return filesDir.listFiles()?.any { file ->
        file.name.endsWith(".gguf", ignoreCase = true)
    } ?: false
}

private fun getModelFileName(modelName: String): String {
    return "${modelName.lowercase().replace(" ", "_")}.gguf"
}

@Composable
fun ModelImportCard(
    viewModel: MainViewModel,
    downloadManager: DownloadManager,
    modelOptions: List<Downloadable>,
    onImportClick: () -> Unit,
    onSave: (String, Boolean, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isCopying by remember { mutableStateOf(false) }
    var copyingFileName by remember { mutableStateOf<String?>(null) }
    var copyProgress by remember { mutableStateOf(0f) }
    var importedModel by remember { mutableStateOf<String?>(null) }
    var importedFileName by remember { mutableStateOf<String?>(null) }
    var copyJob by remember { mutableStateOf<Job?>(null) }
    var selectedModel by remember { mutableStateOf<String?>(null) }
    var hasUserSelectedModel by remember { mutableStateOf(false) }
    var shouldCopyFile by remember { mutableStateOf(false) }

    val previousModelName by remember { mutableStateOf(viewModel.selectedFileName.value) }
    val previousUri by remember { mutableStateOf(viewModel.selectedUri.value) }

    // Reset hasUserSelectedModel when the composable is recomposed (dialog opens)
    LaunchedEffect(Unit) {
        // Check if a model is already loaded
        val currentModelPath = viewModel.modelPath.value
        if (currentModelPath.isNotEmpty() && File(currentModelPath).exists() && viewModel.isModelLoaded.value) {
            val fileName = currentModelPath.substringAfterLast('/')
            importedModel = viewModel.getModelDisplayName(fileName)
            importedFileName = fileName
            selectedModel = importedModel
            hasUserSelectedModel = true
            viewModel.showImportSuccess.value = false // Allow new imports
            shouldCopyFile = false
            Log.d("ModelImportCard", "Initialized with modelPath: $currentModelPath, displayName=$importedModel")
        } else {
            importedModel = null
            importedFileName = null
            selectedModel = null
            hasUserSelectedModel = false
            viewModel.showImportSuccess.value = false
            shouldCopyFile = false
            Log.d("ModelImportCard", "No model loaded, initialized with empty state")
        }
        Log.d("ModelImportCard", "Initialized: downloadedModels=${viewModel.downloadedModels.value}, importedModel=$importedModel, selectedModel=$selectedModel, shouldCopyFile=$shouldCopyFile")
    }

    // Reset shouldCopyFile when the composable is disposed (dialog is closed)
    DisposableEffect(Unit) {
        onDispose {
            shouldCopyFile = false
            Log.d("ModelImportCard", "DisposableEffect: shouldCopyFile reset to false on dialog close")
        }
    }

    val undownloadedModels by remember(viewModel.downloadedModels) {
        derivedStateOf {
            modelOptions.filter { !viewModel.downloadedModels.value.contains(it.name) }
        }
    }

    fun copySelectedFile() {
        if (shouldCopyFile) {
            viewModel.selectedUri.value?.let { uri ->
                val rawFileName = viewModel.selectedFileName.value ?: return@let
                val internalFile = File(context.filesDir, rawFileName)

                // Check if file already exists and is not the current model
                if (internalFile.exists() && internalFile.absolutePath != viewModel.modelPath.value) {
                    Log.d("ModelImportCard", "File already exists: $rawFileName, using existing file")
                    importedModel = viewModel.getModelDisplayName(rawFileName)
                    importedFileName = rawFileName
                    selectedModel = importedModel
                    hasUserSelectedModel = true
                    viewModel.showImportSuccess.value = true
                    shouldCopyFile = false
                    viewModel.selectedUri.value = null
                    viewModel.selectedFileName.value = null
                    return@let
                }

                val job = scope.launch {
                    try {
                        isCopying = true
                        copyingFileName = rawFileName
                        copyProgress = 0f
                        val fileSize = context.contentResolver.openFileDescriptor(uri, "r")
                            ?.use { it.statSize } ?: 0L
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                                FileOutputStream(internalFile).use { outputStream ->
                                    val buffer = ByteArray(65536) // Increased buffer size to 64KB
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
                            } ?: throw Exception("Failed to open input stream for URI: $uri")
                        }
                        if (internalFile.exists()) {
                            importedModel = viewModel.getModelDisplayName(rawFileName)
                            importedFileName = rawFileName
                            selectedModel = importedModel
                            hasUserSelectedModel = true
                            viewModel.showImportSuccess.value = true
                            shouldCopyFile = false
                            viewModel.selectedUri.value = null
                            viewModel.selectedFileName.value = null
                            Log.d(
                                "ModelImportCard",
                                "File copied successfully: $rawFileName, path=${internalFile.absolutePath}, size=${internalFile.length()} bytes"
                            )
                        } else {
                            viewModel.importError.value = "Failed to copy model file: $rawFileName"
                            importedModel = null
                            importedFileName = null
                            selectedModel = previousModelName
                            hasUserSelectedModel = false
                            shouldCopyFile = false
                            Log.e("ModelImportCard", "File copy failed: $rawFileName")
                        }
                    } catch (e: Exception) {
                        viewModel.importError.value = "Error copying file: ${e.message}"
                        importedModel = null
                        importedFileName = null
                        selectedModel = previousModelName
                        hasUserSelectedModel = false
                        shouldCopyFile = false
                        Log.e("ModelImportCard", "File copy error: ${e.stackTraceToString()}")
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
    }

    fun cancelCopy() {
        copyJob?.let { job ->
            scope.launch {
                job.cancelAndJoin()
                File(context.filesDir, copyingFileName ?: "").delete()
                isCopying = false
                copyingFileName = null
                copyProgress = 0f
                copyJob = null
                viewModel.importError.value = "Import cancelled"
                viewModel.selectedUri.value = previousUri
                viewModel.selectedFileName.value = previousModelName
                selectedModel = previousModelName
                hasUserSelectedModel = false
                importedModel = if (previousModelName != null && !viewModel.downloadedModels.value.contains(previousModelName)) previousModelName else null
                importedFileName = if (previousModelName != null && !viewModel.downloadedModels.value.contains(previousModelName)) previousModelName else null
                shouldCopyFile = false
                Log.d("ModelImportCard", "Cancelled copy, restored state: selectedModel=$selectedModel, importedModel=$importedModel")
            }
        }
    }

    val isModelLoaded by remember(viewModel.downloadedModels, importedModel) {
        derivedStateOf {
            when {
                importedModel != null && File(context.filesDir, importedFileName ?: "").exists() -> true
                viewModel.downloadedModels.value.isNotEmpty() -> true
                else -> false
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.setModelLoaded(checkModelInLocalStorage(context))
    }

    // Handle file selection and prevent re-importing same file
    LaunchedEffect(viewModel.selectedUri.value, viewModel.selectedFileName.value) {
        if (viewModel.selectedUri.value != null && viewModel.selectedFileName.value != null && !viewModel.showImportSuccess.value) {
            val rawFileName = viewModel.selectedFileName.value
            if (rawFileName != null) {
                val internalFile = File(context.filesDir, rawFileName)
                val isDifferentFile = internalFile.absolutePath != viewModel.modelPath.value
                if (!internalFile.exists() || isDifferentFile) {
                    shouldCopyFile = true
                    copySelectedFile()
                } else {
                    viewModel.importError.value = "Model file already imported: $rawFileName"
                    viewModel.selectedUri.value = null
                    viewModel.selectedFileName.value = null
                    shouldCopyFile = false
                    importedModel = viewModel.getModelDisplayName(rawFileName)
                    importedFileName = rawFileName
                    selectedModel = importedModel
                    hasUserSelectedModel = true
                    viewModel.showImportSuccess.value = true
                }
            }
        } else {
            shouldCopyFile = false
        }
    }

    // Auto-dismiss success message after 3 seconds
    LaunchedEffect(viewModel.showImportSuccess.value) {
        if (viewModel.showImportSuccess.value) {
            delay(3000L)
            viewModel.showImportSuccess.value = false
            hasUserSelectedModel = false // Allow new imports after success message
        }
    }

    fun handleModelDownloadComplete(modelName: String) {
        viewModel.addDownloadedModel(modelName)
        selectedModel = modelName
        importedFileName = getModelFileName(modelName)
        hasUserSelectedModel = true
        viewModel.showImportSuccess.value = true
        Log.d("ModelImportCard", "Download complete: $modelName, downloadedModels=${viewModel.downloadedModels.value}")
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
            Spacer(modifier = Modifier.height(16.dp))

            // Display import error if present
            viewModel.importError.value?.takeIf { it.isNotEmpty() }?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }

            if (undownloadedModels.isNotEmpty()) {
                undownloadedModels.forEachIndexed { index, model ->
                    ModelOptionItem(
                        model = model,
                        viewModel = viewModel,
                        downloadManager = downloadManager,
                        onDownloadStart = { /* No longer needed */ },
                        onProgressUpdate = { /* Track progress if needed */ },
                        onDownloadComplete = { handleModelDownloadComplete(model.name) },
                        isLastItem = index == undownloadedModels.lastIndex,
                        isSelected = selectedModel == model.name,
                        onSelect = {
                            selectedModel = model.name
                            importedFileName = getModelFileName(model.name)
                            hasUserSelectedModel = true
                        }
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
                Text(
                    text = "or",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFF1C1B1F).copy(alpha = 0.6f),
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }

            if (isCopying && copyingFileName != null) {
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
                                    text = viewModel.getModelDisplayName(copyingFileName ?: ""),
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
            } else if (viewModel.showImportSuccess.value && importedModel != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, Color.Green.copy(alpha = 0.3f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color.Green.copy(alpha = 0.1f),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_check),
                                contentDescription = "Success",
                                tint = Color.Green,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(8.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Model Successfully Imported",
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
                    if (selectedModel != null && importedFileName != null) {
                        val file = File(context.filesDir, importedFileName!!)
                        Log.d("ModelImportCard", "Saving model: selectedModel=$selectedModel, fileName=$importedFileName, path=${file.absolutePath}, exists=${file.exists()}, size=${file.length()} bytes")
                        if (file.exists()) {
                            val absolutePath = file.absolutePath
                            val displayName = selectedModel!!
                            onSave(absolutePath, viewModel.downloadedModels.value.contains(selectedModel), displayName)
                            // Reset transient state
                            shouldCopyFile = false
                        } else {
                            viewModel.importError.value = "Selected model file does not exist: $importedFileName"
                            Log.e("ModelImportCard", "Model file does not exist: $importedFileName")
                        }
                    }
                },
                enabled = hasUserSelectedModel && !isCopying,
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
                    color = if (hasUserSelectedModel && !isCopying) Color.White else Color.Gray,
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
    val isDownloadComplete by remember(viewModel.downloadedModels) {
        derivedStateOf {
            val complete = viewModel.downloadedModels.value.contains(model.name)
            Log.d("ModelOptionItem", "isDownloadComplete for ${model.name}: $complete, downloadedModels=${viewModel.downloadedModels.value}")
            complete
        }
    }
    var downloadId by remember { mutableStateOf<Long?>(null) }
    val context = LocalContext.current
    var hasUserSelectedModel by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(isDownloadComplete) {
        Log.d("ModelOptionItem", "isDownloadComplete for ${model.name}: $isDownloadComplete")
    }

    fun handleSuccessfulDownload(downloadedFileUri: Uri?) {
        scope.launch {
            try {
                val targetFile = File(context.filesDir, getModelFileName(model.name))
                var totalBytesCopied = 0L
                if (downloadedFileUri != null) {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(downloadedFileUri)?.use { inputStream ->
                            FileOutputStream(targetFile).use { outputStream ->
                                val buffer = ByteArray(65536) // Increased buffer size to 64KB
                                var bytesRead: Int
                                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                    outputStream.write(buffer, 0, bytesRead)
                                    totalBytesCopied += bytesRead
                                }
                            }
                        } ?: throw Exception("Failed to open input stream for downloaded file")
                    }
                    if (targetFile.exists() && totalBytesCopied > 0) {
                        Log.d("ModelOptionItem", "File copied to private storage: ${targetFile.absolutePath}, exists=${targetFile.exists()}, size=$totalBytesCopied bytes")
                    } else {
                        throw Exception("File copy resulted in an empty file: ${targetFile.absolutePath}")
                    }
                } else {
                    throw Exception("Downloaded file URI is null")
                }
                isDownloading = false
                downloadId = null
                onDownloadComplete()
                hasUserSelectedModel = true
            } catch (e: Exception) {
                viewModel.importError.value = "Error copying downloaded file: ${e.message}"
                Log.e("ModelOptionItem", "File copy error: ${e.stackTraceToString()}")
                isDownloading = false
                downloadId = null
                val targetFile = File(context.filesDir, getModelFileName(model.name))
                if (targetFile.exists()) {
                    targetFile.delete()
                }
            }
        }
    }

    fun cancelDownload() {
        downloadId?.let { id ->
            downloadManager.remove(id)
            isDownloading = false
            progress = 0f
            downloadId = null
            val modelFile = File(context.filesDir, getModelFileName(model.name))
            modelFile.delete()
            val tempFile = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), getModelFileName(model.name))
            tempFile.delete()
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
                        scope.launch {
                            val request = DownloadManager.Request(model.source).apply {
                                setTitle("Downloading ${model.name}")
                                setDescription("Downloading AI model ${model.name}")
                                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                setDestinationInExternalFilesDir(context, android.os.Environment.DIRECTORY_DOWNLOADS, getModelFileName(model.name))
                            }
                            downloadId = downloadManager.enqueue(request)

                            var isDownloadingLocal = true
                            var totalBytes = 0L
                            while (isDownloadingLocal) {
                                val query = DownloadManager.Query().setFilterById(downloadId ?: return@launch)
                                downloadManager.query(query)?.use { cursor ->
                                    if (cursor.moveToFirst()) {
                                        val bytesDownloadedIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                                        val bytesTotalIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                                        val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                                        val localUriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)

                                        if (bytesDownloadedIndex >= 0 && bytesTotalIndex >= 0) {
                                            val bytesDownloaded = cursor.getLong(bytesDownloadedIndex)
                                            totalBytes = cursor.getLong(bytesTotalIndex)
                                            if (totalBytes > 0) {
                                                progress = bytesDownloaded.toFloat() / totalBytes
                                                onProgressUpdate(progress)
                                            }
                                        } else {
                                            Log.e("ModelOptionItem", "Missing columns for progress: bytesDownloadedIndex=$bytesDownloadedIndex, bytesTotalIndex=$bytesTotalIndex")
                                        }

                                        if (statusIndex >= 0) {
                                            val status = cursor.getInt(statusIndex)
                                            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                                                if (localUriIndex >= 0) {
                                                    val downloadedUri = Uri.parse(cursor.getString(localUriIndex))
                                                    Log.d("ModelOptionItem", "Download successful, URI: $downloadedUri, totalBytes=$totalBytes")
                                                    handleSuccessfulDownload(downloadedUri)
                                                    isDownloadingLocal = false
                                                } else {
                                                    Log.e("ModelOptionItem", "Missing COLUMN_LOCAL_URI column, cannot retrieve downloaded file URI")
                                                    viewModel.importError.value = "Download completed but failed to retrieve file URI for ${model.name}"
                                                    isDownloading = false
                                                    downloadId = null
                                                    isDownloadingLocal = false
                                                }
                                            } else if (status == DownloadManager.STATUS_FAILED) {
                                                viewModel.importError.value = "Download failed for ${model.name}"
                                                Log.e("ModelOptionItem", "Download failed for ${model.name}")
                                                isDownloading = false
                                                downloadId = null
                                                isDownloadingLocal = false
                                            }
                                        } else {
                                            Log.e("ModelOptionItem", "Missing COLUMN_STATUS column, cannot determine download status")
                                            viewModel.importError.value = "Failed to determine download status for ${model.name}"
                                            isDownloading = false
                                            downloadId = null
                                            isDownloadingLocal = false
                                        }
                                    } else {
                                        Log.e("ModelOptionItem", "Download ID ${downloadId} not found in DownloadManager")
                                        viewModel.importError.value = "Download ID not found for ${model.name}"
                                        isDownloading = false
                                        downloadId = null
                                        isDownloadingLocal = false
                                    }
                                } ?: run {
                                    Log.e("ModelOptionItem", "DownloadManager query returned null for download ID ${downloadId}")
                                    viewModel.importError.value = "Failed to query download status for ${model.name}"
                                    isDownloading = false
                                    downloadId = null
                                    isDownloadingLocal = false
                                }
                                delay(500L)
                            }
                        }
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = PurplePrimary,
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_download),
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
        if (!isLastItem) {
            Divider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = Color(0xFF1C1B1F).copy(alpha = 0.12f)
            )
        }
    }
}
