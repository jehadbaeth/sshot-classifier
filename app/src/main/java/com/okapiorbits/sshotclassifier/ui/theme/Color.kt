package com.okapiorbits.sshotclassifier.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Brand palette built around the blue used in the app icon, with a teal tertiary
 * accent. These are full Material 3 schemes so every surface, container and outline
 * is intentional rather than defaulted, giving the app a consistent identity even
 * where dynamic (Material You) colour is turned off.
 */

val BrandLightColors = lightColorScheme(
    primary = Color(0xFF1A5FA8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD4E3FF),
    onPrimaryContainer = Color(0xFF001C39),
    secondary = Color(0xFF535F70),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD7E3F8),
    onSecondaryContainer = Color(0xFF101C2B),
    tertiary = Color(0xFF006A60),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF9EF2E4),
    onTertiaryContainer = Color(0xFF00201C),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    background = Color(0xFFFDFCFF),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFFDFCFF),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFDFE2EB),
    onSurfaceVariant = Color(0xFF43474E),
    outline = Color(0xFF73777F),
)

val BrandDarkColors = darkColorScheme(
    primary = Color(0xFFA6C8FF),
    onPrimary = Color(0xFF00315C),
    primaryContainer = Color(0xFF00497F),
    onPrimaryContainer = Color(0xFFD4E3FF),
    secondary = Color(0xFFBBC7DB),
    onSecondary = Color(0xFF253140),
    secondaryContainer = Color(0xFF3B4858),
    onSecondaryContainer = Color(0xFFD7E3F8),
    tertiary = Color(0xFF83D5C8),
    onTertiary = Color(0xFF003731),
    tertiaryContainer = Color(0xFF005048),
    onTertiaryContainer = Color(0xFF9EF2E4),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    background = Color(0xFF1A1C1E),
    onBackground = Color(0xFFE2E2E6),
    surface = Color(0xFF1A1C1E),
    onSurface = Color(0xFFE2E2E6),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC3C7CF),
    outline = Color(0xFF8D9199),
)

// Fixed alternative palettes for the theme picker. Indigo primary + amber accent.
val IndigoLightColors = lightColorScheme(
    primary = Color(0xFF3F51B5),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDEE0FF),
    onPrimaryContainer = Color(0xFF00105C),
    secondary = Color(0xFF5C5D72),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE1E0F9),
    tertiary = Color(0xFF8C5000),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDCBE),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
)
val IndigoDarkColors = darkColorScheme(
    primary = Color(0xFFBBC3FF),
    onPrimary = Color(0xFF09218B),
    primaryContainer = Color(0xFF2639A2),
    onPrimaryContainer = Color(0xFFDEE0FF),
    secondary = Color(0xFFC5C4DD),
    tertiary = Color(0xFFFFB870),
    onTertiary = Color(0xFF4A2800),
    tertiaryContainer = Color(0xFF6A3C00),
)

// Teal primary + coral accent.
val TealLightColors = lightColorScheme(
    primary = Color(0xFF00897B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF8DF8E6),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF4A6360),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8E3),
    tertiary = Color(0xFFB1261E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDAD5),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
)
val TealDarkColors = darkColorScheme(
    primary = Color(0xFF70DBCA),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005048),
    onPrimaryContainer = Color(0xFF8DF8E6),
    secondary = Color(0xFFB0CCC7),
    tertiary = Color(0xFFFFB4A9),
    onTertiary = Color(0xFF680A04),
    tertiaryContainer = Color(0xFF8C1A14),
)
