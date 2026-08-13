package com.syed.wattson

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.syed.wattson.ui.BatteryScreen
import com.syed.wattson.ui.theme.WattsonTheme

/**
 * The whole app. One activity, no services — closing it ends every bit of work Wattson does.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            WattsonTheme {
                BatteryScreen()
            }
        }
    }
}
