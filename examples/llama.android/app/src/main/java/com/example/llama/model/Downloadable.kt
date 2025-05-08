package com.example.llama.model

import android.app.DownloadManager
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.database.getLongOrNull
import androidx.core.net.toUri
import com.example.llama.MainViewModel
import com.example.llama.ui.theme.PurplePrimary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class Downloadable(
    val name: String,
    val source: Uri,
    val destination: File,
    val size: Float, // Change from String to Float
    val iconResId: Int // Resource ID for the model icon
) {
    companion object {
        private val TAG: String = Downloadable::class.java.simpleName

        sealed interface State {
            data object Ready : State
            data class Downloading(val id: Long) : State
            data class Downloaded(val downloadable: Downloadable) : State
            data class Error(val message: String) : State
        }

        @Composable
        fun CustomProgressBar(
            progress: Float,
            modifier: Modifier = Modifier
        ) {
            Box(
                modifier = modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .background(
                        color = Color(0xFFDDA0DD).copy(alpha = 0.3f), // Light purple background
                        shape = MaterialTheme.shapes.medium
                    )
                    .clip(MaterialTheme.shapes.medium)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .height(24.dp)
                        .background(
                            color = PurplePrimary, // Dark purple progress
                            shape = MaterialTheme.shapes.medium
                        )
                )
                Text(
                    text = "Downloading... ${(progress * 100).toInt()}%",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                )
            }
        }

        @Composable
        fun DownloadButton(
            viewModel: MainViewModel,
            downloadManager: DownloadManager,
            item: Downloadable,
            onDownloadComplete: (String) -> Unit = {},
            modifier: Modifier = Modifier
        ) {
            var status by remember {
                mutableStateOf<State>(
                    if (item.destination.exists()) State.Downloaded(item) else State.Ready
                )
            }
            var progress by remember { mutableDoubleStateOf(0.0) }

            LaunchedEffect(status) {
                if (status is State.Downloading) {
                    val downloading = status as State.Downloading
                    while (true) {
                        val cursor = downloadManager.query(DownloadManager.Query().setFilterById(downloading.id))
                        if (cursor == null) {
                            android.util.Log.e(TAG, "DownloadManager query returned null")
                            status = State.Error("DownloadManager query failed")
                            return@LaunchedEffect
                        }
                        if (!cursor.moveToFirst() || cursor.count < 1) {
                            cursor.close()
                            android.util.Log.i(TAG, "Download canceled or not found")
                            status = State.Ready
                            return@LaunchedEffect
                        }
                        val bytesDownloaded = cursor.getLongOrNull(
                            cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                        ) ?: 0L
                        val totalBytes = cursor.getLongOrNull(
                            cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                        ) ?: 1L
                        cursor.close()
                        progress = (bytesDownloaded * 1.0) / totalBytes
                        if (bytesDownloaded == totalBytes) {
                            status = State.Downloaded(item)
                            return@LaunchedEffect
                        }
                        delay(1000L)
                    }
                }
            }

            Row(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(id = item.iconResId),
                    contentDescription = item.name,
                    tint = Color.Unspecified, // Preserve original icon colors
                    modifier = Modifier
                        .size(40.dp)
                        .padding(end = 16.dp)
                )
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFF800080) // Dark purple
                    )
                    Text(
                        text = item.size.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF800080).copy(alpha = 0.7f)
                    )
                }
                if (status is State.Downloading) {
                    CustomProgressBar(
                        progress = progress.toFloat(),
                        modifier = Modifier
                            .width(100.dp)
                            .padding(start = 16.dp)
                    )
                } else {
                    Button(
                        onClick = {
                            when (val currentStatus = status) {
                                is State.Downloaded -> {
                                    viewModel.load(item.destination.path)
                                    onDownloadComplete(item.destination.path)
                                }
                                is State.Downloading -> {
                                    // Do nothing while downloading
                                }
                                else -> {
                                    item.destination.delete()
                                    val request = DownloadManager.Request(item.source).apply {
                                        setTitle("Downloading ${item.name}")
                                        setDescription("Model: ${item.name}")
                                        setAllowedNetworkTypes(
                                            DownloadManager.Request.NETWORK_WIFI or
                                                DownloadManager.Request.NETWORK_MOBILE
                                        )
                                        setNotificationVisibility(
                                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                                        )
                                        setDestinationUri(item.destination.toUri())
                                    }
                                    viewModel.log("Saving ${item.name} to ${item.destination.path}")
                                    val downloadId = downloadManager.enqueue(request)
                                    status = State.Downloading(downloadId)
                                }
                            }
                        },
                        enabled = status !is State.Downloading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PurplePrimary,
                            contentColor = Color.White
                        ),
                        modifier = Modifier.padding(start = 16.dp)
                    ) {
                        Text(
                            when (status) {
                                is State.Downloaded -> "Load"
                                is State.Ready -> "Download"
                                is State.Error -> "Retry"
                                is State.Downloading -> ""
                            }
                        )
                    }
                }
            }
        }
    }

    fun download(
        viewModel: MainViewModel,
        downloadManager: DownloadManager,
        scope: CoroutineScope,
        onProgressUpdate: (Float) -> Unit
    ): Long {
        // Set destination to app's internal storage
        val internalDestination = File(destination.parent, name.lowercase().replace(" ", "_") + ".gguf")
        internalDestination.delete()

        val request = DownloadManager.Request(source).apply {
            setTitle("Downloading $name")
            setDescription("Model: $name")
            setAllowedNetworkTypes(
                DownloadManager.Request.NETWORK_WIFI or
                    DownloadManager.Request.NETWORK_MOBILE
            )
            setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            setDestinationUri(internalDestination.toUri())
        }

        viewModel.log("Saving $name to ${internalDestination.path}")
        val downloadId = downloadManager.enqueue(request)

        // Use a coroutine to track progress
        scope.launch(Dispatchers.IO) {
            var downloading = true
            while (downloading && isActive) {
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = downloadManager.query(query)

                if (cursor.moveToFirst()) {
                    val downloadedIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    val totalIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)

                    val bytesDownloaded = if (downloadedIndex >= 0) cursor.getLong(downloadedIndex) else 0L
                    val bytesTotal = if (totalIndex >= 0) cursor.getLong(totalIndex) else 1L

                    if (bytesTotal > 0) {
                        val progress = bytesDownloaded.toFloat() / bytesTotal
                        withContext(Dispatchers.Main) {
                            onProgressUpdate(progress)
                        }

                        if (bytesDownloaded == bytesTotal) {
                            downloading = false
                            withContext(Dispatchers.Main) {
                                viewModel.setModelPath(internalDestination.absolutePath)
                            }
                        }
                    }
                }
                cursor.close()
                delay(100)
            }
        }

        return downloadId
    }
}
