package com.example.llama

import android.app.DownloadManager
import android.content.SharedPreferences
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.example.llama.model.Downloadable
import com.example.llama.ui.theme.PurpleHeading
import com.example.llama.ui.theme.PurplePrimary
import com.example.llama.ui.theme.White
import android.util.Log
import kotlinx.coroutines.delay

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
    var showModelDialog by remember { mutableStateOf(false) }
    var isModelReady by remember { mutableStateOf(viewModel.isModelLoaded.value) }
    var loadedModel by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // (modelName, isDownloaded)
    var isUserNameFocused by remember { mutableStateOf(false) }
    var isAssistantNameFocused by remember { mutableStateOf(false) }
    var pendingDialogOpen by remember { mutableStateOf(false) }

    // Initialize state only once when the composable is created
    LaunchedEffect(Unit) {
        if (viewModel.isModelLoaded.value && viewModel.modelPath.value.isNotEmpty()) {
            val displayName = viewModel.selectedFileName.value ?: viewModel.getModelDisplayName(viewModel.modelPath.value.substringAfterLast('/'))
            loadedModel = Pair(displayName, false)
            isModelReady = true
            Log.d("SetupScreen", "Initialized with loaded model: $displayName, isModelReady=$isModelReady")
        }else{
            Log.d("Setupscreen", "No model loaded")
        }
    }

    // Delay opening the dialog to ensure state has settled
    LaunchedEffect(pendingDialogOpen) {
        if (pendingDialogOpen) {
            delay(100)
            showModelDialog = true
            pendingDialogOpen = false
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val mimeType = context.contentResolver.getType(uri)
            val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (displayNameIndex != -1) cursor.getString(displayNameIndex) else null
                } else null
            } ?: uri.lastPathSegment

            if (fileName?.endsWith(".gguf", ignoreCase = true) == true) {
                viewModel.selectedUri.value = uri
                viewModel.selectedFileName.value = fileName
                Log.d("SetupScreen", "File selected: $fileName, URI: $uri")
            } else {
                viewModel.importError.value = "Please select a .gguf file"
                Log.d("SetupScreen", "Invalid file type: $fileName")
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 100.dp, start = 18.dp, end = 18.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.Start
    ) {
        Column(
            horizontalAlignment = Alignment.Start,
            modifier = Modifier
                .padding(bottom = 16.dp)
                .fillMaxWidth()
        ) {
            val customFont = FontFamily(Font(R.font.roboto_serif, FontWeight.Normal))
            Text(
                text = "Welcome!",
                style = TextStyle(
                    fontFamily = customFont,
                    fontWeight = FontWeight.Normal,
                    fontSize = 70.sp,
                    color = PurpleHeading
                ),
                modifier = Modifier.padding(bottom = 8.dp),
                textAlign = TextAlign.Start
            )
            Text(
                text = "Let's set up your AI Assistant",
                style = MaterialTheme.typography.titleLarge.copy(
                    color = PurpleHeading,
                    fontSize = 28.sp
                ),
                textAlign = TextAlign.Start
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

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
                    color = Color(0xFF6750A4),
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
                focusedBorderColor = Color(0xFF6750A4),
                unfocusedBorderColor = Color(0xFFCAC4D0),
                focusedLabelColor = Color(0xFF6750A4),
                unfocusedLabelColor = Color(0xFF6750A4).copy(alpha = 0.6f)
            ),
            shape = RoundedCornerShape(5.dp),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(24.dp))

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
                    color = Color(0xFF6750A4),
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
                focusedBorderColor = Color(0xFF6750A4),
                unfocusedBorderColor = Color(0xFFCAC4D0),
                focusedLabelColor = Color(0xFF6750A4),
                unfocusedLabelColor = Color(0xFF6750A4).copy(alpha = 0.6f)
            ),
            shape = RoundedCornerShape(5.dp),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(24.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .height(72.dp)
                .clickable {
                    if (!showModelDialog) { // Prevent multiple dialog openings
                        pendingDialogOpen = true
                    }
                },
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
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
                                            else -> R.drawable.ic_ai_chip_import_model
                                        }
                                    ),
                                    contentDescription = "Model Icon",
                                    modifier = Modifier
                                        .padding(8.dp)
                                        .fillMaxSize()
                                )
                            } else {
                                Image(
                                    painter = painterResource(id = R.drawable.ic_ai_chip_import_model),
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
                                text = "Model Loaded",
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
                        painter = painterResource(id = R.drawable.ic_check),
                        contentDescription = "Loaded",
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
                                tint = White,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }
            }
        }

        if (showModelDialog) {
            Dialog(
                onDismissRequest = {
                    showModelDialog = false
                    viewModel.importError.value = ""
                    Log.d("SetupScreen", "Dialog dismissed, showImportSuccess=${viewModel.showImportSuccess.value}")
                },
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
                        onImportClick = {
                            if (!viewModel.showImportSuccess.value) {
                                filePickerLauncher.launch("application/*")
                            } else {
                                Log.d("SetupScreen", "Import click ignored, model already imported")
                            }
                        },
                        onSave = { modelPath, isDownloaded, displayName ->
                            Log.d("SetupScreen", "onSave called with modelPath=$modelPath, isDownloaded=$isDownloaded, displayName=$displayName")
                            viewModel.setModelPath(modelPath)
                            loadedModel = Pair(displayName, isDownloaded)
                            isModelReady = true
                            showModelDialog = false
                            viewModel.selectedUri.value = null
                            viewModel.selectedFileName.value = null
                            viewModel.importError.value = ""
                            viewModel.showImportSuccess.value = true
                        },
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .wrapContentHeight()
                            .align(Alignment.Center)
                    )
                }
            }
        }

        // Display any import errors
        viewModel.importError.value.takeIf { it.isNotEmpty() }?.let { error ->
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(100.dp))

        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Button(
                onClick = {
                    viewModel.savePrefs(prefs)
                    with(prefs.edit()) {
                        putBoolean("isFirstLaunch", false)
                        apply()
                    }
                    Log.d("SetupScreen", "Set isFirstLaunch to false in SharedPreferences")
                    navController.navigate("chat") {
                        popUpTo("setup") { inclusive = true }
                    }
                    Log.d("SetupScreen", "Navigated to ChatScreen after saving model")
                },
                enabled = isModelReady && viewModel.userName.value.isNotBlank() && viewModel.assistantName.value.isNotBlank(),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isModelReady && viewModel.userName.value.isNotBlank() && viewModel.assistantName.value.isNotBlank()) PurplePrimary else Color.LightGray,
                    contentColor = if (isModelReady && viewModel.userName.value.isNotBlank() && viewModel.assistantName.value.isNotBlank()) White else Color.Gray
                ),
                modifier = Modifier
                    .width(150.dp)
                    .height(48.dp)
            ) {
                Text("Done", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    // Log SharedPreferences state after saving
    LaunchedEffect(viewModel.modelPath.value) {
        val prefsState = prefs.getString("modelPath", "") ?: ""
        val isModelLoaded = prefs.getBoolean("isModelLoaded", false)
        Log.d("SetupScreen", "SharedPrefs after save: modelPath=$prefsState, isModelLoaded=$isModelLoaded")
    }

    // Log isModelLoaded changes for debugging
    LaunchedEffect(viewModel.isModelLoaded.value) {
        Log.d("SetupScreen", "isModelLoaded changed: ${viewModel.isModelLoaded.value}")
    }

    // Log showImportSuccess changes for debugging
    LaunchedEffect(viewModel.showImportSuccess.value) {
        Log.d("SetupScreen", "showImportSuccess changed: ${viewModel.showImportSuccess.value}")
    }
}
