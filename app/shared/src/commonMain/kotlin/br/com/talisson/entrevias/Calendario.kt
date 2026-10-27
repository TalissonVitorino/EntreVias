package br.com.talisson.entrevias

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/** Calendário mensal. Datas antes de 20/11/2026 ficam bloqueadas; a oferta é a mesma todos os dias (demonstração). */
@Composable
fun CalendarioDialog(atual: Data, onEscolher: (Data) -> Unit, onFechar: () -> Unit) {
    val c = cores()
    val minimo = DATA_INICIAL
    val maximo = minimo.plusDias(150)
    var ano by remember { mutableStateOf(atual.ano) }
    var mes by remember { mutableStateOf(atual.mes) }
    val podeVoltar = ano * 12 + mes > minimo.ano * 12 + minimo.mes
    val podeAvancar = ano * 12 + mes < maximo.ano * 12 + maximo.mes
    Dialog(onDismissRequest = onFechar) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.surface).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).clickable(enabled = podeVoltar) {
                    if (mes == 1) { mes = 12; ano-- } else mes--
                }, contentAlignment = Alignment.Center) { T("‹", 26, cor = if (podeVoltar) c.primary else c.line) }
                T("${MESES[mes - 1].replaceFirstChar { it.uppercase() }} $ano", 17, FontWeight.SemiBold)
                Box(Modifier.size(40.dp).clip(CircleShape).clickable(enabled = podeAvancar) {
                    if (mes == 12) { mes = 1; ano++ } else mes++
                }, contentAlignment = Alignment.Center) { T("›", 26, cor = if (podeAvancar) c.primary else c.line) }
            }
            Row(Modifier.fillMaxWidth()) {
                listOf("D", "S", "T", "Q", "Q", "S", "S").forEach {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { T(it, 13, FontWeight.Medium, c.muted) }
                }
            }
            val primeiro = Data(ano, mes, 1).diaDaSemana()
            val total = diasNoMes(ano, mes)
            val semanas = (primeiro + total + 6) / 7
            for (s in 0 until semanas) {
                Row(Modifier.fillMaxWidth()) {
                    for (w in 0 until 7) {
                        val dia = s * 7 + w - primeiro + 1
                        Box(Modifier.weight(1f).height(44.dp), contentAlignment = Alignment.Center) {
                            if (dia in 1..total) {
                                val d = Data(ano, mes, dia)
                                val livre = d.compareTo(minimo) >= 0 && d.compareTo(maximo) <= 0
                                val sel = d == atual
                                Box(
                                    Modifier.size(40.dp).clip(CircleShape)
                                        .background(if (sel) c.primary else androidx.compose.ui.graphics.Color.Transparent)
                                        .clickable(enabled = livre) { onEscolher(d) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    T(dia.toString(), 16, if (sel) FontWeight.Bold else FontWeight.Normal,
                                        if (sel) c.onPrimary else if (livre) c.fg else c.line, align = TextAlign.Center)
                                }
                            }
                        }
                    }
                }
            }
            T("Datas a partir de ${minimo.br()}. Na demonstração, os horários se repetem todos os dias.", 13, cor = c.muted)
            Botao("Fechar", onFechar, Modifier.fillMaxWidth())
        }
    }
}
