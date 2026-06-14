package com.okapiorbits.sshotclassifier.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * App theme. Defaults to the fixed brand palette for a consistent identity.
 * [dynamicColor] opts into Material You (wallpaper-based) colour on Android 12+,
 * exposed as a user setting; it has no effect on older devices.
 */
@Composable
fun ScreenshotClassifierTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> BrandDarkColors
        else -> BrandLightColors
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}
