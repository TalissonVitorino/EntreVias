package br.com.talisson.entrevias

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import br.com.talisson.entrevias.domain.Notificacao
import br.com.talisson.entrevias.domain.Pedido
import br.com.talisson.entrevias.domain.StatusPedido
import br.com.talisson.entrevias.domain.TipoNotificacao

private fun Estado.rotaDe(p: Pedido) = "${cidade(p.trechos.first().origem)} → ${cidade(p.trechos.last().destino)}"

private fun rotuloStatus(p: Pedido) = when (p.status) { StatusPedido.PAGO -> "Paga"; StatusPedido.CANCELADO -> "Cancelada"; StatusPedido.EXPIRADO -> "Expirada"; else -> "Em aberto" }

@Composable
private fun PrecisaDeLoginPos(e: Estado): Boolean {
    if (e.usuario != null) return false
    Cartao {
        T("Entre na sua conta", 22, FontWeight.Bold)
        T("Suas viagens ficam na sua conta.", 15, cor = cores().muted)
        Botao("Ir para a conta", { e.ir(Tela.CONTA) }, primario = true)
    }
    return true
}

// ---------------------------------------------------------------- D1 HISTÓRICO (US12)
@Composable
fun TelaHistorico(e: Estado) {
    val c = cores()
    if (PrecisaDeLoginPos(e)) return
    LaunchedEffect(e.usuario?.id) { e.carregarHistorico() }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column {
            Eyebrow("Viagens")
            Spacer(Modifier.height(8.dp))
            T("Histórico de compras", 30, FontWeight.Bold, ls = -0.9f)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(null to "Todas", "proximas" to "Próximas", "passadas" to "Passadas", "canceladas" to "Canceladas").forEach { (f, nome) ->
                Botao(nome, { e.carregarHistorico(f) }, pequeno = true, ativo = e.filtroHistorico == f)
            }
        }
        when (val carga = e.historicoCarga) {
            is Carga.Carregando -> T("Carregando…", 15, cor = c.muted)
            is Carga.Erro -> {
                T("✕  ${carga.mensagem}", 14, FontWeight.SemiBold, c.erro)
                if (e.bilhetesGuardados.isNotEmpty()) T("Sem conexão: seus bilhetes guardados no aparelho estão abaixo.", 14, cor = c.muted)
            }
            Carga.Pronto -> if (e.historico.isEmpty()) {
                Cartao { T("Nenhuma compra aqui", 20, FontWeight.Bold); T("Quando você comprar, a viagem aparece nesta lista.", 15, cor = c.muted); Botao("Buscar viagens", { e.ir(Tela.BUSCAR) }, primario = true) }
            }
        }
        val lista = if (e.historicoCarga is Carga.Erro) e.bilhetesGuardados.toList() else e.historico.toList()
        lista.forEach { p ->
            Cartao(raio = 24, pad = 20, borda = true, mod = Modifier.clickable { e.abrirDetalhe(p) }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                    T(e.rotaDe(p), 18, FontWeight.Bold, ls = -0.3f, mod = Modifier.weight(1f))
                    Etiqueta(rotuloStatus(p))
                }
                T("${Tempo.data(p.saidaEm).br()} · ${p.codigo}", 14, cor = c.muted)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    T(centavosParaReais(p.totais.totalCentavos), 18, FontWeight.Bold)
                    if (e.estaGuardado(p)) Etiqueta("Sem internet", simples = true)
                }
            }
        }
        if (e.historico.isNotEmpty()) T("Mostrando ${e.historico.size} ${if (e.historico.size == 1) "compra" else "compras"}", 13, cor = c.muted)
    }
}

