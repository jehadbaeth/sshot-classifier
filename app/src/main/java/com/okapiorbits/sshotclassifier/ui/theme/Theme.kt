package com.okapiorbits.sshotclassifier.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.okapiorbits.sshotclassifier.data.prefs.AppTheme

/**
 * App theme. [theme] is user-selectable in Settings: DYNAMIC = Material You (wallpaper-based) on
 * Android 12+, falling back to the brand palette on older devices; the rest are fixed palettes.
 */
@Composable
fun ScreenshotClassifierTheme(
    theme: AppTheme = AppTheme.DYNAMIC,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = when (theme) {
        AppTheme.DYNAMIC ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else if (darkTheme) BrandDarkColors else BrandLightColors
        AppTheme.BRAND -> if (darkTheme) BrandDarkColors else BrandLightColors
        AppTheme.INDIGO -> if (darkTheme) IndigoDarkColors else IndigoLightColors
        AppTheme.TEAL -> if (darkTheme) TealDarkColors else TealLightColors
    }

    MaterialTheme(colorScheme = colorScheme, shapes = AppShapes, content = content)
}
