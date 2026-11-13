package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.preferences.UserPreferences
import com.example.ui.navigation.KomaAppNavigation
import com.example.ui.theme.KomaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as KomaApplication
        val container = app.container

        setContent {
            val prefs by container.preferences.preferences.collectAsStateWithLifecycle(
                initialValue = UserPreferences()
            )
            KomaTheme(
                themeMode = prefs.themeMode,
                amoledBlack = prefs.amoledBlack,
                dynamicColor = prefs.dynamicColor,
                accentPreset = prefs.accentPreset
            ) {
                KomaAppNavigation(container = container)
            }
        }
    }
}
