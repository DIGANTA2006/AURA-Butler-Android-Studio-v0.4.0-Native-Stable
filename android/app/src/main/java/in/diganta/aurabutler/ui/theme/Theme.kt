package `in`.diganta.aurabutler.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val AuraGreen = Color(0xFF18362C)
val AuraGreenSoft = Color(0xFF31594B)
val AuraGold = Color(0xFFD6B66A)
val AuraCream = Color(0xFFF6F3EC)
val AuraInk = Color(0xFF17201D)

// Screen background is slightly translucent so the animated AuraGlassBackdrop
// shows through. Cards and dialogs use the opaque surface colours below.
private val LightColors = lightColorScheme(
    primary = AuraGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDEBE4),
    onPrimaryContainer = Color(0xFF0A241B),
    secondary = Color(0xFF735E28),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE8A9),
    onSecondaryContainer = Color(0xFF251A00),
    background = Color(0xE0F6F3EC),
    onBackground = AuraInk,
    surface = Color(0xFFFFFBF5),
    onSurface = AuraInk,
    surfaceVariant = Color(0xFFE7E5DD),
    onSurfaceVariant = Color(0xFF454943),
    error = Color(0xFFBA1A1A)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA8D5C1),
    onPrimary = Color(0xFF083729),
    primaryContainer = Color(0xFF244F40),
    onPrimaryContainer = Color(0xFFC3F1DB),
    secondary = AuraGold,
    onSecondary = Color(0xFF3D2F00),
    secondaryContainer = Color(0xFF574500),
    onSecondaryContainer = Color(0xFFFFE08A),
    background = Color(0xD0111410),
    onBackground = Color(0xFFE2E4DE),
    surface = Color(0xFF171B17),
    onSurface = Color(0xFFE2E4DE),
    surfaceVariant = Color(0xFF414842),
    onSurfaceVariant = Color(0xFFC1C9C2)
)

@Composable
fun AuraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = MaterialTheme.typography,
        content = content
    )
}
