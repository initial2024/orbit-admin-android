package de.kango99w.orbitadmin

import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** App-only appearance; never changes Android system colors or third-party pages. */
enum class OrbitAppearanceMode(val title: String, val description: String) {
    SYSTEM("跟随系统", "随设备明暗变化，支持系统动态配色"),
    DAY("日光", "清爽明亮的邮件视图"),
    NIGHT("深海", "深蓝底色与清晰的文字对比"),
    SOFT("柔和护眼", "低饱和暖色阅读配色（不替代系统护眼功能）");

    companion object {
        fun parse(value: String?): OrbitAppearanceMode =
            entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}

private val daylight = lightColorScheme(
    primary=Color(0xFF2658A2), onPrimary=Color.White,
    primaryContainer=Color(0xFFD8E6FF), onPrimaryContainer=Color(0xFF0E3265),
    secondary=Color(0xFF51657E),
    secondaryContainer=Color(0xFFDBE5F2),
    background=Color(0xFFF7F9FC), surface=Color(0xFFFFFFFF),
    surfaceVariant=Color(0xFFE9EFF6), onSurface=Color(0xFF172436),
    onSurfaceVariant=Color(0xFF526274),
    outline=Color(0xFF8493A5), error=Color(0xFFB3261E)
)
private val midnight = darkColorScheme(
    primary=Color(0xFFA8C8FA), onPrimary=Color(0xFF123458),
    primaryContainer=Color(0xFF24476C), onPrimaryContainer=Color(0xFFD2E5FF),
    secondary=Color(0xFFB2C7DC),
    secondaryContainer=Color(0xFF344B60),
    background=Color(0xFF101923), surface=Color(0xFF18232F),
    surfaceVariant=Color(0xFF243342), onSurface=Color(0xFFE7EEF6),
    onSurfaceVariant=Color(0xFFC4D0DD),
    outline=Color(0xFF8898AA), error=Color(0xFFFFB4A9)
)
private val soft = lightColorScheme(
    primary=Color(0xFF77613F), onPrimary=Color(0xFFFFFEFA),
    primaryContainer=Color(0xFFEDE1C9), onPrimaryContainer=Color(0xFF44361F),
    secondary=Color(0xFF6A7160),
    secondaryContainer=Color(0xFFE1E6D4),
    background=Color(0xFFF8F5EC), surface=Color(0xFFFFFBF2),
    surfaceVariant=Color(0xFFF0EADB), onSurface=Color(0xFF302E29),
    onSurfaceVariant=Color(0xFF59574F),
    outline=Color(0xFF928C7D), error=Color(0xFFAD433D)
)

@Composable
fun OrbitMailTheme(mode: OrbitAppearanceMode, content: @Composable () -> Unit) {
    val context=LocalContext.current
    val systemDark=isSystemInDarkTheme()
    val colorScheme=when (mode) {
        OrbitAppearanceMode.SYSTEM -> if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if(systemDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else if(systemDark) midnight else daylight
        OrbitAppearanceMode.DAY -> daylight
        OrbitAppearanceMode.NIGHT -> midnight
        OrbitAppearanceMode.SOFT -> soft
    }
    MaterialTheme(
        colorScheme=colorScheme,
        shapes=Shapes(
            small=RoundedCornerShape(12.dp),
            medium=RoundedCornerShape(18.dp),
            large=RoundedCornerShape(24.dp)
        ),
        content=content
    )
}
