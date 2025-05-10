package com.example.llama

import android.app.DownloadManager
import android.content.SharedPreferences
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.llama.component.ModelImportCard
import com.example.llama.model.Downloadable
import com.example.llama.ui.theme.Black
import com.example.llama.ui.theme.PurplePrimary
import com.example.llama.ui.theme.PurpleLight
import com.example.llama.ui.theme.PurpleHeading
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavHostController,
    viewModel: MainViewModel,
    downloadManager: DownloadManager,
    modelOptions: List<Downloadable>,
    prefs: SharedPreferences
) {
    val settingsNavController = rememberNavController()
    var isUserNameFocused by remember { mutableStateOf(false) }
    var isAssistantNameFocused by remember { mutableStateOf(false) }
    var showModelDialog by remember { mutableStateOf(false) }
    // Determine if the loaded model is downloaded or imported
    val loadedModel by remember(viewModel.selectedFileName.value) {
        derivedStateOf {
            val modelName = viewModel.selectedFileName.value
            if (modelName.isNullOrEmpty()) null
            else {
                val isDownloaded = modelOptions.any { it.name == modelName }
                Pair(modelName, isDownloaded)
            }
        }
    }

    val context = LocalContext.current
    // File picker for importing .gguf files
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri ->
            uri?.let {
                try {
                    val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val displayNameIndex = cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.DISPLAY_NAME)
                            if (displayNameIndex != -1) cursor.getString(displayNameIndex) else null
                        } else null
                    } ?: uri.toString().substringAfterLast('/').takeIf { it.isNotEmpty() }

                    if (fileName != null && fileName.endsWith(".gguf", ignoreCase = true)) {
                        viewModel.selectedUri.value = uri
                        viewModel.selectedFileName.value = fileName
                        // Copying will be handled in ModelImportCard
                    } else {
                        viewModel.importError.value = "Please select a .gguf file"
                    }
                } catch (e: Exception) {
                    viewModel.importError.value = "Error checking file: ${e.message}"
                }
            } ?: run {
                viewModel.importError.value = "No file selected"
            }
        }
    )

    NavHost(
        navController = settingsNavController,
        startDestination = "settings_main"
    ) {
        composable("settings_main") {
            Scaffold(
                topBar = {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = Color(0xFFFEF7FF),
                                shape = RoundedCornerShape(bottomStart = 0.dp, bottomEnd = 0.dp)
                            )
                            .padding(vertical = 8.dp)
                    ) {
                        TopAppBar(
                            title = {
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "Settings",
                                        color = Black,
                                        style = MaterialTheme.typography.titleLarge,
                                        modifier = Modifier.fillMaxWidth(),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            },
                            navigationIcon = {
                                IconButton(onClick = { navController.popBackStack() }) {
                                    Icon(
                                        Icons.Filled.ArrowBack,
                                        contentDescription = "Back",
                                        tint = PurpleHeading
                                    )
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = Color.Transparent
                            )
                        )
                    }
                }
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 18.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(32.dp)
                ) {
                    // Your Name Field
                    OutlinedTextField(
                        value = viewModel.userName.value,
                        onValueChange = { viewModel.userName.value = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp)
                            .onFocusChanged { focusState ->
                                isUserNameFocused = focusState.isFocused
                            },
                        label = {
                            Text(
                                "Your Name",
                                color = PurplePrimary,
                                modifier = if (isUserNameFocused || viewModel.userName.value.isNotEmpty()) {
                                    Modifier
                                        .background(
                                            color = Color(0xFFFEF7FF),
                                            shape = RoundedCornerShape(4.dp)
                                        )
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                } else {
                                    Modifier
                                }
                            )
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PurplePrimary,
                            unfocusedBorderColor = Color(0xFFCAC4D0),
                            focusedLabelColor = PurplePrimary,
                            unfocusedLabelColor = PurplePrimary.copy(alpha = 0.6f)
                        ),
                        shape = RoundedCornerShape(5.dp),
                        singleLine = true
                    )

                    // Assistant Name Field
                    OutlinedTextField(
                        value = viewModel.assistantName.value,
                        onValueChange = { viewModel.assistantName.value = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp)
                            .onFocusChanged { focusState ->
                                isAssistantNameFocused = focusState.isFocused
                            },
                        label = {
                            Text(
                                "Assistant Name",
                                color = PurplePrimary,
                                modifier = if (isAssistantNameFocused || viewModel.assistantName.value.isNotEmpty()) {
                                    Modifier
                                        .background(
                                            color = Color(0xFFFEF7FF),
                                            shape = RoundedCornerShape(4.dp)
                                        )
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                } else {
                                    Modifier
                                }
                            )
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PurplePrimary,
                            unfocusedBorderColor = Color(0xFFCAC4D0),
                            focusedLabelColor = PurplePrimary,
                            unfocusedLabelColor = PurplePrimary.copy(alpha = 0.6f)
                        ),
                        shape = RoundedCornerShape(5.dp),
                        singleLine = true
                    )

                    // Model Loaded Card
                    Card(
                        onClick = { showModelDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp)
                            .height(72.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = PurpleLight.copy(alpha = 0.2f)),
                        border = BorderStroke(1.dp, Color(0xFFD9BDFE))
                    ) {
                        if (loadedModel != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        shape = CircleShape,
                                        color = Color(0xFFD9BDFE),
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        if (loadedModel!!.second) {
                                            Image(
                                                painter = painterResource(
                                                    id = when (loadedModel!!.first) {
                                                        "Orca Mini-3B" -> R.drawable.ic_orca_icon
                                                        "Gemma 3-4B" -> R.drawable.ic_gemma_icon
                                                        "Mistral-7B" -> R.drawable.ic_mistral_icon
                                                        else -> R.drawable.ic_ai_chip
                                                    }
                                                ),
                                                contentDescription = "Model Icon",
                                                modifier = Modifier
                                                    .padding(8.dp)
                                                    .fillMaxSize()
                                            )
                                        } else {
                                            Image(
                                                painter = painterResource(id = R.drawable.ic_ai_chip),
                                                contentDescription = "AI Chip",
                                                modifier = Modifier
                                                    .padding(8.dp)
                                                    .fillMaxSize()
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = "Loaded Model",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = Color(0xFF1C1B1F)
                                        )
                                        Text(
                                            text = loadedModel!!.first,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = Color(0xFF1C1B1F).copy(alpha = 0.6f)
                                        )
                                    }
                                }
                                Icon(
                                    imageVector = Icons.Default.ArrowForward,
                                    contentDescription = "Change Model",
                                    tint = PurplePrimary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 20.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_ai_chip),
                                        contentDescription = "AI Chip",
                                        tint = PurplePrimary,
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
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
                                    modifier = Modifier.size(20.dp)
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_arrow_right),
                                            contentDescription = "Arrow Right",
                                            tint = Color.White,
                                            modifier = Modifier.size(12.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Display any import errors
                    viewModel.importError.value?.takeIf { it.isNotEmpty() }?.let { error ->
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }

                    // Save Changes Button
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Button(
                            onClick = {
                                viewModel.savePrefs(prefs)
                                navController.popBackStack()
                            },
                            modifier = Modifier
                                .width(180.dp)
                                .height(40.dp),
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PurplePrimary
                            )
                        ) {
                            Text(
                                "Save Changes",
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                }

                // Model selection dialog
                if (showModelDialog) {
                    Dialog(
                        onDismissRequest = { showModelDialog = false },
                        properties = DialogProperties(
                            usePlatformDefaultWidth = false,
                            dismissOnBackPress = true,
                            dismissOnClickOutside = true
                        )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.5f))
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            ModelImportCard(
                                viewModel = viewModel,
                                downloadManager = downloadManager,
                                modelOptions = modelOptions,
                                onImportClick = { filePickerLauncher.launch("*/*") },
                                onSave = { path, isDownloaded ->
                                    // Update the MainViewModel with the new model path
                                    viewModel.setModelPath(path)
                                    // Update the selected file name based on whether it's a downloaded or imported model
                                    viewModel.selectedFileName.value = if (isDownloaded) {
                                        modelOptions.find { "${it.name.lowercase().replace(" ", "_")}.gguf" == File(path).name }?.name
                                            ?: File(path).name.removeSuffix(".gguf")
                                    } else {
                                        File(path).name.removeSuffix(".gguf")
                                    }
                                    // Mark the model as loaded
                                    viewModel.setModelLoaded(true)
                                    // Save preferences to persist the model selection
                                    viewModel.savePrefs(prefs)
                                    // Close the dialog
                                    showModelDialog = false
                                },
                                modifier = Modifier
                                    .fillMaxWidth(0.9f)
                                    .wrapContentHeight()
                                    .align(Alignment.Center)
                            )
                        }
                    }
                }
            }
        }
    }
}
