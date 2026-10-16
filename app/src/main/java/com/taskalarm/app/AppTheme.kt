package com.taskalarm.app

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Interpreted from the supplied Folderstorm screenshot, rather than the site's CSS.
internal object Folderstorm {
  val Background = Color(0xff111015)
  val Panel = Color(0xff211b29)
  val Inset = Color(0xff1b1821)
  val Lavender = Color(0xffc5a8f4)
  val PaleLavender = Color(0xffd8c4f2)
  val Plum = Color(0xff3c2c4f)
  val Text = Color(0xfff7f0f6)
  val Muted = Color(0xffb8a9bf)
  val Outline = Color(0xff8f7a9e)
  val Border = Color(0xff554263)
  val Gold = Color(0xffefc86b)
}

private val FolderstormColors = darkColorScheme(
  primary = Folderstorm.Lavender, onPrimary = Folderstorm.Background,
  primaryContainer = Folderstorm.Plum, onPrimaryContainer = Folderstorm.Text,
  inversePrimary = Color(0xff654584),
  secondary = Folderstorm.PaleLavender, onSecondary = Folderstorm.Background,
  secondaryContainer = Color(0xff342b40), onSecondaryContainer = Folderstorm.Text,
  tertiary = Folderstorm.Gold, onTertiary = Folderstorm.Background,
  tertiaryContainer = Color(0xff4a391e), onTertiaryContainer = Color(0xffffe6ad),
  background = Folderstorm.Background, onBackground = Folderstorm.Text,
  surface = Folderstorm.Panel, onSurface = Folderstorm.Text,
  surfaceVariant = Color(0xff3a3044), onSurfaceVariant = Folderstorm.Muted,
  surfaceTint = Folderstorm.Lavender,
  inverseSurface = Folderstorm.Text, inverseOnSurface = Folderstorm.Panel,
  outline = Folderstorm.Outline, outlineVariant = Folderstorm.Border,
  error = Color(0xffffb4bf), onError = Color(0xff650b28),
  errorContainer = Color(0xff852440), onErrorContainer = Color(0xffffd9df),
  scrim = Color.Black,
  surfaceBright = Color(0xff3c3346), surfaceDim = Folderstorm.Background,
  surfaceContainerLowest = Folderstorm.Background,
  surfaceContainerLow = Folderstorm.Inset,
  surfaceContainer = Folderstorm.Panel,
  surfaceContainerHigh = Color(0xff2b2235),
  surfaceContainerHighest = Color(0xff342b40),
)

@Composable
internal fun TaskAlarmTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = FolderstormColors, shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(20.dp),
  ), content = content)
}
