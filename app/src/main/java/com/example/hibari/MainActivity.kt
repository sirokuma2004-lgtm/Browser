package com.example.hibari

import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.hibari.ui.BrowserScreen
import com.example.hibari.ui.theme.HibariTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Disable WebView debugging in non-debug builds.
        // This must be called before any WebView is instantiated.
        if (!BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(false)
        }

        enableEdgeToEdge()

        setContent {
            HibariTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BrowserScreen()
                }
            }
        }
    }
}
