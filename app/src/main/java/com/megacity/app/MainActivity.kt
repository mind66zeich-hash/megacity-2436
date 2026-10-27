package com.megacity.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Tema scuro neon, coerente con l'ambientazione cyberpunk.
            val scheme = darkColorScheme(
                primary = Color(0xFF00E5FF),
                onPrimary = Color(0xFF001F24),
                primaryContainer = Color(0xFF14363D),
                onPrimaryContainer = Color(0xFFCFF8FF),
                secondary = Color(0xFFFF2E88),
                background = Color(0xFF0B0D14),
                surface = Color(0xFF0B0D14),
                surfaceVariant = Color(0xFF1C2030),
                onSurfaceVariant = Color(0xFFAEB4C8)
            )
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

private val tabs = listOf("🎮" to "Gioco", "🎭" to "Personaggi", "⚙️" to "Parametri", "🧠" to "Memoria")

@Composable
fun App(vm: GameViewModel = viewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, (icon, label) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Text(icon) },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { inner ->
        val m = Modifier.padding(inner)
        when (tab) {
            0 -> GameScreen(vm, m)
            1 -> CharactersScreen(vm, m)
            2 -> ParamsScreen(vm, m)
            else -> MemoryScreen(vm, m)
        }
    }
}
