package com.example.llama

import android.app.DownloadManager
import android.content.SharedPreferences
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavHostController
import com.example.llama.component.ModelImportCard
import com.example.llama.component.NameFieldComponent
import com.example.llama.component.ProgressComponent
import com.example.llama.model.Downloadable
import com.example.llama.ui.theme.PurpleHeading
import com.example.llama.ui.theme.PurpleLabel
import com.example.llama.ui.theme.PurplePrimary
import com.example.llama.ui.theme.White
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    navController: NavHostController,
    viewModel: MainViewModel,
    downloadManager: DownloadManager,
    modelOptions: List<Downloadable>,
    isFirstLaunch: Boolean,
    prefs: SharedPreferences
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(false) }
    var copyProgress by remember { mutableStateOf(0f) } // Progress from 0.0 to 1.0
    var showModelDialog by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri ->
            uri?.let {
                try {
                    val fileName =
                        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val displayNameIndex =
                                    cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.DISPLAY_NAME)
                                if (displayNameIndex != -1) cursor.getString(displayNameIndex) else null
                            } else null
                        } ?: uri.toString().substringAfterLast('/').takeIf { it.isNotEmpty() }
                        ?: "default_model.gguf"

                    if (fileName.endsWith(".gguf", ignoreCase = true)) {
                        isLoading = true
                        scope.launch {
                            try {
                                val fileSize =
                                    context.contentResolver.openFileDescriptor(uri, "r")?.statSize
                                        ?: 0L
                                val internalFile = File(context.filesDir, fileName)

                                withContext(Dispatchers.IO) {
                                    context.contentResolver.openInputStream(uri)
                                        ?.use { inputStream ->
                                            FileOutputStream(internalFile).use { outputStream ->
                                                val buffer = ByteArray(8192)
                                                var bytesCopied = 0L
                                                var bytesRead: Int
                                                while (inputStream.read(buffer)
                                                        .also { bytesRead = it } != -1
                                                ) {
                                                    outputStream.write(buffer, 0, bytesRead)
                                                    bytesCopied += bytesRead
                                                    if (fileSize > 0) {
                                                        copyProgress =
                                                            bytesCopied.toFloat() / fileSize
                                                    }
                                                }
                                            }
                                        } ?: throw Exception("Failed to open input stream from Uri")
                                }

                                Log.d(
                                    "SetupScreen",
                                    "File copied to internal storage: ${internalFile.absolutePath}"
                                )

                                viewModel.setModelPath(internalFile.absolutePath)
                                while (viewModel.isLoadingModel.value) {
                                    kotlinx.coroutines.delay(100)
                                }
                                if (viewModel.importError.value.isEmpty()) {
                                    viewModel.savePrefs(prefs)
                                    Log.d(
                                        "SetupScreen",
                                        "Model loaded successfully, navigating to chat"
                                    )
                                    navController.navigate("chat") {
                                        popUpTo("setup") { inclusive = true }
                                    }
                                } else {
                                    Log.e(
                                        "SetupScreen",
                                        "Model load failed: ${viewModel.importError.value}"
                                    )
                                }
                            } catch (e: Exception) {
                                viewModel.importError.value =
                                    "Error copying/loading file: ${e.message}"
                                Log.e("SetupScreen", "Error in process: ${e.stackTraceToString()}")
                            } finally {
                                isLoading = false
                                copyProgress = 0f
                            }
                        }
                    } else {
                        viewModel.importError.value = "Please select a .gguf file"
                        Log.d("SetupScreen", "Invalid file selected: $fileName")
                    }
                } catch (e: Exception) {
                    viewModel.importError.value = "Error initiating file copy: ${e.message}"
                    Log.e("SetupScreen", "Initial error: ${e.stackTraceToString()}")
                }
            } ?: run {
                viewModel.importError.value = "No file selected"
                Log.d("SetupScreen", "File picker returned null URI")
            }
        }
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 100.dp, start = 18.dp, end = 18.dp),
        verticalArrangement = Arrangement.Top, // Changed from Center to Top
        horizontalAlignment = Alignment.Start  // Changed from CenterHorizontally to Start
    ) {
        Column(
            horizontalAlignment = Alignment.Start, // Changed from CenterHorizontally to Start
            modifier = Modifier
                .padding(bottom = 16.dp)
                .fillMaxWidth()
        ) {
            // Custom font for "Welcome!"
            val customFont = FontFamily(Font(R.font.roboto_serif, FontWeight.Bold))
            Text(
                text = "Welcome!",
                style = TextStyle(
                    fontFamily = customFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 70.sp, // Increased from 28.sp to 40.sp
                    color = PurpleHeading
                ),
                modifier = Modifier.padding(bottom = 8.dp),
                textAlign = TextAlign.Start // Ensure text starts from the left
            )
            Text(
                text = "Let's set up your AI Assistant",
                style = MaterialTheme.typography.titleLarge.copy(
                    color = PurpleHeading,
                    fontSize = 28.sp // Increased from 20.sp to 24.sp
                ),
                textAlign = TextAlign.Start // Ensure text starts from the left
            )
        }

        Spacer(modifier = Modifier.height(32.dp)) // Added more space after the welcome text

        // NameFieldComponent with badge-like placeholder behavior
        var isUserNameFocused by remember { mutableStateOf(false) }
        var isAssistantNameFocused by remember { mutableStateOf(false) }

        OutlinedTextField(
            value = viewModel.userName.value,
            onValueChange = { viewModel.userName.value = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)  // Changed from 16.dp to match card
                .onFocusChanged { focusState ->
                    isUserNameFocused = focusState.isFocused
                },
            label = if (isUserNameFocused) {
                {
                    Text(
                        "Your Name",
                        color = Color(0xFF6750A4),
                        modifier = Modifier
                            .background(
                                color = Color(0xFFFEF7FF),
                                shape = RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            } else null,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF6750A4),
                unfocusedBorderColor = Color(0xFFCAC4D0),
            ),
            shape = RoundedCornerShape(5.dp),
            singleLine = true,
            placeholder = if (!isUserNameFocused) {
                { Text("Your Name", color = Color(0xFF6750A4).copy(alpha = 0.6f)) }
            } else null
        )

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = viewModel.assistantName.value,
            onValueChange = { viewModel.assistantName.value = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .onFocusChanged { focusState ->
                    isAssistantNameFocused = focusState.isFocused
                },
            label = if (isAssistantNameFocused) {
                {
                    Text(
                        "Assistant Name",
                        color = Color(0xFF6750A4),
                        modifier = Modifier
                            .background(
                                color = Color(0xFFFEF7FF),
                                shape = RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            } else null,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF6750A4),
                unfocusedBorderColor = Color(0xFFCAC4D0),
            ),
            shape = RoundedCornerShape(5.dp),
            singleLine = true,
            placeholder = if (!isAssistantNameFocused) {
                { Text("Assistant Name", color = Color(0xFF6750A4).copy(alpha = 0.6f)) }
            } else null
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Model Selection Card with Pop-up
        var showModelDialog by remember { mutableStateOf(false) }
        // Replace the Card section with:
        Card(
            modifier = Modifier
                .fillMaxWidth()

                .padding(horizontal = 4.dp)  // Added horizontal padding
                .height(72.dp) // Increased height for better spacing
                .clickable { showModelDialog = true },
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween, // This ensures proper spacing
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp) // Increased horizontal padding
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_ai_chip),
                        contentDescription = "AI Chip",
                        tint = PurplePrimary,
                        modifier = Modifier.size(32.dp) // Increased icon size
                    )
                    Spacer(modifier = Modifier.width(12.dp)) // Increased spacing between icon and text
                    Text(
                        text = "Choose Your AI Model",
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = PurplePrimary,
                            fontSize = 16.sp
                        )
                    )
                }

                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = PurplePrimary,
                    modifier = Modifier.size(24.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_arrow_right),
                            contentDescription = "Arrow Right",
                            tint = White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        // Model Dialog with Blur Effect
        if (showModelDialog) {
            Dialog(
                onDismissRequest = { showModelDialog = false },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f)) // Semi-transparent black for blur effect
                ) {
                    ModelImportCard(
                        viewModel = viewModel,
                        downloadManager = downloadManager,
                        modelOptions = modelOptions,
                        onImportClick = { filePickerLauncher.launch("*/*") },
                        onSave = {
                            viewModel.savePrefs(prefs)
                            if (isFirstLaunch) {
                                navController.navigate("chat") {
                                    popUpTo("setup") { inclusive = true }
                                }
                            }
                            showModelDialog = false
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(100.dp))  // Increased from 24.dp

        // Done Button
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Button(
                onClick = {
                    viewModel.savePrefs(prefs)
                    if (isFirstLaunch) {
                        navController.navigate("chat") {
                            popUpTo("setup") { inclusive = true }
                        }
                    }
                },
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.LightGray,
                    contentColor = PurplePrimary
                ),
                modifier = Modifier
                    .width(150.dp)
                    .height(48.dp)
            ) {
                Text("Done", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Progress and Error
        ProgressComponent(
            progress = copyProgress,
            isLoading = isLoading || viewModel.isLoadingModel.value,
            modifier = Modifier.fillMaxWidth()
        )

        if (viewModel.importError.value.isEmpty()) {
            Text(
                text = viewModel.importError.value,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}
