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
import android.util.Log
import android.os.Environment

private fun checkModelInLocalStorage(context: Context): Boolean {
    val filesDir = context.filesDir
    return filesDir.listFiles()?.any { file ->
        file.name.endsWith(".gguf", ignoreCase = true)
    } ?: false
}

private fun getModelFileName(modelName: String): String {
    return "${modelName.lowercase().replace(" ", "_")}.gguf"
}

data class ModelInfo(
    val name: String,
    val file: File,
    val isDownloaded: Boolean,
    val iconResId: Int? = null,
    val size: String? = null
)

@Composable
fun ModelImportCard(
    viewModel: MainViewModel,
    downloadManager: DownloadManager,
    modelOptions: List<Downloadable>,
    onImportClick: () -> Unit,
    onSave: (String, Boolean, String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isCopying by remember { mutableStateOf(false) }
    var copyingFileName by remember { mutableStateOf<String?>(null) }
    var copyProgress by remember { mutableStateOf(0f) }
    var copyJob by remember { mutableStateOf<Job?>(null) }
    var selectedModel by remember { mutableStateOf<ModelInfo?>(null) }
    var shouldCopyFile by remember { mutableStateOf(false) }

    // Local state to control what the UI displays (null = show "Import Model" button)
    var displayedModel by remember { mutableStateOf<ModelInfo?>(null) }

    // Reset displayedModel when the dialog opens
    LaunchedEffect(Unit) {
        displayedModel = null
        Log.d("ModelImportCard", "Dialog opened, reset displayedModel to null")
    }

    // Reset displayedModel and call onDismiss when the dialog is dismissed
    DisposableEffect(Unit) {
        onDispose {
            displayedModel = null
            onDismiss()
            Log.d("ModelImportCard", "Dialog dismissed, reset displayedModel and called onDismiss")
        }
    }

    // Handle file copying for imported models
    fun copySelectedFile() {
        if (shouldCopyFile) {
            viewModel.selectedUri.value?.let { uri ->
                val rawFileName = viewModel.selectedFileName.value ?: return@let
                val internalFile = File(context.filesDir, rawFileName)

                // Check if file already exists
                if (internalFile.exists()) {
                    Log.d("ModelImportCard", "File already exists: $rawFileName, using existing file")
                    val displayName = viewModel.getModelDisplayName(rawFileName)
                    val modelInfo = ModelInfo(
                        name = displayName,
                        file = internalFile,
                        isDownloaded = false,
                        iconResId = R.drawable.ic_ai_chip_import_model
                    )
                    displayedModel = modelInfo
                    selectedModel = modelInfo
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
                                    val buffer = ByteArray(65536)
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
                            val displayName = viewModel.getModelDisplayName(rawFileName)
                            val modelInfo = ModelInfo(
                                name = displayName,
                                file = internalFile,
                                isDownloaded = false,
                                iconResId = R.drawable.ic_ai_chip_import_model
                            )
                            displayedModel = modelInfo
                            selectedModel = modelInfo
                            viewModel.showImportSuccess.value = true
                            shouldCopyFile = false
                            viewModel.selectedUri.value = null
                            viewModel.selectedFileName.value = null
                            Log.d(
                                "ModelImportCard",
                                "File copied successfully: $rawFileName, path=${internalFile.absolutePath}"
                            )
                        } else {
                            viewModel.importError.value = "Failed to copy model file: $rawFileName"
                            shouldCopyFile = false
                        }
                    } catch (e: Exception) {
                        viewModel.importError.value = "Error copying file: ${e.message}"
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
                viewModel.selectedUri.value = null
                viewModel.selectedFileName.value = null
                shouldCopyFile = false
                displayedModel = null // Reset displayed model on cancel
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.setModelLoaded(checkModelInLocalStorage(context))
    }

    LaunchedEffect(viewModel.selectedUri.value, viewModel.selectedFileName.value) {
        if (viewModel.selectedUri.value != null && viewModel.selectedFileName.value != null && !viewModel.showImportSuccess.value) {
            val rawFileName = viewModel.selectedFileName.value
            if (rawFileName != null) {
                val internalFile = File(context.filesDir, rawFileName)
                if (!internalFile.exists()) {
                    shouldCopyFile = true
                    copySelectedFile()
                } else {
                    viewModel.importError.value = "Model file already imported: $rawFileName"
                    viewModel.selectedUri.value = null
                    viewModel.selectedFileName.value = null
                    shouldCopyFile = false
                    val displayName = viewModel.getModelDisplayName(rawFileName)
                    val modelInfo = ModelInfo(
                        name = displayName,
                        file = internalFile,
                        isDownloaded = false,
                        iconResId = R.drawable.ic_ai_chip_import_model
                    )
                    displayedModel = modelInfo
                    selectedModel = modelInfo
                    viewModel.showImportSuccess.value = true
                }
            }
        } else {
            shouldCopyFile = false
        }
    }

    LaunchedEffect(viewModel.showImportSuccess.value) {
        if (viewModel.showImportSuccess.value) {
            delay(3000L)
            viewModel.showImportSuccess.value = false
        }
    }

    fun handleModelDownloadComplete(modelName: String, downloadedFile: File) {
        viewModel.addDownloadedModel(modelName)
        Log.d("ModelImportCard", "Download complete: $modelName, Path=${downloadedFile.absolutePath}")
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

            viewModel.importError.value?.takeIf { it.isNotEmpty() }?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }

            if (modelOptions.isNotEmpty()) {
                modelOptions.forEachIndexed { index, model ->
                    ModelOptionItem(
                        model = model,
                        viewModel = viewModel,
                        downloadManager = downloadManager,
                        onDownloadStart = {},
                        onProgressUpdate = {},
                        onDownloadComplete = { downloadedFile ->
                            handleModelDownloadComplete(model.name, downloadedFile)
                        },
                        isLastItem = index == modelOptions.lastIndex,
                        isSelected = selectedModel?.name == model.name,
                        onSelect = {
                            val downloadedFile = File(
                                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                                getModelFileName(model.name)
                            )
                            val modelInfo = ModelInfo(
                                name = model.name,
                                file = downloadedFile,
                                isDownloaded = true,
                                iconResId = model.iconResId,
                                size = model.size.toString()
                            )
                            selectedModel = modelInfo
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
                            Text(
                                text = viewModel.getModelDisplayName(copyingFileName ?: ""),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFF1C1B1F).copy(alpha = 0.6f)
                            )
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
            } else if (displayedModel != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .clickable { onImportClick() },
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
                                    painter = painterResource(
                                        id = displayedModel!!.iconResId ?: R.drawable.ic_ai_chip_import_model
                                    ),
                                    contentDescription = displayedModel!!.name,
                                    modifier = Modifier
                                        .padding(4.dp)
                                        .fillMaxSize()
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Imported Model",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color(0xFF1C1B1F)
                                )
                                Text(
                                    text = displayedModel!!.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color(0xFF1C1B1F).copy(alpha = 0.6f)
                                )
                            }
                        }
                        Checkbox(
                            checked = selectedModel?.file?.absolutePath == displayedModel!!.file.absolutePath,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    selectedModel = displayedModel
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
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = {
                    selectedModel?.let { model ->
                        Log.d(
                            "ModelImportCard",
                            "Sending model to load_model: Name=${model.name}, Path=${model.file.absolutePath}, IsDownloaded=${model.isDownloaded}, Exists=${model.file.exists()}, Size=${model.file.length()} bytes"
                        )
                        if (model.file.exists()) {
                            onSave(model.file.absolutePath, model.isDownloaded, model.name)
                        } else {
                            viewModel.importError.value = "Selected model file does not exist: ${model.file.absolutePath}"
                        }
                    }
                },
                enabled = selectedModel != null && !isCopying,
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
                    color = if (selectedModel != null && !isCopying) Color.White else Color.Gray,
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
    onDownloadComplete: (File) -> Unit,
    isLastItem: Boolean,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    var progress by remember { mutableStateOf(0f) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadId by remember { mutableStateOf<Long?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val downloadedFile = File(
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
        getModelFileName(model.name)
    )

    var isDownloadComplete by remember { mutableStateOf(viewModel.downloadedModels.value.contains(model.name)) }

    LaunchedEffect(Unit) {
        while (true) {
            if (isDownloadComplete) {
                if (!downloadedFile.exists()) {
                    viewModel.removeDownloadedModel(model.name)
                    isDownloadComplete = false
                    Log.d("ModelOptionItem", "File no longer exists: ${downloadedFile.absolutePath}, marking as not downloaded")
                }
            }
            delay(5000L)
        }
    }

    LaunchedEffect(viewModel.downloadedModels.value) {
        isDownloadComplete = viewModel.downloadedModels.value.contains(model.name)
    }

    fun handleSuccessfulDownload(downloadedFileUri: Uri?) {
        scope.launch {
            try {
                if (downloadedFileUri != null) {
                    if (downloadedFile.exists()) {
                        Log.d("ModelOptionItem", "File downloaded to: ${downloadedFile.absolutePath}, Size=${downloadedFile.length()} bytes")
                        isDownloading = false
                        downloadId = null
                        isDownloadComplete = true
                        onDownloadComplete(downloadedFile)
                    } else {
                        throw Exception("Downloaded file does not exist at expected location: ${downloadedFile.absolutePath}")
                    }
                } else {
                    throw Exception("Downloaded file URI is null")
                }
            } catch (e: Exception) {
                viewModel.importError.value = "Error processing downloaded file: ${e.message}"
                isDownloading = false
                downloadId = null
                isDownloadComplete = false
                viewModel.removeDownloadedModel(model.name)
                if (downloadedFile.exists()) {
                    val deleted = downloadedFile.delete()
                    Log.d("ModelOptionItem", "Deleted failed download file: ${downloadedFile.absolutePath}, Success=$deleted")
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
            isDownloadComplete = false
            viewModel.removeDownloadedModel(model.name)
            if (downloadedFile.exists()) {
                val deleted = downloadedFile.delete()
                Log.d("ModelOptionItem", "Deleted temporary file on cancel: ${downloadedFile.absolutePath}, Success=$deleted")
            }
            viewModel.importError.value = "Download canceled"
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
                        "Qwen 2.5-0.5B" -> Color.White
                        "Arcee-lite 1.78B" -> Color.Black
                        "Orca Mini-3B" -> Color(0xFFD9BDFE)
                        else -> Color(0xFFD9BDFE)
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    Image(
                        painter = painterResource(id = model.iconResId),
                        contentDescription = model.name,
                        modifier = Modifier
                            .padding(
                                start = 4.dp,
                                top = 4.dp,
                                end = 4.dp,
                                bottom = if (model.name == "Arcee-lite 1.78B") 12.dp else 4.dp
                            )
                            .fillMaxSize()
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
            if (isDownloadComplete && downloadedFile.exists()) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { checked ->
                        if (checked) {
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
                                setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, getModelFileName(model.name))
                                setAllowedOverMetered(true)
                                setAllowedOverRoaming(true)
                            }
                            downloadId = downloadManager.enqueue(request)

                            var isDownloadingLocal = true
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
                                            val totalBytes = cursor.getLong(bytesTotalIndex)
                                            if (totalBytes > 0) {
                                                progress = bytesDownloaded.toFloat() / totalBytes
                                                onProgressUpdate(progress)
                                            }
                                        }

                                        if (statusIndex >= 0) {
                                            val status = cursor.getInt(statusIndex)
                                            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                                                if (localUriIndex >= 0) {
                                                    val downloadedUri = Uri.parse(cursor.getString(localUriIndex))
                                                    handleSuccessfulDownload(downloadedUri)
                                                    isDownloadingLocal = false
                                                } else {
                                                    viewModel.importError.value = "Download completed but failed to retrieve file URI for ${model.name}"
                                                    isDownloading = false
                                                    downloadId = null
                                                    isDownloadingLocal = false
                                                }
                                            } else if (status == DownloadManager.STATUS_FAILED) {
                                                viewModel.importError.value = "Download failed for ${model.name}"
                                                isDownloading = false
                                                downloadId = null
                                                isDownloadingLocal = false
                                            }
                                        }
                                    } else {
                                        viewModel.importError.value = "Download ID not found for ${model.name}"
                                        isDownloading = false
                                        downloadId = null
                                        isDownloadingLocal = false
                                    }
                                } ?: run {
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
                            contentDescription = "Download",
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
