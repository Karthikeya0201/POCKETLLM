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
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.text.format.Formatter
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.getSystemService
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
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
            Log.w("MainActivity", "RECORD_AUDIO permission denied")
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
            openAppSettings() // Guide user to enable manually
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

        // Check and request RECORD_AUDIO permission
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
        setTheme(R.style.Theme_LlamaAndroid)
        super.onCreate(savedInstanceState)

        checkAndRequestPermissions()

        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder(StrictMode.getVmPolicy())
                .detectLeakedClosableObjects()
                .build()
        )

        val free = Formatter.formatFileSize(this, availableMemory().availMem)
        val total = Formatter.formatFileSize(this, availableMemory().totalMem)
        viewModel.log("Current memory: $free / $total")

        val extFilesDir = getExternalFilesDir(null)
        val models = listOf(
            Downloadable(
                "Orca Mini 3B",
                Uri.parse("https://huggingface.co/Aryanne/Orca-Mini-3B-gguf/resolve/main/q4_0-orca-mini-3b.gguf?download=true"),
                File(extFilesDir, "q4_0-orca-mini-3b.gguf")
            )
        )

        val prefs = getSharedPreferences("LlamaPrefs", Context.MODE_PRIVATE)
        val isFirstLaunch = prefs.getBoolean("isFirstLaunch", true)
        viewModel.loadPrefs(prefs)

        setContent {
            LlamaAndroidTheme {
                AppNavigation(
                    viewModel = viewModel,
                    downloadManager = downloadManager,
                    clipboardManager = clipboardManager,
                    models = models,
                    isFirstLaunch = isFirstLaunch,
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
    isFirstLaunch: Boolean,
    prefs: android.content.SharedPreferences,
    requestPermissions: (Array<String>) -> Unit
) {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = if (isFirstLaunch) "setup" else "chat"
    ) {
        composable("setup") {
            SetupScreen(
                navController = navController,
                viewModel = viewModel,
                downloadManager = downloadManager,
                modelOptions = models,
                isFirstLaunch = true,
                prefs = prefs
            )
        }
        composable("chat") {
            ChatScreen(
                navController = navController,
                viewModel = viewModel,
                clipboardManager = clipboardManager,
                requestPermissions = requestPermissions
            )
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