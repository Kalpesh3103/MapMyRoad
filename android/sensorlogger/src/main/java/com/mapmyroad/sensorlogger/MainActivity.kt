package com.mapmyroad.sensorlogger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.mapmyroad.sensorlogger.ui.LoggerApp
import com.mapmyroad.sensorlogger.ui.LoggerTheme

/** Single Activity. Debug field tool, see README.md. */
class MainActivity : ComponentActivity() {
    // Bumped on every resume so the UI re-checks permissions after the user visits Settings.
    private var resumeCount by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            LoggerTheme {
                LoggerApp(resumeCount = resumeCount)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeCount++
    }
}
