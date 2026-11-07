package br.com.talisson.entrevias

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Campo de texto no mesmo desenho do `Campo` de escolha: fundo `field`, raio 14, 52 dp de altura. */
@Composable
fun CampoTexto(
    rotulo: String, valor: String, onChange: (String) -> Unit,
    dica: String = "", senha: Boolean = false, teclado: KeyboardType = KeyboardType.Text, comErro: Boolean = false,
) {
    val c = cores()
    var visivel by remember { mutableStateOf(false) }
    val forma = RoundedCornerShape(14.dp)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        T(rotulo, 14, FontWeight.Medium, c.muted)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(forma).background(c.field)
                .then(if (comErro) Modifier.border(1.5.dp, c.erro, forma) else Modifier)
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = valor, onValueChange = onChange, singleLine = true,
                textStyle = TextStyle(color = c.fg, fontSize = 17.sp, fontWeight = FontWeight.Medium),
                cursorBrush = SolidColor(c.primary),
                visualTransformation = if (senha && !visivel) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = if (senha) KeyboardType.Password else teclado),
                modifier = Modifier.weight(1f).padding(vertical = 14.dp),
                decorationBox = { campo -> Box { if (valor.isEmpty() && dica.isNotEmpty()) T(dica, 17, FontWeight.Medium, c.muted); campo() } },
            )
            if (senha) T(if (visivel) "ocultar" else "mostrar", 14, FontWeight.SemiBold, c.primary, Modifier.clickable { visivel = !visivel }.padding(start = 12.dp))
        }
    }
}

/** Chave liga/desliga. Nunca nasce ligada quando o consentimento é do usuário (RQ11). */
@Composable
fun Chave(ligada: Boolean, onChange: (Boolean) -> Unit) {
    val c = cores()
    Box(
        Modifier.size(width = 44.dp, height = 26.dp).clip(RoundedCornerShape(999.dp))
            .background(if (ligada) c.primary else c.line).clickable { onChange(!ligada) }.padding(3.dp),
        contentAlignment = if (ligada) Alignment.CenterEnd else Alignment.CenterStart,
    ) { Box(Modifier.size(20.dp).clip(CircleShape).background(Color.White)) }
}

@Composable
fun IconeConta(cor: Color) {
    Canvas(Modifier.size(22.dp)) {
        val s = size.width / 24f
        drawCircle(cor, 4f * s, Offset(12f * s, 8f * s), style = Stroke(2f * s))
        val corpo = Path().apply {
            moveTo(4f * s, 21f * s); cubicTo(4f * s, 14f * s, 20f * s, 14f * s, 20f * s, 21f * s)
        }
        drawPath(corpo, cor, style = Stroke(2f * s, cap = androidx.compose.ui.graphics.StrokeCap.Round))
    }
}

fun celularBr(d: String?): String {
    val n = d.orEmpty().filter { it in '0'..'9' }
    return when (n.length) {
        11 -> "(${n.substring(0, 2)}) ${n.substring(2, 7)}-${n.substring(7)}"
        10 -> "(${n.substring(0, 2)}) ${n.substring(2, 6)}-${n.substring(6)}"
        else -> d.orEmpty()
    }
}

fun cnpjBr(d: String?): String {
    val n = d.orEmpty().filter { it in '0'..'9' }
    return if (n.length == 14) "${n.substring(0, 2)}.${n.substring(2, 5)}.${n.substring(5, 8)}/${n.substring(8, 12)}-${n.substring(12)}" else d.orEmpty()
}
