package com.example.llama

import android.app.DownloadManager
import android.content.SharedPreferences
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.example.llama.component.ModelImportCard
import com.example.llama.component.NameFieldComponent
import com.example.llama.component.ProgressComponent
import com.example.llama.component.SettingsContent
import com.example.llama.component.SettingsSidebar
import com.example.llama.model.Downloadable
import com.example.llama.model.SettingsMenuItem
import com.example.llama.ui.theme.PurplePrimary
import com.example.llama.ui.theme.PurpleLight
import com.example.llama.ui.theme.PurpleLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavHostController,
    viewModel: MainViewModel,
    downloadManager: DownloadManager,
    modelOptions: List<Downloadable>,
    prefs: SharedPreferences
) {
    val context = LocalContext.current
    var selectedItem by remember { mutableStateOf(SettingsMenuItem.PROFILE) }

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
                        viewModel.setModelPath(uri.toString())
                        viewModel.savePrefs(prefs)
                        navController.popBackStack()
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Settings",
                        color = PurplePrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = PurplePrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = PurpleLight.copy(alpha = 0.4f)
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.White)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                SettingsSidebar(
                    selectedItem = selectedItem,
                    onItemClick = { selectedItem = it },
                    modifier = Modifier.weight(1f)
                )
                SettingsContent(
                    selectedItem = selectedItem,
                    viewModel = viewModel,
                    downloadManager = downloadManager,
                    modelOptions = modelOptions,
                    prefs = prefs,
                    onImportClick = { filePickerLauncher.launch("*/*") },
                    modifier = Modifier
                        .weight(3f)
                        .padding(16.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSettings(
    viewModel: MainViewModel,
    prefs: SharedPreferences,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        var isEditingName by remember { mutableStateOf(false) }
        var nameValue by remember { mutableStateOf(viewModel.userName.value) }
        var showNameBadge by remember { mutableStateOf(false) }

        NameFieldComponent(
            label = "Your Name",
            value = nameValue,
            onValueChange = { newValue ->
                nameValue = newValue
                viewModel.userName.value = newValue
            },
            onSave = {
                isEditingName = false
                viewModel.savePrefs(prefs)
            },
            isEditing = isEditingName,
            onFocusChange = { focused ->
                showNameBadge = focused && nameValue.isEmpty()
            },
            modifier = Modifier.fillMaxWidth()
        )
        if (showNameBadge) {
            Badge(
                containerColor = PurpleLabel,
                contentColor = Color.White,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .align(Alignment.CenterHorizontally)
            ) {
                Text("User", fontSize = 12.sp)
            }
        }

        var isEditingAssistant by remember { mutableStateOf(false) }
        var assistantValue by remember { mutableStateOf(viewModel.assistantName.value) }
        var showAssistantBadge by remember { mutableStateOf(false) }

        NameFieldComponent(
            label = "Assistant Name",
            value = assistantValue,
            onValueChange = { newValue ->
                assistantValue = newValue
                viewModel.assistantName.value = newValue
            },
            onSave = {
                isEditingAssistant = false
                viewModel.savePrefs(prefs)
            },
            isEditing = isEditingAssistant,
            onFocusChange = { focused ->
                showAssistantBadge = focused && assistantValue.isEmpty()
            },
            modifier = Modifier.fillMaxWidth()
        )
        if (showAssistantBadge) {
            Badge(
                containerColor = PurpleLabel,
                contentColor = Color.White,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .align(Alignment.CenterHorizontally)
            ) {
                Text("Jarvis", fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun ModelSettings(
    viewModel: MainViewModel,
    downloadManager: DownloadManager,
    modelOptions: List<Downloadable>,
    onImportClick: () -> Unit,
    prefs: SharedPreferences,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ModelImportCard(
            viewModel = viewModel,
            downloadManager = downloadManager,
            modelOptions = modelOptions,
            onImportClick = onImportClick,
            onSave = { viewModel.savePrefs(prefs) },
            modifier = Modifier.fillMaxWidth()
        )
        ProgressComponent(
            progress = 0f, // Progress handled in ModelImportCard or Downloadable.DownloadButton
            isLoading = viewModel.isLoadingModel.value,
            modifier = Modifier.fillMaxWidth()
        )
        if (viewModel.importError.value.isNotEmpty()) {
            Text(
                text = viewModel.importError.value,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

// Placeholder composables for other settings sections
@Composable
fun NotificationsSettings(modifier: Modifier = Modifier) {
    Text(
        text = "Notifications Settings",
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier
    )
}

@Composable
fun RecentChatsSettings(modifier: Modifier = Modifier) {
    Text(
        text = "Recent Chats Settings",
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier
    )
}

@Composable
fun AboutSettings(modifier: Modifier = Modifier) {
    Text(
        text = "About Settings",
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier
    )
}

@Composable
fun ArchivedChatsSettings(modifier: Modifier = Modifier) {
    Text(
        text = "Archived Chats Settings",
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier
    )
}

@Composable
fun CallsSettings(modifier: Modifier = Modifier) {
    Text(
        text = "Calls Settings",
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier
    )
}

@Composable
fun GroupsSettings(modifier: Modifier = Modifier) {
    Text(
        text = "Groups Settings",
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier
    )
}

@Composable
fun PrivacySettings(modifier: Modifier = Modifier) {
    Text(
        text = "Privacy Settings",
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier
    )
}
