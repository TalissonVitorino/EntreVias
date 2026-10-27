package br.com.talisson.entrevias

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2

private const val DURACAO_MS = 7000
private val FUNDO = Color(0xFF071327)
private val AZUL = Color(0xFF4DA3FF)
private val AZUL_APP = Color(0xFF0071E3)
private val CINZA = Color(0xFF9DB4D6)

private fun faixa(t: Float, de: Float, ate: Float) = ((t - de) / (ate - de)).coerceIn(0f, 1f)
private fun suave(p: Float) = p * p * (3f - 2f * p)

/** Ponto da rota (em frações da tela) para u em 0..1: curva de Bézier cúbica. */
private fun ponto(u: Float): Offset {
    val a = (1 - u); val b = a * a * a; val c = 3 * a * a * u; val d = 3 * a * u * u; val e = u * u * u
    return Offset(b * 0.12f + c * 0.35f + d * 0.62f + e * 0.88f, b * 0.60f + c * 0.34f + d * 0.74f + e * 0.42f)
}

private val PARADAS = listOf(0f to "São Paulo", 0.5f to "Curitiba", 1f to "Florianópolis")
private val PARTIDAS = listOf(
    Triple("06:30", "RIO DE JANEIRO", "PLAT 3"),
    Triple("07:15", "BELO HORIZONTE", "PLAT 7"),
    Triple("08:00", "FLORIANÓPOLIS", "PLAT 1"),
    Triple("09:20", "PORTO ALEGRE", "PLAT 2"),
)

/**
 * Abertura em três tempos: (1) o ônibus percorre a rota entre as cidades;
 * (2) a rota vira um bilhete com a marca; (3) o painel de partidas acende. Toque para pular.
 */
