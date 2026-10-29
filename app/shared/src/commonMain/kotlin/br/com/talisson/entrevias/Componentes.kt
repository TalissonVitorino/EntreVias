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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import br.com.talisson.entrevias.domain.RotuloRisco

@Composable
fun Eyebrow(t: String) = T(t, 14, FontWeight.SemiBold, cores().primary)

@Composable
fun Cartao(mod: Modifier = Modifier, raio: Int = 28, pad: Int = 24, borda: Boolean = false, content: @Composable () -> Unit) {
    val c = cores()
    val forma = RoundedCornerShape(raio.dp)
    Column(
        mod.fillMaxWidth().clip(forma).background(c.surface)
            .then(if (borda) Modifier.border(1.dp, c.line, forma) else Modifier)
            .padding(pad.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
fun Botao(
    txt: String, onClick: () -> Unit, mod: Modifier = Modifier,
    primario: Boolean = false, ativo: Boolean = false, pequeno: Boolean = false, grande: Boolean = false,
) {
    val c = cores()
    val bg = if (primario) c.primary else if (ativo) c.soft else c.field
    val borda = if (primario) c.primary else if (ativo) c.primary else c.field
    val fg = if (primario) c.onPrimary else c.primary
    val forma = RoundedCornerShape(999.dp)
    Box(
        mod.heightIn(min = if (grande) 54.dp else if (pequeno) 42.dp else 48.dp)
            .clip(forma).background(bg).border(1.dp, borda, forma).clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { T(txt, if (grande) 17 else if (pequeno) 14 else 15, FontWeight.SemiBold, fg) }
}

@Composable
fun Link(txt: String, onClick: () -> Unit) =
    T(txt, 15, FontWeight.SemiBold, cores().primary, Modifier.clickable(onClick = onClick))

@Composable
fun Campo(rotulo: String, valor: String, opcoes: List<Pair<String, String>>, onEscolher: (String) -> Unit) {
    val c = cores()
    var aberto by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        T(rotulo, 14, FontWeight.Medium, c.muted)
        Box {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp)).background(c.field)
                    .clickable { aberto = true }.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
            ) { T(valor, 17, FontWeight.Medium); T("▾", 14, cor = c.muted) }
            DropdownMenu(expanded = aberto, onDismissRequest = { aberto = false }) {
                opcoes.forEach { (id, nome) -> DropdownMenuItem(text = { Text(nome) }, onClick = { aberto = false; onEscolher(id) }) }
            }
        }
    }
}

@Composable
fun Etiqueta(txt: String, simples: Boolean = false) {
    val c = cores()
    if (simples) T(txt, 13, FontWeight.Medium, c.muted)
    else Box(Modifier.clip(RoundedCornerShape(10.dp)).background(c.field).padding(horizontal = 11.dp, vertical = 5.dp)) {
        T(txt, 13, FontWeight.SemiBold)
    }
}

/** RN13: selo do risco da conexão. A cor nunca vai sozinha: sempre com símbolo e texto (RNF04). */
@Composable
fun SeloRisco(rotulo: RotuloRisco, nota: Int) {
    val c = cores()
    val (cor, simbolo) = when (rotulo) {
        RotuloRisco.SEGURO -> c.ok to "✓"
        RotuloRisco.ATENCAO -> c.atencao to "▲"
        RotuloRisco.ARRISCADO -> c.erro to "✕"
    }
    val forma = RoundedCornerShape(10.dp)
    Row(
        Modifier.clip(forma).border(1.dp, cor, forma).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        T(simbolo, 13, FontWeight.Bold, cor)
        T("${rotulo.texto} · $nota", 13, FontWeight.SemiBold, cor)
    }
}

@Composable
fun Aviso(titulo: String, linhas: List<String>) {
    val c = cores()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(16.dp)).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        T("⚠  $titulo", 15, FontWeight.SemiBold)
        linhas.forEachIndexed { i, l -> T(l, if (i == 0) 15 else 13, cor = if (i == 0) c.fg else c.muted) }
    }
}

/** Logo do protótipo: ônibus com setas, em volta do azul primário. */
@Composable
fun Logo() {
    val c = cores()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Canvas(Modifier.size(34.dp, 24.dp)) {
            val s = size.width / 34f
            val st = Stroke(width = 2.4f * s, cap = StrokeCap.Round)
            drawRoundRect(c.primary, Offset(2f * s, 3f * s), Size(22f * s, 15f * s), CornerRadius(4f * s), style = st)
            drawLine(c.primary, Offset(2f * s, 11f * s), Offset(24f * s, 11f * s), 2.4f * s)
            drawCircle(c.primary, 2.2f * s, Offset(8f * s, 20f * s))
            drawCircle(c.primary, 2.2f * s, Offset(18f * s, 20f * s))
            drawLine(c.primary, Offset(27f * s, 8f * s), Offset(32f * s, 8f * s), 2f * s, StrokeCap.Round)
            drawLine(c.primary, Offset(32f * s, 8f * s), Offset(29.6f * s, 5.6f * s), 2f * s, StrokeCap.Round)
            drawLine(c.primary, Offset(32f * s, 8f * s), Offset(29.6f * s, 10.4f * s), 2f * s, StrokeCap.Round)
            drawLine(c.primary, Offset(27f * s, 14f * s), Offset(32f * s, 14f * s), 2f * s, StrokeCap.Round)
            drawLine(c.primary, Offset(32f * s, 14f * s), Offset(29.6f * s, 11.6f * s), 2f * s, StrokeCap.Round)
            drawLine(c.primary, Offset(32f * s, 14f * s), Offset(29.6f * s, 16.4f * s), 2f * s, StrokeCap.Round)
        }
        Row {
            T("EntreVias", 22, FontWeight.Bold, ls = -0.7f)
            T(".", 22, FontWeight.Bold, c.primary)
        }
    }
}

@Composable
fun Pilula(txt: String) {
    val c = cores()
    Row(
        Modifier.clip(RoundedCornerShape(999.dp)).background(c.field)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(c.muted))
        T(txt, 13, FontWeight.Medium)
    }
}

@Composable
fun IconeBuscar(cor: Color) {
    Canvas(Modifier.size(22.dp)) {
        val s = size.width / 24f
        drawCircle(cor, 7f * s, Offset(11f * s, 11f * s), style = Stroke(2f * s))
        drawLine(cor, Offset(20f * s, 20f * s), Offset(16.5f * s, 16.5f * s), 2f * s, StrokeCap.Round)
    }
}

@Composable
fun IconeSalvos(cor: Color) {
    Canvas(Modifier.size(22.dp)) {
        val s = size.width / 24f
        val p = androidx.compose.ui.graphics.Path().apply {
            moveTo(6f * s, 3f * s); lineTo(18f * s, 3f * s); lineTo(18f * s, 21f * s)
            lineTo(12f * s, 17f * s); lineTo(6f * s, 21f * s); close()
        }
        drawPath(p, cor, style = Stroke(2f * s))
    }
}
