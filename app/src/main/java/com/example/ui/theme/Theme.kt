package com.example.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = PureWhite,
    onPrimary = PureBlack,
    primaryContainer = PureBlack,
    onPrimaryContainer = PureWhite,
    secondary = PureWhite,
    onSecondary = PureBlack,
    background = PureBlack,
    onBackground = PureWhite,
    surface = PureBlack,
    onSurface = PureWhite,
    surfaceVariant = PureBlack,
    onSurfaceVariant = PureWhite,
    error = PureWhite,
    onError = PureBlack,
    outline = PureWhite
)

private val LightColorScheme = lightColorScheme(
    primary = PureBlack,
    onPrimary = PureWhite,
    primaryContainer = PureWhite,
    onPrimaryContainer = PureBlack,
    secondary = PureBlack,
    onSecondary = PureWhite,
    background = PureWhite,
    onBackground = PureBlack,
    surface = PureWhite,
    onSurface = PureBlack,
    surfaceVariant = PureWhite,
    onSurfaceVariant = PureBlack,
    error = PureBlack,
    onError = PureWhite,
    outline = PureBlack
)

@Composable
fun DoxonTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val targetColorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val animatedPrimary = animateColorAsState(targetValue = targetColorScheme.primary, animationSpec = tween(durationMillis = 500), label = "primary")
    val animatedOnPrimary = animateColorAsState(targetValue = targetColorScheme.onPrimary, animationSpec = tween(durationMillis = 500), label = "onPrimary")
    val animatedPrimaryContainer = animateColorAsState(targetValue = targetColorScheme.primaryContainer, animationSpec = tween(durationMillis = 500), label = "primaryContainer")
    val animatedOnPrimaryContainer = animateColorAsState(targetValue = targetColorScheme.onPrimaryContainer, animationSpec = tween(durationMillis = 500), label = "onPrimaryContainer")
    val animatedSecondary = animateColorAsState(targetValue = targetColorScheme.secondary, animationSpec = tween(durationMillis = 500), label = "secondary")
    val animatedOnSecondary = animateColorAsState(targetValue = targetColorScheme.onSecondary, animationSpec = tween(durationMillis = 500), label = "onSecondary")
    val animatedBackground = animateColorAsState(targetValue = targetColorScheme.background, animationSpec = tween(durationMillis = 500), label = "background")
    val animatedOnBackground = animateColorAsState(targetValue = targetColorScheme.onBackground, animationSpec = tween(durationMillis = 500), label = "onBackground")
    val animatedSurface = animateColorAsState(targetValue = targetColorScheme.surface, animationSpec = tween(durationMillis = 500), label = "surface")
    val animatedOnSurface = animateColorAsState(targetValue = targetColorScheme.onSurface, animationSpec = tween(durationMillis = 500), label = "onSurface")
    val animatedSurfaceVariant = animateColorAsState(targetValue = targetColorScheme.surfaceVariant, animationSpec = tween(durationMillis = 500), label = "surfaceVariant")
    val animatedOnSurfaceVariant = animateColorAsState(targetValue = targetColorScheme.onSurfaceVariant, animationSpec = tween(durationMillis = 500), label = "onSurfaceVariant")
    val animatedError = animateColorAsState(targetValue = targetColorScheme.error, animationSpec = tween(durationMillis = 500), label = "error")
    val animatedOnError = animateColorAsState(targetValue = targetColorScheme.onError, animationSpec = tween(durationMillis = 500), label = "onError")
    val animatedOutline = animateColorAsState(targetValue = targetColorScheme.outline, animationSpec = tween(durationMillis = 500), label = "outline")

    val colorScheme = targetColorScheme.copy(
        primary = animatedPrimary.value,
        onPrimary = animatedOnPrimary.value,
        primaryContainer = animatedPrimaryContainer.value,
        onPrimaryContainer = animatedOnPrimaryContainer.value,
        secondary = animatedSecondary.value,
        onSecondary = animatedOnSecondary.value,
        background = animatedBackground.value,
        onBackground = animatedOnBackground.value,
        surface = animatedSurface.value,
        onSurface = animatedOnSurface.value,
        surfaceVariant = animatedSurfaceVariant.value,
        onSurfaceVariant = animatedOnSurfaceVariant.value,
        error = animatedError.value,
        onError = animatedOnError.value,
        outline = animatedOutline.value
    )

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
