package com.ivarna.fluxlinux.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Cut paper: corners are trimmed, not pillowed. Radius still encodes hierarchy, from key
 * caps and chips (2dp) through buttons and rows (4–6dp) to sheets and dialogs (14dp).
 */
val FluxShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(10.dp),
    extraLarge = RoundedCornerShape(14.dp),
)
