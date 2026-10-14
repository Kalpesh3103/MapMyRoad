package com.mapmyroad

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mapmyroad.ui.MapMyRoadRoot
import com.mapmyroad.ui.theme.MapMyRoadTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MapMyRoadTheme {
                MapMyRoadRoot()
            }
        }
    }
}