// ---------------------------------------------------------------- D2 DETALHE DA COMPRA (US13)
@Composable
fun TelaCompraDetalhe(e: Estado) {
    val c = cores()
    val p = e.pedidoAberto
    if (p == null) { Cartao { T("Nenhuma compra aberta", 22, FontWeight.Bold); Botao("Ver minhas viagens", { e.abrirHistorico() }, primario = true) }; return }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Link("‹  Viagens") { e.abrirHistorico() }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) { Eyebrow(p.codigo); Spacer(Modifier.height(8.dp)); T(e.rotaDe(p), 28, FontWeight.Bold, ls = -0.8f, lh = 32) }
            Etiqueta(rotuloStatus(p))
        }
        e.compraAviso?.let { T("✓  $it", 14, FontWeight.SemiBold, c.ok) }
        Cartao {
            T("Trechos", 20, FontWeight.Bold, ls = -0.4f)
            p.trechos.forEach { t ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) { T("${e.cidade(t.origem)} → ${e.cidade(t.destino)}", 16, FontWeight.SemiBold); T("${Tempo.dataHora(t.saidaEm)} · ${t.empresa}", 13, cor = c.muted) }
                    T(centavosParaReais(t.precoUnitCentavos), 15, FontWeight.SemiBold)
                }
            }
            T("Valores por pessoa, por trecho.", 13, cor = c.muted)
        }
        Cartao {
            T("Valores", 20, FontWeight.Bold, ls = -0.4f)
            Linha("Subtotal", centavosParaReais(p.totais.subtotalCentavos))
            if (p.totais.descontoCategoriaCentavos > 0) Linha("Gratuidade e desconto por categoria", "− " + centavosParaReais(p.totais.descontoCategoriaCentavos))
            if (p.cupom != null) Linha("Cupom ${p.cupom}", "− " + centavosParaReais(p.totais.cupomCentavos))
            Linha("Total pago", centavosParaReais(p.totais.totalCentavos), forte = true)
            p.metodo?.let { T("${if (it == "pix") "Pix" else "Cartão"}${p.pagoEm?.let { t -> " · ${Tempo.dataHora(t)}" } ?: ""}", 14, cor = c.muted) }
            p.reembolsoCentavos?.let { T("Reembolso de ${centavosParaReais(it)} em até 72 horas", 14, FontWeight.SemiBold, c.ok) }
        }
        Cartao {
            T("Passageiros e poltronas", 20, FontWeight.Bold, ls = -0.4f)
            p.passageiros.forEachIndexed { i, q ->
                val pol = if (q.categoria == "colo") "colo" else p.trechos.joinToString(" e ") { t -> t.poltronas.getOrNull(p.passageiros.take(i).count { x -> x.categoria != "colo" })?.toString() ?: "-" }
                T("${q.nome} · poltrona $pol", 15, cor = c.fg)
            }
        }
        if (p.status == StatusPedido.PAGO) {
            Botao("Ver bilhetes", { e.abrirBilhetes(p) }, Modifier.fillMaxWidth(), primario = true, grande = true)
            Botao("Cancelar compra", { e.abrirCancelamento(p) }, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Linha(rotulo: String, valor: String, forte: Boolean = false) {
    val c = cores()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        T(rotulo, if (forte) 18 else 15, if (forte) FontWeight.Bold else FontWeight.Medium, if (forte) c.fg else c.muted, Modifier.weight(1f))
        T(valor, if (forte) 24 else 15, FontWeight.Bold)
    }
}

// ---------------------------------------------------------------- D3 CANCELAR E REEMBOLSO (US14)
@Composable
fun TelaCancelar(e: Estado) {
    val c = cores()
    val p = e.pedidoAberto
    if (p == null) { Cartao { T("Nenhuma compra aberta", 22, FontWeight.Bold); Botao("Ver minhas viagens", { e.abrirHistorico() }, primario = true) }; return }
    var motivo by remember { mutableStateOf("Mudei de plano") }
    var confirmando by remember { mutableStateOf(false) }
    val pr = e.previa
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Link("‹  Detalhes da compra") { e.abrirDetalhe(p) }
        Column {
            Eyebrow("Cancelar compra ${p.codigo}")
            Spacer(Modifier.height(8.dp))
            T(if (pr != null) "Você receberá ${centavosParaReais(pr.reembolsoCentavos)}" else "Calculando o reembolso…", 28, FontWeight.Bold, ls = -0.8f, lh = 32)
        }
        if (pr != null) {
            Cartao {
                Linha("Valor pago", centavosParaReais(pr.totalPagoCentavos))
                Linha("Taxa de cancelamento (${pr.taxaPct}%)", "− " + centavosParaReais(pr.taxaCentavos))
                Linha("Reembolso", centavosParaReais(pr.reembolsoCentavos), forte = true)
                T("✓ Em até ${pr.prazoReembolsoHoras} horas, na forma de pagamento de origem.", 14, FontWeight.SemiBold, c.ok)
                T("As poltronas voltam à venda e os ${p.trechos.size} ${if (p.trechos.size == 1) "trecho é cancelado" else "trechos são cancelados juntos"}.", 14, cor = c.muted)
                T("Nesta versão o reembolso é só registrado: nenhum dinheiro de verdade é devolvido.", 13, cor = c.muted)
            }
            T("Motivo (opcional)", 14, FontWeight.Medium, c.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Mudei de plano", "Preço", "Erro na compra", "Outro").forEach { m -> Botao(m, { motivo = m }, pequeno = true, ativo = motivo == m) } }
        }
        e.compraErro?.let { T("✕  $it", 14, FontWeight.SemiBold, c.erro) }
        if (pr != null) {
            if (confirmando) {
                T("Cancelar de verdade? Isso não pode ser desfeito.", 14, FontWeight.SemiBold, c.erro)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Botao(if (e.compraOcupada) "Cancelando…" else "Sim, cancelar", { e.confirmarCancelamento(motivo) }, primario = true, pequeno = true)
                    Botao("Voltar", { confirmando = false }, pequeno = true)
                }
            } else {
                Botao("Confirmar cancelamento", { confirmando = true }, Modifier.fillMaxWidth(), grande = true)
                Botao("Manter minha viagem", { e.abrirDetalhe(p) }, Modifier.fillMaxWidth(), primario = true, grande = true)
            }
        }
    }
}

