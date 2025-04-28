package com.example.llama

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import com.example.llama.ui.theme.LlamaAndroidTheme

class SplashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LlamaAndroidTheme {
                SplashScreen(
                    onSplashFinished = {
                        startActivity(Intent(this@SplashActivity, MainActivity::class.java))
                        finish() // Close SplashActivity
                    }
                )
            }
        }
    }
}
