package com.example.llama

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.llama.ui.theme.LlamaAndroidTheme

class SplashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LlamaAndroidTheme {
                val viewModel: MainViewModel = viewModel(factory = MainViewModelFactory(this@SplashActivity))
                SplashScreen(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun SplashScreen(viewModel: MainViewModel) {
    val context = LocalContext.current  // Move LocalContext.current here, within the Composable scope

    LaunchedEffect(viewModel.isInitialized.value) {
        if (viewModel.isInitialized.value) {
            val intent = Intent(context, MainActivity::class.java)
            context.startActivity(intent)
            (context as ComponentActivity).finish() // Close SplashActivity
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.pocket_llm_icon),
            contentDescription = "Splash Logo",
            modifier = Modifier.size(347.dp, 105.dp)
        )
    }
}
