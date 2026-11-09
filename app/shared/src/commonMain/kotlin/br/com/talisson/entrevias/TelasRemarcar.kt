package br.com.talisson.entrevias

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import br.com.talisson.entrevias.domain.Alternativa
import br.com.talisson.entrevias.domain.RegrasDePreco

// ---------------------------------------------------------------- REMARCAR VIAGEM
// Uma vez por compra, até 2 horas antes da saída, sem taxa, para uma viagem da mesma rota de preço igual ou menor.
@Composable
fun TelaRemarcar(e: Estado) {
    val c = cores()
    val p = e.pedidoAberto
    if (p == null) { Cartao { T("Nenhuma compra aberta", 22, FontWeight.Bold); Botao("Ver minhas viagens", { e.abrirHistorico() }, primario = true) }; return }
    val escolhida = e.alternativaEscolhida
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Link(if (escolhida == null) "‹  Compra" else "‹  Outras viagens") { if (escolhida == null) e.abrirDetalhe(p) else e.voltarDasAlternativas() }
        Column {
            Eyebrow("Compra ${p.codigo}")
            Spacer(Modifier.height(8.dp))
            T("Remarcar viagem", 30, FontWeight.Bold, ls = -0.9f)
            Spacer(Modifier.height(6.dp))
            T("Uma vez por compra, até ${RegrasDePreco.REMARCACAO_ANTECEDENCIA_SEG / 3600} horas antes da saída, sem taxa. A nova viagem precisa custar o mesmo ou menos, e o valor que você pagou não muda.", 15, cor = c.muted)
        }
        val remarcaveis = e.trechosRemarcaveis(p)
        if (remarcaveis.isEmpty()) {
            T("Não há trecho que ainda possa ser remarcado.", 15, cor = c.muted)
        } else if (escolhida == null) EscolherViagem(e, p, remarcaveis.map { it.ordem })
        else EscolherPoltronas(e, escolhida)
    }
}

@Composable
private fun EscolherViagem(e: Estado, p: br.com.talisson.entrevias.domain.Pedido, ordens: List<Int>) {
    val c = cores()
    if (ordens.size > 1) {
        Cartao(raio = 24, pad = 20, borda = true) {
            T("Qual trecho?", 17, FontWeight.Bold)
            p.trechos.filter { it.ordem in ordens }.forEach { t ->
                Botao("${e.cidade(t.origem)} → ${e.cidade(t.destino)} · ${Tempo.dataHora(t.saidaReal)}", { e.escolherTrechoDaRemarcacao(t.ordem) }, Modifier.fillMaxWidth(), ativo = e.remarcarTrecho == t.ordem, pequeno = true)
            }
        }
    } else p.trechos.firstOrNull { it.ordem == ordens.first() }?.let { t ->
        T("${e.cidade(t.origem)} → ${e.cidade(t.destino)} · saída atual ${Tempo.dataHora(t.saidaReal)}", 15, FontWeight.SemiBold)
    }
    when (val carga = e.alternativasCarga) {
        is Carga.Carregando -> T("Procurando viagens…", 15, cor = c.muted)
        is Carga.Erro -> T("✕  ${carga.mensagem}", 14, FontWeight.SemiBold, c.erro)
        Carga.Pronto -> if (e.alternativas.isEmpty()) T("Não há outra viagem dessa rota, de preço igual ou menor, que encaixe com o resto da sua compra.", 15, cor = c.muted)
    }
    e.alternativas.toList().forEach { a -> CartaoAlternativa(e, a) }
}

@Composable
private fun CartaoAlternativa(e: Estado, a: Alternativa) {
    val c = cores()
    Cartao(raio = 24, pad = 20, borda = true) {
        T("${Tempo.dataHora(a.saidaEm)} → ${Tempo.hora(a.chegadaEm)}", 17, FontWeight.Bold, ls = -0.3f)
        T("${a.empresa} · ${a.categoria} · ${centavosParaReais(a.precoCentavos)} por pessoa", 14, cor = c.muted)
        Botao("Escolher poltronas", { e.escolherAlternativa(a) }, pequeno = true, primario = true)
    }
}

@Composable
private fun EscolherPoltronas(e: Estado, a: Alternativa) {
    val c = cores()
    val m = e.remarcarMapa
    val minhas = e.remarcarPoltronas
    val n = e.poltronasDaRemarcacao
    T("${Tempo.dataHora(a.saidaEm)} · ${a.empresa}", 16, FontWeight.SemiBold)
    T("Escolha $n ${if (n == 1) "poltrona" else "poltronas"} · ${minhas.size} escolhida${if (minhas.size == 1) "" else "s"}", 16, FontWeight.SemiBold)
    if (m == null) T("Carregando o mapa…", 15, cor = c.muted)
    else Cartao {
        for (linha in 0 until m.total / 4) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                for (col in 0 until 4) {
                    val num = linha * 4 + col + 1
                    if (col == 2) Spacer(Modifier.width(28.dp))
                    Poltrona(num, ocupada = num in m.ocupadas, pcd = num in m.pcd, bloqueadaPcd = num in m.pcd && !e.remarcacaoComAcessibilidade, selecionada = num in minhas) { e.alternarPoltronaDaRemarcacao(num) }
                    if (col != 3 && col != 1) Spacer(Modifier.width(8.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Legenda(c.field, "livre"); Legenda(c.primary, "sua escolha"); Legenda(c.line, "ocupada"); T("♿ PcD", 13, cor = c.muted)
        }
    }
    T("Seus bilhetes atuais deixam de valer: serão refeitos com a nova viagem e as novas poltronas.", 13, cor = c.muted)
    e.compraErro?.let { T("✕  $it", 14, FontWeight.SemiBold, c.erro) }
    Botao(if (e.compraOcupada) "Remarcando…" else "Confirmar remarcação", { e.confirmarRemarcacao() }, Modifier.fillMaxWidth(), primario = true, grande = true)
}
