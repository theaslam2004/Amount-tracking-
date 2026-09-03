package com.example.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

enum class AppThemeMode {
    LIGHT, DARK, SYSTEM
}

class ThemeController(
    initialMode: AppThemeMode = AppThemeMode.SYSTEM,
    private val onThemeChanged: ((AppThemeMode) -> Unit)? = null
) {
    var themeMode by mutableStateOf(initialMode)
        private set

    fun setTheme(mode: AppThemeMode) {
        themeMode = mode
        onThemeChanged?.invoke(mode)
    }

    fun toggleLightDark(isSystemDark: Boolean) {
        val currentlyDark = when (themeMode) {
            AppThemeMode.LIGHT -> false
            AppThemeMode.DARK -> true
            AppThemeMode.SYSTEM -> isSystemDark
        }
        val nextMode = if (currentlyDark) AppThemeMode.LIGHT else AppThemeMode.DARK
        setTheme(nextMode)
    }

    companion object {
        private const val PREFS_NAME = "bento_theme_prefs"
        private const val KEY_THEME_MODE = "bento_theme_mode"

        fun create(context: Context): ThemeController {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedName = prefs.getString(KEY_THEME_MODE, AppThemeMode.SYSTEM.name)
            val initialMode = try {
                AppThemeMode.valueOf(savedName ?: AppThemeMode.SYSTEM.name)
            } catch (e: Exception) {
                AppThemeMode.SYSTEM
            }

            return ThemeController(initialMode) { mode ->
                prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
            }
        }
    }
}

val LocalThemeController = compositionLocalOf { ThemeController() }

private val BentoLightColorScheme = lightColorScheme(
    primary = BentoPrimary,
    onPrimary = BentoSurface,
    primaryContainer = BentoPrimaryContainer,
    onPrimaryContainer = BentoOnPrimaryContainer,
    background = BentoBackground,
    onBackground = BentoTextPrimary,
    surface = BentoSurface,
    onSurface = BentoTextPrimary,
    surfaceVariant = BentoSurfaceVariant,
    onSurfaceVariant = BentoTextSecondary,
    outline = BentoOutline,
    outlineVariant = BentoOutline,
    error = BentoError,
    onError = BentoSurface
)

private val BentoDarkColorScheme = darkColorScheme(
    primary = BentoDarkPrimary,
    onPrimary = BentoDarkBackground,
    primaryContainer = BentoDarkPrimaryContainer,
    onPrimaryContainer = BentoDarkOnPrimaryContainer,
    background = BentoDarkBackground,
    onBackground = BentoDarkTextPrimary,
    surface = BentoDarkSurface,
    onSurface = BentoDarkTextPrimary,
    surfaceVariant = BentoDarkSurfaceVariant,
    onSurfaceVariant = BentoDarkTextSecondary,
    outline = BentoDarkOutline,
    outlineVariant = BentoDarkOutline,
    error = BentoDarkError,
    onError = BentoDarkBackground
)

@Composable
fun MyApplicationTheme(
    themeController: ThemeController = remember { ThemeController() },
    content: @Composable () -> Unit
) {
    val isSystemDark = isSystemInDarkTheme()
    val isDark = when (themeController.themeMode) {
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
        AppThemeMode.SYSTEM -> isSystemDark
    }

    val colorScheme = if (isDark) BentoDarkColorScheme else BentoLightColorScheme

    CompositionLocalProvider(LocalThemeController provides themeController) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
