package de.nfcmanager.app.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F5FBF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E2FF),
    onPrimaryContainer = Color(0xFF001A41),
    secondary = Color(0xFF565F71),
    tertiary = Color(0xFF00796B),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFADC6FF),
    onPrimary = Color(0xFF002E69),
    primaryContainer = Color(0xFF004494),
    onPrimaryContainer = Color(0xFFD8E2FF),
    secondary = Color(0xFFBEC6DC),
    tertiary = Color(0xFF4DB6AC),
)

@Composable
fun NfcManagerTheme(darkTheme: Boolean, dynamicColor: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}

/** Farben, aus denen Anwender für ihre Tags wählen. */
val TagColors: List<Color> = listOf(
    Color(0xFF1F5FBF), Color(0xFF00796B), Color(0xFF7B1FA2), Color(0xFFC2185B),
    Color(0xFFE64A19), Color(0xFFF9A825), Color(0xFF558B2F), Color(0xFF546E7A),
)

/** Symbole (Emoji) zur Auswahl; eigene Symbole lassen sich zusätzlich eintippen. */
val TagSymbols: List<String> = listOf(
    "🏷️", "🏠", "💼", "🚗", "🔑", "📶", "📞", "✉️", "🌐", "🎵", "💡", "🛒",
    "📍", "⭐", "❤️", "🎮", "🧳", "🍽️", "🏋️", "🐾", "🔧", "📅", "💳", "🎫",
)
