package com.ivarna.fluxlinux.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Soft, not pillowed: folded washi rather than cut card. Radius encodes hierarchy, from key
 * caps and chips (6dp) through buttons and rows (10–12dp) to cards (16dp) and sheets (24dp).
 */
val FluxShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)