@Composable
fun Splash(onFim: () -> Unit) {
    val prog = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        prog.animateTo(1f, tween(DURACAO_MS, easing = LinearEasing))
        onFim()
    }
    val t = prog.value
    val saida = 1f - faixa(t, 0.94f, 1f)
    val rotulos = suave(faixa(t, 0.06f, 0.20f)) * (1f - suave(faixa(t, 0.40f, 0.50f)))
    val bilhete = suave(faixa(t, 0.44f, 0.64f))

    BoxWithConstraints(Modifier.fillMaxSize().alpha(saida).pointerInput(Unit) { detectTapGestures { onFim() } }) {
        val larg = maxWidth
        val alto = maxHeight
        Canvas(Modifier.fillMaxSize()) { fundoERota(t) }

        // nomes das cidades (somem quando a rota vira bilhete)
        PARADAS.forEach { (u, nome) ->
            val p = ponto(u)
            Box(Modifier.offset(x = larg * p.x - 60.dp, y = alto * p.y + 22.dp).width(120.dp).alpha(rotulos), contentAlignment = Alignment.Center) {
                T(nome, 12, FontWeight.Medium, CINZA)
            }
        }

        // bilhete
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = alto * 0.12f + 40.dp * (1f - bilhete))
                .fillMaxWidth(0.86f).alpha(bilhete),
        ) { Bilhete(t) }

        // painel de partidas
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = alto * 0.12f + 330.dp).fillMaxWidth(0.86f),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            PARTIDAS.forEachIndexed { i, (h, c, p) ->
                val a = suave(faixa(t, 0.62f + i * 0.055f, 0.74f + i * 0.055f))
                Row(
                    Modifier.fillMaxWidth().offset(y = 12.dp * (1f - a)).alpha(a)
                        .clip(RoundedCornerShape(10.dp)).background(Color(0xFF111C33)).padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(h, color = Color(0xFFFFD60A), fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(14.dp))
                    Text(c, color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                    Text(p, color = CINZA, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
            T("Seu próximo destino começa aqui.", 14, FontWeight.Normal, CINZA,
                mod = Modifier.fillMaxWidth().padding(top = 10.dp).alpha(suave(faixa(t, 0.84f, 0.94f))), align = TextAlign.Center)
        }
    }
}

@Composable
private fun Bilhete(t: Float) {
    Column(Modifier.clip(RoundedCornerShape(26.dp)).background(Color.White)) {
        Row(Modifier.fillMaxWidth().background(AZUL_APP).padding(horizontal = 22.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(34.dp, 24.dp)) { logoBranco() }
            Spacer(Modifier.width(10.dp))
            Row(Modifier.weight(1f)) {
                T("EntreVias", 22, FontWeight.Bold, Color.White, ls = -0.7f)
                T(".", 22, FontWeight.Bold, Color(0xFFBFE0FF))
            }
            T("BILHETE DEMO", 10, FontWeight.Bold, Color.White.copy(alpha = 0.8f))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column {
                T("ORIGEM", 10, FontWeight.Bold, Color(0xFF86868B))
                T("SP", 38, FontWeight.Bold, Color(0xFF1D1D1F), ls = -1f)
            }
            Canvas(Modifier.weight(1f).height(20.dp).padding(horizontal = 12.dp)) {
                val y = size.height / 2f
                val n = 9
                for (i in 0 until n) drawLine(Color(0xFFC7C7CC), Offset(size.width * i / n, y), Offset(size.width * (i + 0.55f) / n, y), 3f, StrokeCap.Round)
                drawCircle(AZUL_APP, 6f, Offset(size.width * (0.15f + 0.7f * suave(faixa(t, 0.50f, 0.80f))), y))
            }
            Column(horizontalAlignment = Alignment.End) {
                T("DESTINO", 10, FontWeight.Bold, Color(0xFF86868B))
                T("FLN", 38, FontWeight.Bold, Color(0xFF1D1D1F), ls = -1f)
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, bottom = 16.dp)) {
            T("Dados demonstrativos", 11, FontWeight.Normal, Color(0xFF86868B))
        }
    }
}

private fun DrawScope.logoBranco() {
    val c = Color.White
    val s = size.width / 34f
    val st = Stroke(width = 2.4f * s, cap = StrokeCap.Round)
    drawRoundRect(c, Offset(2f * s, 3f * s), Size(22f * s, 15f * s), CornerRadius(4f * s), style = st)
    drawLine(c, Offset(2f * s, 11f * s), Offset(24f * s, 11f * s), 2.4f * s)
    drawCircle(c, 2.2f * s, Offset(8f * s, 20f * s))
    drawCircle(c, 2.2f * s, Offset(18f * s, 20f * s))
    for (y in listOf(8f, 14f)) {
        drawLine(c, Offset(27f * s, y * s), Offset(32f * s, y * s), 2f * s, StrokeCap.Round)
        drawLine(c, Offset(32f * s, y * s), Offset(29.6f * s, (y - 2.4f) * s), 2f * s, StrokeCap.Round)
        drawLine(c, Offset(32f * s, y * s), Offset(29.6f * s, (y + 2.4f) * s), 2f * s, StrokeCap.Round)
    }
}

private fun DrawScope.fundoERota(t: Float) {
    val w = size.width
    val h = size.height
    drawRect(FUNDO)
    drawCircle(
        Brush.radialGradient(listOf(Color(0x3D4DA3FF), Color(0x00071327)), center = Offset(w / 2f, h * 0.42f), radius = w * 0.85f),
        radius = w * 0.85f, center = Offset(w / 2f, h * 0.42f),
    )
    // mapa de pontos
    val passo = 22.dp.toPx()
    val pts = ArrayList<Offset>()
    var gx = passo / 2f
    while (gx < w) { var gy = passo / 2f; while (gy < h) { pts.add(Offset(gx, gy)); gy += passo }; gx += passo }
    drawPoints(pts, PointMode.Points, Color.White.copy(alpha = 0.07f), 3f, StrokeCap.Round)

    val rota = 1f - suave(faixa(t, 0.40f, 0.50f))
    if (rota <= 0f) return
    val p = suave(faixa(t, 0.06f, 0.40f))
    val traco = 4.dp.toPx()
    val n = 80
    fun px(u: Float) = ponto(u).let { Offset(it.x * w, it.y * h) }
    for (i in 0 until n) drawLine(Color.White.copy(alpha = 0.16f * rota), px(i / n.toFloat()), px((i + 1) / n.toFloat()), traco, StrokeCap.Round)
    val ate = (p * n).toInt()
    for (i in 0 until ate) drawLine(AZUL.copy(alpha = rota), px(i / n.toFloat()), px((i + 1) / n.toFloat()), traco + 1f, StrokeCap.Round)

    for ((u, _) in PARADAS) {
        val c = px(u)
        val on = p >= u && (u > 0f || t > 0.06f)
        if (on) drawCircle(AZUL.copy(alpha = 0.28f * rota), 18.dp.toPx(), c)
        drawCircle(if (on) AZUL.copy(alpha = rota) else Color(0xFF1B2B47).copy(alpha = rota), 9.dp.toPx(), c)
        drawCircle(Color.White.copy(alpha = (if (on) 1f else 0.4f) * rota), 9.dp.toPx(), c, style = Stroke(2.dp.toPx()))
    }

    // ônibus acompanhando a tangente da curva
    val a = px((p - 0.01f).coerceAtLeast(0f)); val b = px((p + 0.01f).coerceAtMost(1f))
    val ang = (atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble()) * 180.0 / kotlin.math.PI).toFloat().coerceIn(-35f, 35f)
    val bc = px(p)
    val bw = 64.dp.toPx(); val bh = bw * 0.62f
    rotate(ang, bc) { onibusIcone(bc.x - bw / 2f, bc.y - bh - 6.dp.toPx(), bw, bh, rota) }
}

/** Ícone simples, de traço limpo, no mesmo estilo do logo do app. */
private fun DrawScope.onibusIcone(x: Float, y: Float, w: Float, h: Float, a: Float) {
    val c = Color.White.copy(alpha = a)
    val azul = AZUL.copy(alpha = a)
    drawRoundRect(c, Offset(x, y), Size(w, h * 0.82f), CornerRadius(h * 0.16f))
    drawRect(azul, Offset(x, y + h * 0.50f), Size(w, h * 0.12f))
    val jw = w * 0.17f
    for (i in 0 until 4) {
        drawRoundRect(Color(0xFF0E1F3D).copy(alpha = a), Offset(x + w * 0.07f + i * (jw + w * 0.04f), y + h * 0.12f), Size(jw, h * 0.28f), CornerRadius(h * 0.04f))
    }
    drawRoundRect(Color(0xFF0E1F3D).copy(alpha = a), Offset(x + w * 0.80f, y + h * 0.12f), Size(w * 0.14f, h * 0.38f), CornerRadius(h * 0.04f))
    drawCircle(Color(0xFFFFE08A).copy(alpha = a), h * 0.05f, Offset(x + w * 0.965f, y + h * 0.68f))
    for (fx in listOf(0.24f, 0.74f)) {
        drawCircle(Color(0xFF0A1226).copy(alpha = a), h * 0.13f, Offset(x + w * fx, y + h * 0.84f))
        drawCircle(Color(0xFF9DB4D6).copy(alpha = a), h * 0.055f, Offset(x + w * fx, y + h * 0.84f))
    }
}
