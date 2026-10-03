package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun rememberNativeAppearanceStore(team: NativeTeamScope?): NativeMacAppearanceStore? {
    val context = LocalContext.current.applicationContext
    return remember(context, team?.userId, team?.teamId) { team?.let { NativeMacAppearanceStore.create(context, it) } }
}

@Composable
internal fun nativeMacAppearances(team: NativeTeamScope?): NativeMacAppearances =
    rememberNativeAppearanceStore(team)?.state?.collectAsState()?.value ?: NativeMacAppearances()

internal object NativeMacAvatarColors {
    val palettes = listOf(
        listOf(0xFF0A84FF, 0xFF64D2FF), listOf(0xFF30D158, 0xFF40C8E0),
        listOf(0xFFFF9F0A, 0xFFFFD60A), listOf(0xFFBF5AF2, 0xFF5E5CE6),
        listOf(0xFFFF375F, 0xFFFF453A), listOf(0xFF63E6E2, 0xFF30D158),
        listOf(0xFF5E5CE6, 0xFF0A84FF), listOf(0xFFAC8E68, 0xFFFF9F0A)
    ).map { pair -> pair.map(::Color) }
    fun colors(appearance: NativeMacAppearance, identity: String, index: Int? = null): List<Color> {
        val custom = appearance.color
        if (custom?.startsWith("#") == true) {
            val color = Color(0xFF000000 or custom.substring(1).toLong(16))
            return listOf(color, color.copy(alpha = .72f))
        }
        val slot = custom?.removePrefix("palette:")?.toIntOrNull() ?: index ?: NativeMacAppearance.paletteSlot(identity)
        return palettes[Math.floorMod(slot, palettes.size)]
    }
}

@Composable
internal fun NativeMacGlyph(icon: String?, modifier: Modifier = Modifier, defaultSymbol: String = "desktopcomputer") {
    val selected = icon ?: defaultSymbol
    if (selected.any { it.code > 127 }) Text(selected, modifier, color = Color.White, fontSize = 19.sp, maxLines = 1)
    else {
        val drawable = when (selected) {
            "macbook" -> R.drawable.ic_computer_macbook
            "laptopcomputer" -> R.drawable.ic_computer_laptop
            "server.rack" -> R.drawable.ic_computer_server
            "terminal" -> R.drawable.ic_computer_terminal
            "display" -> R.drawable.ic_computer_display
            "bolt.fill" -> R.drawable.ic_computer_bolt
            "star.fill" -> R.drawable.ic_computer_star
            "heart.fill" -> R.drawable.ic_computer_heart
            "flame.fill" -> R.drawable.ic_computer_flame
            else -> R.drawable.ic_computer_desktop
        }
        Icon(painterResource(drawable), null, modifier.size(22.dp), tint = Color.White)
    }
}

@Composable
internal fun NativeMacAvatar(appearance: NativeMacAppearance, identity: String, modifier: Modifier = Modifier.size(37.dp),
    index: Int? = null, defaultSymbol: String = "desktopcomputer") {
    Box(modifier.background(Brush.linearGradient(NativeMacAvatarColors.colors(appearance, identity, index)), CircleShape),
        contentAlignment = Alignment.Center) { NativeMacGlyph(appearance.icon, defaultSymbol = defaultSymbol) }
}
