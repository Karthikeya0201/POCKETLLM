package com.example.llama

import android.app.ActivityManager
import android.app.DownloadManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.StrictMode
import android.provider.Settings
import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import android.text.format.Formatter
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.getSystemService
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.llama.model.Downloadable
import com.example.llama.ui.theme.LlamaAndroidTheme
import java.io.File

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels { MainViewModelFactory(this) }
    private val downloadManager by lazy { getSystemService<DownloadManager>()!! }
    private val clipboardManager by lazy { getSystemService<ClipboardManager>()!! }
    private val activityManager by lazy { getSystemService<ActivityManager>()!! }

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        Log.d("MainActivity", "Permission request result: $permissions")
        permissions.forEach { (permission, isGranted) ->
            if (isGranted) {
                viewModel.log("$permission granted")
                Log.d("MainActivity", "$permission granted")
            } else {
                viewModel.log("$permission denied")
                Log.w("MainActivity", "$permission denied")
                if (!shouldShowRequestPermissionRationale(permission)) {
                    Log.d("MainActivity", "$permission permanently denied, directing to settings")
                    openAppSettings()
                }
            }
        }
    }

    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (Environment.isExternalStorageManager()) {
            viewModel.log("MANAGE_EXTERNAL_STORAGE granted")
            Log.d("MainActivity", "MANAGE_EXTERNAL_STORAGE granted")
        } else {
            viewModel.log("MANAGE_EXTERNAL_STORAGE denied")
            Log.w("MainActivity", "MANAGE_EXTERNAL_STORAGE denied")
            openAppSettings()
        }
    }

    private fun checkAndRequestPermissions() {
        val dangerousPermissions = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.SEND_SMS,
        )

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Log.d("MainActivity", "Android 11+: Requesting MANAGE_EXTERNAL_STORAGE")
                requestManageStorage()
            } else {
                Log.d("MainActivity", "MANAGE_EXTERNAL_STORAGE already granted")
            }
        }

        val permissionsToRequest = mutableListOf<String>()
        dangerousPermissions.forEach { permission ->
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
                (permission == Manifest.permission.READ_EXTERNAL_STORAGE ||
                    permission == Manifest.permission.WRITE_EXTERNAL_STORAGE)
            ) {
                return@forEach
            }
            val granted = ContextCompat.checkSelfPermission(
                this,
                permission
            ) == PackageManager.PERMISSION_GRANTED
            Log.d("MainActivity", "Checking $permission: granted=$granted")
            if (!granted) {
                permissionsToRequest.add(permission)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            Log.d("MainActivity", "Requesting permissions: $permissionsToRequest")
            permissionsLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            Log.d("MainActivity", "All required permissions already granted")
        }

        Log.d(
            "MainActivity",
            "Normal permissions (INTERNET, ACCESS_NETWORK_STATE) are granted at install time"
        )
    }

    private fun requestManageStorage() {
        Log.d("MainActivity", "Requesting MANAGE_EXTERNAL_STORAGE")
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
        intent.data = Uri.parse("package:$packageName")
        manageStorageLauncher.launch(intent)
    }

    private fun openAppSettings() {
        Log.d("MainActivity", "Opening app settings")
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        intent.data = Uri.parse("package:$packageName")
        startActivity(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("MainActivity", "MainActivity started after splash screen")

        checkAndRequestPermissions()

        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder(StrictMode.getVmPolicy())
                .detectLeakedClosableObjects()
                .build()
        )

        val free = Formatter.formatFileSize(this, availableMemory().availMem)
        val total = Formatter.formatFileSize(this, availableMemory().totalMem)
        viewModel.log("Current memory: $free / $total")

        val downloadDir = File(getExternalFilesDir(null), "Download").apply { mkdirs() }
        val importedDir = filesDir // Imported models are stored directly in filesDir

        val models = listOf(
            Downloadable(
                name = "Qwen 2.5-0.5B",
                source = Uri.parse("https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q8_0.gguf?download=true"),
                destination = File(downloadDir, "qwen2.5-0.5b-instruct-q8_0.gguf"),
                size = 0.61f, // 610MB
                iconResId = R.drawable.ic_qwen_icon
            ),
            Downloadable(
                name = "Arcee-lite 1.78B",
                source = Uri.parse("https://huggingface.co/arcee-ai/arcee-lite-GGUF/resolve/main/arcee-lite-Q8_0.gguf?download=true"),
                destination = File(downloadDir, "arcee-lite-Q8_0.gguf"),
                size = 1.8f,
                iconResId = R.drawable.ic_arcee_lite_icon
            ),
            Downloadable(
                name = "Orca Mini-3B",
                source = Uri.parse("https://huggingface.co/pankajmathur/orca_mini_v9_6_3B-Instruct-GGUF/resolve/main/orca_mini_v9_6_3B-Instruct.Q8_0.gguf?download=true"),
                destination = File(downloadDir, "orca_mini_v9_6_3B-Instruct.Q8_0.gguf"),
                size = 1.92f,
                iconResId = R.drawable.ic_orca_icon
            )
        )

        val prefs = getSharedPreferences("PocketLLMPrefs", Context.MODE_PRIVATE)

        setContent {
            LlamaAndroidTheme {
                AppNavigation(
                    viewModel = viewModel,
                    downloadManager = downloadManager,
                    clipboardManager = clipboardManager,
                    models = models,
                    prefs = prefs,
                    requestPermissions = { permissions ->
                        Log.d("MainActivity", "Requesting permissions via callback: $permissions")
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
                            (permissions.contains(Manifest.permission.READ_EXTERNAL_STORAGE) ||
                                permissions.contains(Manifest.permission.WRITE_EXTERNAL_STORAGE))
                        ) {
                            requestManageStorage()
                        } else {
                            permissionsLauncher.launch(permissions)
                        }
                    }
                )
            }
        }
    }

    private fun availableMemory(): ActivityManager.MemoryInfo {
        return ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
    }
}

class MainViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

@Composable
fun AppNavigation(
    viewModel: MainViewModel,
    downloadManager: DownloadManager,
    clipboardManager: ClipboardManager,
    models: List<Downloadable>,
    prefs: SharedPreferences,
    requestPermissions: (Array<String>) -> Unit
) {
    val context = LocalContext.current
    val navController = rememberNavController()
    var hasModels by remember { mutableStateOf<Boolean?>(null) } // Use null to indicate "not yet checked"

    // Perform a synchronous check for models to determine the initial start destination
    val initialHasModels = remember {
        val importedDir = context.filesDir
        val importedFiles = importedDir.listFiles()
            ?.filter { it.name.endsWith(".gguf", ignoreCase = true) }
            ?.toList() ?: emptyList()

        val downloadDir = File(context.getExternalFilesDir(null), "Download")
        val downloadedFiles = downloadDir.listFiles()
            ?.filter { it.name.endsWith(".gguf", ignoreCase = true) }
            ?.toList() ?: emptyList()

        val allFiles = (importedFiles + downloadedFiles).distinctBy { it.name }
        val result = allFiles.isNotEmpty()

        Log.d("AppNavigation", "Initial check - Imported models: ${importedFiles.map { it.absolutePath }}")
        Log.d("AppNavigation", "Initial check - Downloaded models: ${downloadedFiles.map { it.absolutePath }}")
        Log.d("AppNavigation", "Initial check - Has models: $result")

        result
    }

    // Update hasModels asynchronously to handle runtime changes
    LaunchedEffect(Unit) {
        val importedDir = context.filesDir
        val importedFiles = importedDir.listFiles()
            ?.filter { it.name.endsWith(".gguf", ignoreCase = true) }
            ?.toList() ?: emptyList()

        val downloadDir = File(context.getExternalFilesDir(null), "Download")
        val downloadedFiles = downloadDir.listFiles()
            ?.filter { it.name.endsWith(".gguf", ignoreCase = true) }
            ?.toList() ?: emptyList()

        val allFiles = (importedFiles + downloadedFiles).distinctBy { it.name }
        hasModels = allFiles.isNotEmpty() || (viewModel.modelPath.value.isNotEmpty() && viewModel.isModelLoaded.value)

        Log.d("AppNavigation", "LaunchedEffect - Imported models: ${importedFiles.map { it.absolutePath }}")
        Log.d("AppNavigation", "LaunchedEffect - Downloaded models: ${downloadedFiles.map { it.absolutePath }}")
        Log.d("AppNavigation", "LaunchedEffect - modelPath: ${viewModel.modelPath.value}, isModelLoaded: ${viewModel.isModelLoaded.value}")
        Log.d("AppNavigation", "LaunchedEffect - Has models: $hasModels")
    }

    // Determine the start destination using the synchronous check
    val startDestination = if (initialHasModels) {
        Log.d("AppNavigation", "Models found initially, starting at chat screen")
        "chat"
    } else {
        Log.d("AppNavigation", "No models found initially, starting at setup screen")
        "setup"
    }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable("setup") {
            SetupScreen(
                navController = navController,
                viewModel = viewModel,
                downloadManager = downloadManager,
                modelOptions = models,
                isFirstLaunch = prefs.getBoolean("isFirstLaunch", true),
                prefs = prefs
            )
        }
        composable("chat") {
            Log.d("AppNavigation", "Entered chat route - hasModels: $hasModels, isModelLoaded: ${viewModel.isModelLoaded.value}, modelPath: ${viewModel.modelPath.value}")
            if (hasModels == true || viewModel.isModelLoaded.value) {
                LaunchedEffect(Unit) {
                    if (!viewModel.isModelLoaded.value && viewModel.modelPath.value.isEmpty()) {
                        Log.d("AppNavigation", "Model not loaded and modelPath is empty, attempting to load a model")
                        val allFiles = (context.filesDir.listFiles()
                            ?.filter { it.name.endsWith(".gguf", ignoreCase = true) }
                            ?.toList() ?: emptyList()) +
                            (File(context.getExternalFilesDir(null), "Download").listFiles()
                                ?.filter { it.name.endsWith(".gguf", ignoreCase = true) }
                                ?.toList() ?: emptyList())
                        val firstModel = allFiles.firstOrNull()
                        if (firstModel != null) {
                            Log.d("AppNavigation", "Loading first available model: ${firstModel.absolutePath}")
                            viewModel.setModelPath(firstModel.absolutePath)
                        } else {
                            Log.d("AppNavigation", "No models found during chat route loading, navigating to setup")
                            navController.navigate("setup") {
                                popUpTo("chat") { inclusive = true }
                            }
                        }
                    } else {
                        Log.d("AppNavigation", "Model already loaded or modelPath exists, skipping model loading")
                    }
                }

                if (viewModel.isModelLoaded.value) {
                    Log.d("AppNavigation", "Model is loaded, displaying ChatScreen")
                    ChatScreen(
                        navController = navController,
                        viewModel = viewModel,
                        clipboardManager = clipboardManager,
                        modelOptions = models,
                        requestPermissions = requestPermissions
                    )
                } else {
                    LaunchedEffect(viewModel.isModelLoaded.value) {
                        if (!viewModel.isModelLoaded.value) {
                            Log.d("AppNavigation", "Model failed to load in chat route, navigating to setup")
                            navController.navigate("setup") {
                                popUpTo("chat") { inclusive = true }
                            }
                        }
                    }
                }
            } else {
                LaunchedEffect(hasModels) {
                    if (hasModels == false && !viewModel.isModelLoaded.value) {
                        Log.d("AppNavigation", "No models available in chat route and model not loaded, navigating to setup screen")
                        navController.navigate("setup") {
                            popUpTo("chat") { inclusive = true }
                        }
                    }
                }
            }
        }
        composable("settings") {
            SettingsScreen(
                navController = navController,
                viewModel = viewModel,
                downloadManager = downloadManager,
                modelOptions = models,
                prefs = prefs
            )
        }
        composable("talkWithAI") {
            TalkWithAIScreen(
                navController = navController,
                viewModel = viewModel,
                requestPermissions = requestPermissions
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun AppPreview() {
    val context = LocalContext.current
    LlamaAndroidTheme {
        AppNavigation(
            viewModel = MainViewModel(context),
            downloadManager = null!!,
            clipboardManager = null!!,
            models = emptyList(),
            prefs = context.getSharedPreferences("PocketLLMPrefs", Context.MODE_PRIVATE),
            requestPermissions = {}
        )
    }
}
