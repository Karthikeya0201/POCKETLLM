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

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        Log.d("MainActivity", "Storage permission request result: $permissions")
        if (permissions.all { it.value }) {
            viewModel.log("Storage permissions granted")
            Log.d("MainActivity", "Storage permissions granted: $permissions")
        } else {
            viewModel.log("Storage permissions denied")
            Log.w("MainActivity", "Storage permissions denied: $permissions")
            if (!shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                Log.d("MainActivity", "Legacy storage permissions permanently denied, directing to settings")
                openAppSettings()
            }
        }
    }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Log.d("MainActivity", "Audio permission request result: isGranted=$isGranted")
        if (isGranted) {
            viewModel.log("RECORD_AUDIO permission granted")
        } else {
            viewModel.log("RECORD_AUDIO permission denied")
            Log.w("MainActivity", "Storage permissions denied: $isGranted")
            if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                Log.d("MainActivity", "RECORD_AUDIO permission permanently denied, directing to settings")
                openAppSettings()
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
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Log.d("MainActivity", "Android 11+: Checking MANAGE_EXTERNAL_STORAGE")
                requestManageStorage()
            } else {
                Log.d("MainActivity", "MANAGE_EXTERNAL_STORAGE already granted")
            }
        } else {
            val permissionsToRequest = mutableListOf<String>()
            val requiredPermissions = arrayOf(
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE
            )

            requiredPermissions.forEach { permission ->
                val granted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
                Log.d("MainActivity", "Checking $permission: granted=$granted")
                if (!granted) {
                    permissionsToRequest.add(permission)
                }
            }

            if (permissionsToRequest.isNotEmpty()) {
                Log.d("MainActivity", "Requesting legacy storage permissions: $permissionsToRequest")
                storagePermissionLauncher.launch(permissionsToRequest.toTypedArray())
            } else {
                Log.d("MainActivity", "All legacy storage permissions already granted")
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.d("MainActivity", "Requesting RECORD_AUDIO permission")
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            Log.d("MainActivity", "RECORD_AUDIO permission already granted")
        }
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
                        if (permissions.contains(Manifest.permission.RECORD_AUDIO)) {
                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                        if (permissions.contains(Manifest.permission.WRITE_EXTERNAL_STORAGE) ||
                            permissions.contains(Manifest.permission.READ_EXTERNAL_STORAGE)) {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                                requestManageStorage()
                            } else {
                                storagePermissionLauncher.launch(permissions)
                            }
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
                    Log.d("MainActivity", "Model not loaded in chat route, navigating to setup screen")
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
