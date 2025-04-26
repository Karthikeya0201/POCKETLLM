package com.example.llama

import android.app.DownloadManager
import android.content.SharedPreferences
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.example.llama.component.ModelImportCard

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
                        color = MaterialTheme.colorScheme.primary // Violet (uses theme's primary color)
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.Filled.ArrowBack, 
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.primary // Violet (uses theme's primary color)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            var isEditingName by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = viewModel.userName.value,
                onValueChange = { newValue -> viewModel.userName.value = newValue },
                label = { Text("Your Name") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Person,
                        contentDescription = "User"
                    )
                },
                trailingIcon = {
                    if (isEditingName) {
                        IconButton(
                            onClick = {
                                isEditingName = false
                                viewModel.savePrefs(prefs)
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = "Save",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else {
                        IconButton(
                            onClick = { isEditingName = true }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = "Edit",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                readOnly = !isEditingName
            )

            var isEditingAssistant by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = viewModel.assistantName.value,
                onValueChange = { newValue -> viewModel.assistantName.value = newValue },
                label = { Text("Assistant Name") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Person,
                        contentDescription = "Assistant"
                    )
                },
                trailingIcon = {
                    if (isEditingAssistant) {
                        IconButton(
                            onClick = {
                                isEditingAssistant = false
                                viewModel.savePrefs(prefs)
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = "Save",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else {
                        IconButton(
                            onClick = { isEditingAssistant = true }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = "Edit",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                readOnly = !isEditingAssistant
            )

            ModelImportCard(
                onImportClick = { filePickerLauncher.launch("/") },
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = "Or download a predefined model:",
                style = MaterialTheme.typography.titleMedium
            )

            modelOptions.forEach { model ->
                Downloadable.DownloadButton(
                    viewModel = viewModel,
                    downloadManager = downloadManager,
                    item = model,
                    onDownloadComplete = {
                        viewModel.savePrefs(prefs)
                        navController.popBackStack()
                    }
                )
            }

            if (viewModel.isLoadingModel.value) {
                Downloadable.CustomProgressBar(
                    progress = 0f,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (viewModel.importError.value.isNotEmpty()) {
                Text(
                    text = viewModel.importError.value,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
