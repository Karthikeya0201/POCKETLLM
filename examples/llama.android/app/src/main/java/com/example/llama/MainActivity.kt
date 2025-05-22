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
        // List of dangerous permissions that require runtime approval
        val dangerousPermissions = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.READ_CONTACTS
        )

        // Handle MANAGE_EXTERNAL_STORAGE for Android 11+ (API 30+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Log.d("MainActivity", "Android 11+: Requesting MANAGE_EXTERNAL_STORAGE")
                requestManageStorage()
            } else {
                Log.d("MainActivity", "MANAGE_EXTERNAL_STORAGE already granted")
            }
        }

        // Check and request dangerous permissions
        val permissionsToRequest = mutableListOf<String>()
        dangerousPermissions.forEach { permission ->
            // Skip storage permissions on Android 11+ as MANAGE_EXTERNAL_STORAGE is used
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

        // Normal permissions (INTERNET, ACCESS_NETWORK_STATE) are granted at install time
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

        // Use context.filesDir for storing downloaded models
        val modelsDir = File(filesDir, "models").apply { mkdirs() }
        val models = listOf(
            Downloadable(
                name = "Orca Mini-3B",
                source = Uri.parse("https://huggingface.co/Aryanne/Orca-Mini-3B-gguf/resolve/main/q4_0-orca-mini-3b.gguf?download=true"),
                destination = File(modelsDir, "q4_0-orca-mini-3b.gguf"),
                size = 1.92f,
                iconResId = R.drawable.ic_orca_icon
            ),
            Downloadable(
                name = "Gemma 3-4B",
                source = Uri.parse("https://huggingface.co/google/gemma-3-4b/resolve/main/gemma-3-4b.q4_0.gguf?download=true"),
                destination = File(modelsDir, "gemma-3-4b.q4_0.gguf"),
                size = 2.32f,
                iconResId = R.drawable.ic_gemma_icon
            ),
            Downloadable(
                name = "Mistral-7B",
                source = Uri.parse("https://huggingface.co/mistralai/Mixtral-8x7B-v0.1/resolve/main/mixtral-8x7b.q4_0.gguf?download=true"),
                destination = File(modelsDir, "mixtral-8x7b.q4_0.gguf"),
                size = 4.12f,
                iconResId = R.drawable.ic_mistral_icon
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
    val navController = rememberNavController()
    val startDestination = when {
        viewModel.isFirstLaunch.value -> {
            Log.d("MainActivity", "Starting at setup screen due to first launch")
            "setup"
        }

        !viewModel.isModelLoaded.value -> {
            Log.d("MainActivity", "Starting at setup screen due to model not loaded")
            "setup"
        }

        else -> {
            Log.d("MainActivity", "Starting at chat screen")
            "chat"
        }
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
            if (viewModel.isModelLoaded.value) {
                ChatScreen(
                    navController = navController,
                    viewModel = viewModel,
                    clipboardManager = clipboardManager,
                    modelOptions = models,
                    requestPermissions = requestPermissions
                )
            } else {
                LaunchedEffect(Unit) {
                    Log.d(
                        "MainActivity",
                        "Model not loaded in chat route, navigating to setup screen"
                    )
                    navController.navigate("setup") {
                        popUpTo("chat") { inclusive = true }
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
