package com.syed.wattson

import android.os.Bundle
import com.syed.wattson.data.Capabilities
import com.syed.wattson.data.DataTier
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
        // Debug aid: `--es tier BASIC|PRIVILEGED|ROOT` pins the data tier so the
        // degraded experiences can be checked without removing root.
        if (BuildConfig.DEBUG) {
            Capabilities.debugOverride = intent?.getStringExtra("tier")
                ?.let { runCatching { DataTier.valueOf(it) }.getOrNull() }
        }
        super.onCreate(savedInstanceState)
        setContent {
            WattsonTheme {
                BatteryScreen()
            }
        }
    }
}
