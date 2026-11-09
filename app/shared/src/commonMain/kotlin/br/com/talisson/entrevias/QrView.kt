package br.com.talisson.entrevias

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import br.com.talisson.entrevias.domain.Qr

/**
 * QR Code do [texto], sempre preto sobre branco (os leitores precisam desse contraste, mesmo no tema escuro) e com a
 * borda clara de 4 módulos que o padrão exige. Se o texto não couber, não desenha nada (a tela segue com o "copia e cola").
 */
@Composable
fun QrView(texto: String, lado: Dp = 220.dp, modifier: Modifier = Modifier) {
    val qr = remember(texto) { if (Qr.cabe(texto)) Qr.gerar(texto) else null } ?: return
    Box(modifier.size(lado).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(lado / 20)) {
        Canvas(Modifier.size(lado - lado / 10)) {
            val total = qr.tamanho + 8                              // 4 módulos de borda em cada lado
            val m = size.width / total
            for (y in 0 until qr.tamanho) for (x in 0 until qr.tamanho) {
                if (qr.escuro(x, y)) drawRect(Color.Black, Offset((x + 4) * m, (y + 4) * m), Size(m + 0.5f, m + 0.5f))
            }
        }
    }
}