// ---------------------------------------------------------------- D4 AVISOS (US19, US20)
@Composable
fun TelaAvisos(e: Estado) {
    val c = cores()
    if (PrecisaDeLoginPos(e)) return
    LaunchedEffect(e.usuario?.id) { e.carregarAvisos() }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Column { Eyebrow("Avisos"); Spacer(Modifier.height(8.dp)); T("Suas notificações", 30, FontWeight.Bold, ls = -0.9f) }
            if (e.avisosNaoLidos > 0) Link("Marcar como lidos") { e.marcarAvisosLidos() }
        }
        if (e.avisos.isEmpty()) Cartao { T("Nenhum aviso", 20, FontWeight.Bold); T("Confirmações de compra, cancelamentos, atrasos e mudanças de horário aparecem aqui.", 15, cor = c.muted) }
        e.avisos.toList().forEach { a -> CartaoAviso(e, a) }
        T("Hoje os avisos só aparecem aqui dentro do app. Push, e-mail e WhatsApp ainda não estão ligados.", 13, cor = c.muted)
    }
}

@Composable
private fun CartaoAviso(e: Estado, a: Notificacao) {
    val c = cores()
    val icone = when (a.tipo) { TipoNotificacao.VIAGEM_CANCELADA, TipoNotificacao.CANCELAMENTO -> "✕"; TipoNotificacao.CONEXAO_RISCO -> "▲"; TipoNotificacao.ATRASO, TipoNotificacao.HORARIO -> "🕐"; else -> "✓" }
    val cor = when (a.tipo) { TipoNotificacao.VIAGEM_CANCELADA, TipoNotificacao.CANCELAMENTO -> c.erro; TipoNotificacao.CONEXAO_RISCO -> c.atencao; else -> c.primary }
    Cartao(raio = 24, pad = 20, borda = true, mod = if (a.pedidoId != null) Modifier.clickable { e.historico.firstOrNull { it.id == a.pedidoId }?.let { p -> e.abrirDetalhe(p) } ?: e.abrirHistorico() } else Modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            T(icone, 18, FontWeight.Bold, cor)
            Column(Modifier.weight(1f)) {
                T(a.titulo, 16, if (a.lida) FontWeight.Medium else FontWeight.Bold)
                T(a.texto, 14, cor = c.muted)
                T(Tempo.dataHora(a.criadaEm), 12, cor = c.muted)
            }
            if (!a.lida) T("●", 12, FontWeight.Bold, c.primary)
        }
    }
}
