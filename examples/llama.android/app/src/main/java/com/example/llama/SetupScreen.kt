package com.example.llama

import android.Manifest
import android.app.DownloadManager
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CalendarContract
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.core.content.ContextCompat
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
    var loadedModel by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var isUserNameFocused by remember { mutableStateOf(false) }
    var isAssistantNameFocused by remember { mutableStateOf(false) }
    var pendingDialogOpen by remember { mutableStateOf(false) }
    var importState by remember { mutableStateOf<ImportState?>(null) }
    var calendarPermissionDenied by remember { mutableStateOf(false) }
    var isLoadingCalendars by remember { mutableStateOf(false) }
    val calendarList by viewModel.calendarList
    val selectedCalendarId by viewModel.selectedCalendarId

    fun hasCalendarPermission(context: android.content.Context): Boolean {
        val readGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
        val writeGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
        Log.d("SetupScreen", "Calendar permissions - READ: $readGranted, WRITE: $writeGranted")
        return readGranted && writeGranted
    }

    fun loadCalendars(context: android.content.Context, viewModel: MainViewModel) {
        isLoadingCalendars = true
        Log.d("SetupScreen", "Starting to load calendars")
        try {
            viewModel.calendarList.value = mutableListOf()
            val projection = arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME
            )
            val selection = "${CalendarContract.Calendars.VISIBLE} = 1"

            val cursor: Cursor? = context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                selection,
                null,
                null
            )

            val calendars = mutableListOf<Pair<Long, String>>()
            cursor?.use {
                while (it.moveToNext()) {
                    val id = it.getLong(0)
                    val name = it.getString(1) ?: "Unnamed Calendar"
                    calendars.add(id to name)
                }
            }
            viewModel.calendarList.value = calendars
            if (calendars.isNotEmpty() && viewModel.selectedCalendarId.value == null) {
                viewModel.selectedCalendarId.value = calendars[0].first
                Log.d("SetupScreen", "Set default calendar ID: ${calendars[0].first}")
            }
            Log.d(
                "SetupScreen",
                "Loaded ${calendars.size} calendars: ${calendars.joinToString { it.second }}"
            )
        } catch (e: Exception) {
            Log.e("SetupScreen", "Error loading calendars: ${e.stackTraceToString()}")
            viewModel.calendarList.value = emptyList()
        } finally {
            isLoadingCalendars = false
            Log.d(
                "SetupScreen",
                "Finished loading calendars, isLoadingCalendars=$isLoadingCalendars"
            )
        }
    }

    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.all { it.value }
        calendarPermissionDenied = !granted
        if (granted) {
            Log.d("SetupScreen", "Calendar permissions granted")
            loadCalendars(context, viewModel)
        } else {
            Log.d("SetupScreen", "Calendar permissions denied: $permissions")
        }
    }

    LaunchedEffect(Unit) {
        Log.d("SetupScreen", "Checking calendar permissions")
        if (hasCalendarPermission(context)) {
            loadCalendars(context, viewModel)
        } else {
            calendarPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_CALENDAR,
                    Manifest.permission.WRITE_CALENDAR
                )
            )
        }
    }

    LaunchedEffect(calendarPermissionDenied) {
        if (!calendarPermissionDenied && hasCalendarPermission(context)) {
            Log.d("SetupScreen", "Retrying calendar load after permission change")
            loadCalendars(context, viewModel)
        }
    }

    LaunchedEffect(Unit) {
        if (viewModel.isModelLoaded.value && viewModel.modelPath.value.isNotEmpty()) {
            val displayName = viewModel.getModelDisplayName(viewModel.modelPath.value.substringAfterLast('/'))
            loadedModel = Pair(displayName, viewModel.downloadedModels.value.contains(displayName))
            isModelReady = true
            Log.d(
                "SetupScreen",
                "Initialized with loaded model: $displayName, isModelReady=$isModelReady"
            )
        } else {
            loadedModel = null
            isModelReady = false
            Log.d("SetupScreen", "No model loaded")
        }
    }

    LaunchedEffect(importState, viewModel.isLoadingModel.value, viewModel.importError.value) {
        importState?.let { state ->
            if (!viewModel.isLoadingModel.value) {
                if (viewModel.importError.value.isEmpty()) {
                    viewModel.setModelLoaded(true)
                    viewModel.savePrefs(prefs)
                    loadedModel = Pair(state.displayName, state.isDownloaded)
                    isModelReady = true
                    showModelDialog = false
                    viewModel.selectedUri.value = null
                    viewModel.selectedFileName.value = null
                    viewModel.showImportSuccess.value = false
                    Log.d(
                        "SetupScreen",
                        "Import completed: displayName=${state.displayName}, isModelReady=$isModelReady"
                    )
                }
                importState = null
            }
        }
    }

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

    // Use a scrollable Column to ensure all content is visible
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp)
            .verticalScroll(rememberScrollState()), // Enable scrolling
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            horizontalAlignment = Alignment.Start,
            modifier = Modifier
                .padding(top = 60.dp) // Reduced top padding to save space
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

            Spacer(modifier = Modifier.height(24.dp)) // Reduced spacing

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

            Spacer(modifier = Modifier.height(16.dp)) // Reduced spacing

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

            Spacer(modifier = Modifier.height(16.dp)) // Reduced spacing

            var expanded by remember { mutableStateOf(false) }
            Log.d(
                "SetupScreen",
                "Rendering ExposedDropdownMenuBox, expanded=$expanded, calendarList.size=${calendarList.size}"
            )
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { newExpanded ->
                    expanded = newExpanded
                    if (newExpanded && hasCalendarPermission(context)) {
                        Log.d("SetupScreen", "Dropdown opened, refreshing calendars")
                        loadCalendars(context, viewModel)
                    }
                    Log.d("SetupScreen", "Dropdown expanded state changed to: $expanded")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
            ) {
                OutlinedTextField(
                    value = when {
                        isLoadingCalendars -> "Loading calendars..."
                        else -> calendarList.find { it.first == selectedCalendarId }?.second
                            ?: "Select Calendar"
                    },
                    onValueChange = {},
                    readOnly = true,
                    label = {
                        Text(
                            "Calendar",
                            color = Color(0xFF6750A4),
                            modifier = if (expanded || selectedCalendarId != null) {
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
                    trailingIcon = {
                        if (isLoadingCalendars) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF6750A4),
                        unfocusedBorderColor = Color(0xFFCAC4D0),
                        focusedLabelColor = Color(0xFF6750A4),
                        unfocusedLabelColor = Color(0xFF6750A4).copy(alpha = 0.6f)
                    ),
                    shape = RoundedCornerShape(5.dp),
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = {
                        expanded = false
                        Log.d("SetupScreen", "Dropdown dismissed")
                    },
                    modifier = Modifier.background(Color.White)
                ) {
                    if (isLoadingCalendars) {
                        DropdownMenuItem(
                            text = { Text("Loading calendars...") },
                            onClick = { /* No action during loading */ },
                            enabled = false,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Log.d("SetupScreen", "Displayed 'Loading calendars...' in dropdown")
                    } else if (calendarList.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("No calendars available") },
                            onClick = { expanded = false },
                            enabled = false,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Log.d("SetupScreen", "Displayed 'No calendars available' in dropdown")
                    } else {
                        calendarList.forEach { calendar: Pair<Long, String> ->
                            DropdownMenuItem(
                                text = { Text(calendar.second) },
                                onClick = {
                                    viewModel.selectedCalendarId.value = calendar.first
                                    expanded = false
                                    Log.d(
                                        "SetupScreen",
                                        "Selected calendar: ${calendar.second} (ID: ${calendar.first})"
                                    )
                                },
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            if (calendarPermissionDenied) {
                Spacer(modifier = Modifier.height(8.dp)) // Reduced spacing
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Calendar permission denied. Please grant permissions.",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            calendarPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.READ_CALENDAR,
                                    Manifest.permission.WRITE_CALENDAR
                                )
                            )
                            Log.d("SetupScreen", "Retry permissions clicked")
                        }
                    ) {
                        Text("Retry")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp)) // Reduced spacing

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
                    .height(72.dp)
                    .clickable {
                        if (!showModelDialog) {
                            pendingDialogOpen = true
                            viewModel.importError.value = ""
                            Log.d("SetupScreen", "Model card clicked, opening dialog")
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
                                                "Qwen 3-0.6B" -> R.drawable.ic_qwen_icon
                                                "Arcee-lite" -> R.drawable.ic_arcee_lite_icon
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

            viewModel.importError.value.takeIf { it.isNotEmpty() }?.let { error ->
                Spacer(modifier = Modifier.height(8.dp)) // Reduced spacing
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp)) // Reduced spacing
        }

        // "Done" button at the bottom with padding
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp), // Ensure the button has padding at the bottom
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
                enabled = isModelReady && viewModel.userName.value.isNotBlank() &&
                    viewModel.assistantName.value.isNotBlank() && selectedCalendarId != null,
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isModelReady && viewModel.userName.value.isNotBlank() &&
                        viewModel.assistantName.value.isNotBlank() && selectedCalendarId != null
                    )
                        PurplePrimary else Color.LightGray,
                    contentColor = if (isModelReady && viewModel.userName.value.isNotBlank() &&
                        viewModel.assistantName.value.isNotBlank() && selectedCalendarId != null
                    )
                        White else Color.Gray
                ),
                modifier = Modifier
                    .width(150.dp)
                    .height(48.dp)
            ) {
                Text(
                    text = "Done",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    if (showModelDialog) {
        Dialog(
            onDismissRequest = {
                if (!viewModel.isLoadingModel.value && importState == null) {
                    showModelDialog = false
                    viewModel.importError.value = ""
                    Log.d(
                        "SetupScreen",
                        "Dialog dismissed, showImportSuccess=${viewModel.showImportSuccess.value}"
                    )
                }
            },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = !viewModel.isLoadingModel.value && importState == null,
                dismissOnClickOutside = !viewModel.isLoadingModel.value && importState == null
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
                        filePickerLauncher.launch("application/*")
                    },
                    onSave = { modelPath, isDownloaded, displayName ->
                        Log.d(
                            "SetupScreen",
                            "onSave called with modelPath=$modelPath, isDownloaded=$isDownloaded, displayName=$displayName"
                        )
                        viewModel.setModelPath(modelPath)
                        importState = ImportState(modelPath, isDownloaded, displayName)
                    },
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .wrapContentHeight()
                        .align(Alignment.Center)
                )
            }
        }
    }

    LaunchedEffect(viewModel.modelPath.value, viewModel.selectedCalendarId.value) {
        val prefsState = prefs.getString("modelPath", "") ?: ""
        val isModelLoaded = prefs.getBoolean("isModelLoaded", false)
        val calendarId = prefs.getLong("selectedCalendarId", -1L)
        Log.d(
            "SetupScreen",
            "SharedPrefs after save: modelPath=$prefsState, isModelLoaded=$isModelLoaded, selectedCalendarId=$calendarId"
        )
    }

    LaunchedEffect(viewModel.isModelLoaded.value) {
        Log.d("SetupScreen", "isModelLoaded changed: ${viewModel.isModelLoaded.value}")
    }

    LaunchedEffect(viewModel.showImportSuccess.value) {
        Log.d("SetupScreen", "showImportSuccess changed: ${viewModel.showImportSuccess.value}")
    }

    LaunchedEffect(viewModel.selectedCalendarId.value) {
        Log.d(
            "SetupScreen",
            "selectedCalendarId changed: ${viewModel.selectedCalendarId.value}"
        )
    }
}
