package br.com.talisson.entrevias

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/** Mesmas cores do protótipo EntreVias (claro e escuro). */
class Cores(
    val bg: Color, val surface: Color, val fg: Color, val muted: Color, val line: Color,
    val primary: Color, val onPrimary: Color, val soft: Color, val field: Color,
    val noteBg: Color, val ok: Color, val erro: Color,
    /** Âmbar do selo de risco "Atenção" (contraste de texto sobre a superfície, claro e escuro). */
    val atencao: Color,
)

val Claro = Cores(
    bg = Color(0xFFF2F2F7), surface = Color(0xFFFFFFFF), fg = Color(0xFF1D1D1F), muted = Color(0xFF86868B),
    line = Color(0xFFE5E5EA), primary = Color(0xFF0071E3), onPrimary = Color(0xFFFFFFFF), soft = Color(0xFFE5F1FF),
    field = Color(0xFFF2F2F7), noteBg = Color(0xFFF2F2F7), ok = Color(0xFF248A3D), erro = Color(0xFFD70015),
    atencao = Color(0xFFA85A00),
)
val Escuro = Cores(
    bg = Color(0xFF000000), surface = Color(0xFF1C1C1E), fg = Color(0xFFF5F5F7), muted = Color(0xFF98989D),
    line = Color(0xFF38383A), primary = Color(0xFF0A84FF), onPrimary = Color(0xFFFFFFFF), soft = Color(0xFF0A2A4D),
    field = Color(0xFF2C2C2E), noteBg = Color(0xFF1C1C1E), ok = Color(0xFF30D158), erro = Color(0xFFFF453A),
    atencao = Color(0xFFFF9F0A),
)

val LocalCores = staticCompositionLocalOf { Claro }

@Composable
fun cores(): Cores = LocalCores.current

@Composable
fun EntreViasTheme(content: @Composable () -> Unit) {
    val c = if (isSystemInDarkTheme()) Escuro else Claro
    CompositionLocalProvider(LocalCores provides c) {
        MaterialTheme { content() }
    }
}

/** Texto com os atalhos que o protótipo usa (tamanho, peso, cor, espaçamento). */
@Composable
fun T(
    t: String, size: Int = 15, w: FontWeight = FontWeight.Normal, cor: Color = Color.Unspecified,
    mod: Modifier = Modifier, align: TextAlign? = null, ls: Float = 0f, lh: Int = 0,
) {
    Text(
        t, modifier = mod, color = if (cor == Color.Unspecified) cores().fg else cor,
        fontSize = size.sp, fontWeight = w, textAlign = align, letterSpacing = ls.sp,
        lineHeight = if (lh > 0) lh.sp else TextUnit.Unspecified,
    )
}
