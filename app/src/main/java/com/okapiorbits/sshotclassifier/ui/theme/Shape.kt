package com.okapiorbits.sshotclassifier.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * App-wide corner rounding. Material3 components read these from the theme, so bumping the scale
 * here rounds text fields, cards, dialogs, menus, and chips consistently in one place (rather than
 * setting a shape on each call site). Slightly rounder than the M3 defaults for a softer look.
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),   // text fields, small chips
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),      // cards, surfaces
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),  // dialogs, bottom sheets
)
