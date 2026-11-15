package com.example.quietdialer

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The click animation used by every button in the app.
 *
 * While the finger is down the element springs down to [pressedScale]; on release it bounces back.
 * A ripple is drawn on top, clipped to [shape]. Put the visual modifiers (background, padding...)
 * AFTER this one so that they shrink and grow together with the element:
 *
 *     Modifier.size(44.dp).bounceClick(shape = CircleShape, onClick = ...).background(Pal.Raised)
 *
 * [elevation] adds a drop shadow that scales along with the element.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.bounceClick(
    shape: Shape = RectangleShape,
    pressedScale: Float = 0.92f,
    elevation: Dp = 0.dp,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "pressScale"
    )
    val scaled = this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
    val lifted = if (elevation > 0.dp) scaled.shadow(elevation, shape) else scaled
    lifted
        .clip(shape)
        .combinedClickable(
            interactionSource = source,
            indication = LocalIndication.current,
            enabled = enabled,
            onLongClick = onLongClick,
            onClick = onClick
        )
}
