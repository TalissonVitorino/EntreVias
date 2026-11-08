package br.com.talisson.entrevias

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import br.com.talisson.entrevias.domain.CategoriaPassageiro
import br.com.talisson.entrevias.domain.Pedido
import br.com.talisson.entrevias.domain.RegrasDePreco
import br.com.talisson.entrevias.domain.StatusPagamento
import br.com.talisson.entrevias.domain.TIPO_CARTAO
import br.com.talisson.entrevias.domain.ValidacaoPerfil
import kotlinx.coroutines.delay
import kotlin.time.Clock

/** Segundos desde 1970 no relógio do aparelho. Só para contagens regressivas; quem decide o prazo é sempre o servidor. */
private fun agoraSeg() = Clock.System.now().epochSeconds

/** Segundos que faltam para `expiraEm` (do servidor), dado o relógio do servidor na resposta e o tempo decorrido no aparelho desde então. */
internal fun restanteSeg(expiraEm: Long, agoraDoServidor: Long, decorridoNoAparelho: Long) = expiraEm - (agoraDoServidor + decorridoNoAparelho)

@Composable
private fun MensagensCompra(e: Estado) {
    val c = cores()
    e.compraErro?.let { T("✕  $it", 14, FontWeight.SemiBold, c.erro) }
    e.compraAviso?.let { T("✓  $it", 14, FontWeight.SemiBold, c.ok) }
}

@Composable
private fun CabecalhoCompra(passo: String, titulo: String, sub: String? = null) {
    val c = cores()
    Column {
        Eyebrow(passo)
        Spacer(Modifier.height(8.dp))
        T(titulo, 30, FontWeight.Bold, ls = -0.9f, lh = 34)
        if (sub != null) { Spacer(Modifier.height(6.dp)); T(sub, 15, cor = c.muted) }
    }
}

@Composable
private fun SemPedido(e: Estado) {
    Cartao {
        T("Nenhuma compra em andamento", 22, FontWeight.Bold)
        T("Escolha uma rota para comprar.", 15, cor = cores().muted)
        Botao("Buscar opções", { e.ir(Tela.BUSCAR) }, primario = true)
    }
}

private fun Estado.trechoTxt(origem: String, destino: String) = "${cidade(origem)} → ${cidade(destino)}"

/**
 * Contagem regressiva até `expiraEm`, que é um horário do SERVIDOR. O relógio do aparelho pode estar adiantado ou atrasado
 * (dias, até), então a conta parte do `agoraDoServidor` que veio na mesma resposta e só usa o aparelho para medir o tempo
 * que passou desde então. Quando zera, avisa e chama `aoVencer`.
 */
@Composable
private fun Contagem(rotulo: String, expiraEm: Long?, agoraDoServidor: Long, aoVencer: () -> Unit) {
    val c = cores()
    if (expiraEm == null) return
    val inicio = remember(expiraEm, agoraDoServidor) { agoraSeg() }
    var decorrido by remember(expiraEm, agoraDoServidor) { mutableStateOf(0L) }
    LaunchedEffect(expiraEm, agoraDoServidor) { while (true) { decorrido = agoraSeg() - inicio; delay(1000) } }
    val resta = restanteSeg(expiraEm, agoraDoServidor, decorrido)
    if (resta > 0) T("⏱  $rotulo ${Tempo.contagem(resta)}", 14, FontWeight.SemiBold, if (resta < 120) c.erro else c.primary)
    else {
        T("A reserva das poltronas venceu. Escolha de novo.", 14, FontWeight.SemiBold, c.erro)
        Botao("Escolher poltronas", aoVencer, pequeno = true, primario = true)
    }
}

// ---------------------------------------------------------------- C1 CARRINHO (US18)
@Composable
fun TelaCarrinho(e: Estado) {
    val c = cores()
    val r = e.compraRota
    if (r == null) { SemPedido(e); return }
    LaunchedEffect(e.usuario?.id) { e.carregarCarteira() }
    LaunchedEffect(e.passageiros.size) {
        if (e.paxSelecionados.isEmpty() && e.pedido == null && e.passageiros.isNotEmpty()) e.passageiros.take(e.rPax.coerceIn(1, RegrasDePreco.MAX_PASSAGEIROS)).forEach { e.alternarPassageiro(it.id) }
    }
    var cupom by remember { mutableStateOf("") }
    val ped = e.pedido
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Link("‹  Voltar à rota") { e.ir(Tela.ROTA) }
        CabecalhoCompra("Passo 1 de 4", "Seu carrinho", if (e.voltaEscolhida != null) "${e.cidade(r.origem)} ⇄ ${e.cidade(r.destino)}" else "${e.cidade(r.origem)} → ${e.cidade(r.destino)}")
        val volta = e.voltaEscolhida
        (r.viagens.map { it to false } + (volta?.viagens.orEmpty().map { it to true })).forEach { (v, naVolta) ->
            Cartao(raio = 24, pad = 20, borda = true) {
                if (volta != null) Etiqueta(if (naVolta) "Volta" else "Ida", simples = true)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    T(e.trechoTxt(v.origem, v.destino), 18, FontWeight.Bold, ls = -0.3f, mod = Modifier.weight(1f))
                    T(centavosParaReais(v.precoCentavos), 18, FontWeight.Bold)
                }
                T("${dataViagem(if (naVolta) (e.rDataVolta ?: e.rData) else e.rData, v.saidaMin)} · ${hora(v.saidaMin)} → ${hora(v.chegadaMin)} · ${v.empresa} · ${v.categoria}", 14, cor = c.muted)
                T("preço por pessoa", 13, cor = c.muted)
            }
        }
        Cartao {
            T("Quem viaja", 20, FontWeight.Bold, ls = -0.4f)
            if (e.passageiros.isEmpty()) {
                T("Você ainda não cadastrou passageiros. Cadastre quem vai viajar para ver o total.", 14, cor = c.muted)
                Botao("Cadastrar passageiros", { e.ir(Tela.PASSAGEIROS) }, Modifier.fillMaxWidth(), primario = true)
            } else e.passageiros.toList().forEach { p ->
                val marcado = p.id in e.paxSelecionados
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (marcado) c.soft else c.field).clickable { e.alternarPassageiro(p.id) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { T(p.nome, 16, FontWeight.SemiBold); T("${p.idade} anos · ${CategoriaPassageiro.rotulo(p.categoria)}", 13, cor = c.muted) }
                    T(if (marcado) "✓" else "○", 20, FontWeight.Bold, if (marcado) c.primary else c.muted)
                }
            }
            T("Menor de 12 anos precisa viajar com um adulto ou idoso. Criança paga metade; idoso e bebê de colo não pagam.", 13, cor = c.muted)
        }
        MensagensCompra(e)
        if (ped == null) {
            Botao(if (e.compraOcupada) "Calculando…" else "Ver o total", { e.calcularPedido() }, Modifier.fillMaxWidth(), primario = true, grande = true)
        } else {
            Cartao {
                T("Resumo", 20, FontWeight.Bold, ls = -0.4f)
                LinhaValor("Subtotal", centavosParaReais(ped.totais.subtotalCentavos))
                if (ped.totais.descontoCategoriaCentavos > 0) LinhaValor("Gratuidade e desconto por categoria", "− " + centavosParaReais(ped.totais.descontoCategoriaCentavos))
                if (ped.cupom != null) LinhaValor("Cupom ${ped.cupom}", "− " + centavosParaReais(ped.totais.cupomCentavos), c.ok)
                LinhaValor("Total", centavosParaReais(ped.totais.totalCentavos), forte = true)
                T("Sem taxas escondidas · taxa de serviço R\$ 0,00", 13, cor = c.muted)
                if (ped.cupom == null) {
                    CampoTexto("Cupom de desconto", cupom, { cupom = it.uppercase().take(20) }, dica = "VIAJA10", comErro = e.compraErro?.contains("upom") == true)
                    Botao("Aplicar", { e.aplicarCupom(cupom) }, pequeno = true)
                } else Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    T("✓ Cupom aplicado", 14, FontWeight.SemiBold, c.ok); Link("Remover") { e.removerCupom() }
                }
            }
            Cartao(raio = 24, pad = 20, borda = true) {
                T("Cancelamento", 16, FontWeight.Bold)
                T("Cancelar a compra: taxa de ${RegrasDePreco.TAXA_CANCELAMENTO_PCT}%, reembolso em até 72 horas, antes da saída. Os trechos são cancelados juntos.", 14, cor = c.muted)
            }
            Botao("Escolher poltronas", { e.abrirPoltronas() }, Modifier.fillMaxWidth(), primario = true, grande = true)
        }
    }
}

@Composable
private fun LinhaValor(rotulo: String, valor: String, cor: androidx.compose.ui.graphics.Color? = null, forte: Boolean = false) {
    val c = cores()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        T(rotulo, if (forte) 18 else 15, if (forte) FontWeight.Bold else FontWeight.Medium, cor ?: if (forte) c.fg else c.muted, Modifier.weight(1f))
        T(valor, if (forte) 24 else 15, FontWeight.Bold, cor ?: c.fg)
    }
}

// ---------------------------------------------------------------- C2 POLTRONAS (US17)
@Composable
fun TelaPoltronas(e: Estado) {
    val c = cores()
    val ped = e.pedido
    if (ped == null) { SemPedido(e); return }
    val i = e.trechoDePoltronas.coerceIn(0, ped.trechos.size - 1)
    val t = ped.trechos[i]
    val minhas = e.escolhas[t.viagemId].orEmpty()
    val m = e.mapa
    val ultimo = i == ped.trechos.size - 1
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Link("‹  Voltar ao carrinho") { e.ir(Tela.CARRINHO) }
        CabecalhoCompra("Passo 2 de 4 · Trecho ${i + 1} de ${ped.trechos.size}", "Escolha as poltronas", "${e.trechoTxt(t.origem, t.destino)} · ${Tempo.dataHora(t.saidaEm)} · ${t.empresa}")
        T("Escolha ${e.poltronasPorTrecho} ${if (e.poltronasPorTrecho == 1) "poltrona" else "poltronas"} · ${minhas.size} escolhida${if (minhas.size == 1) "" else "s"}", 16, FontWeight.SemiBold)
        if (m == null) T("Carregando o mapa…", 15, cor = c.muted)
        else Cartao {
            for (linha in 0 until m.total / 4) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    for (col in 0 until 4) {
                        val n = linha * 4 + col + 1
                        if (col == 2) Spacer(Modifier.width(28.dp))
                        Poltrona(n, ocupada = n in m.ocupadas, pcd = n in m.pcd, bloqueadaPcd = n in m.pcd && !e.temAcessibilidade, selecionada = n in minhas) { e.alternarPoltrona(n) }
                        if (col != 3 && col != 1) Spacer(Modifier.width(8.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Legenda(c.field, "livre"); Legenda(c.primary, "sua escolha"); Legenda(c.line, "ocupada"); T("♿ PcD", 13, cor = c.muted)
            }
            if (!e.temAcessibilidade) T("As poltronas PcD são para passageiros com necessidade de acessibilidade cadastrada.", 13, cor = c.muted)
        }
        T("Depois de confirmar, as poltronas ficam reservadas por ${RegrasDePreco.RESERVA_SEG / 60} minutos enquanto você paga.", 13, cor = c.muted)
        MensagensCompra(e)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (i > 0) Botao("‹ Trecho anterior", { e.voltarTrecho() }, pequeno = true)
            Botao(if (e.compraOcupada) "Reservando…" else if (ultimo) "Reservar e pagar" else "Próximo trecho", { e.avancarPoltronas() }, Modifier.weight(1f), primario = true)
        }
    }
}

@Composable
internal fun Poltrona(n: Int, ocupada: Boolean, pcd: Boolean, bloqueadaPcd: Boolean, selecionada: Boolean, onClick: () -> Unit) {
    val c = cores()
    val livre = !ocupada && !bloqueadaPcd
    val forma = RoundedCornerShape(12.dp)
    Box(
        Modifier.size(52.dp).clip(forma)
            .background(if (selecionada) c.primary else if (ocupada) c.line else c.field)
            .then(if (pcd && !ocupada) Modifier.border(1.5.dp, c.atencao, forma) else Modifier)
            .then(if (livre) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        T(if (pcd && !ocupada) "♿$n" else if (ocupada) "✕" else "$n", 14, FontWeight.SemiBold, if (selecionada) c.onPrimary else if (ocupada || bloqueadaPcd) c.muted else c.fg)
    }
}

@Composable
internal fun Legenda(cor: androidx.compose.ui.graphics.Color, rotulo: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(14.dp).clip(RoundedCornerShape(4.dp)).background(cor).border(1.dp, cores().line, RoundedCornerShape(4.dp)))
        T(rotulo, 13, cor = cores().muted)
    }
}

// ---------------------------------------------------------------- C3 FORMA DE PAGAMENTO e C5 CARTÃO (US15, US16)
@Composable
fun TelaPagamento(e: Estado) {
    val c = cores()
    val ped = e.pedido
    if (ped == null) { SemPedido(e); return }
    LaunchedEffect(Unit) { e.carregarCarteira(); e.carregarModoDePagamento() }
    var escolha by remember { mutableStateOf("pix") }   // "pix", "novo" ou "f<id>"
    var parcelas by remember { mutableStateOf(1) }
    val cartoes = e.formas.filter { it.tipo == TIPO_CARTAO }
    val total = ped.totais.totalCentavos
    // Número, validade, CVV e nome vivem só aqui: somem quando a pessoa sai da tela.
    var numero by remember { mutableStateOf("") }
    var validade by remember { mutableStateOf("") }
    var cvv by remember { mutableStateOf("") }
    var nome by remember { mutableStateOf("") }
    var salvar by remember { mutableStateOf(false) }
    var erroLocal by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Link("‹  Voltar às poltronas") { e.abrirPoltronas() }
        CabecalhoCompra("Passo 3 de 4", "Como você quer pagar?", "Total ${centavosParaReais(total)} · ${ped.trechos.size} ${if (ped.trechos.size == 1) "trecho" else "trechos"}")
        Contagem("Poltronas reservadas por", ped.reservaExpiraEm, ped.agora) { e.abrirPoltronas() }
        e.pagamentoModo?.let { if (it["simulado"] == true) Aviso("Pagamento simulado", listOf("Nenhuma cobrança de verdade acontece nesta versão.", "Cartão terminado em 0002 é recusado, para você testar a recusa.")) }
        OpcaoPagamento("Pix", "Aprovação na hora (demonstração)", escolha == "pix") { escolha = "pix"; erroLocal = null }
        cartoes.forEach { f -> OpcaoPagamento(f.rotulo, "vence ${f.validade}", escolha == "f${f.id}") { escolha = "f${f.id}"; erroLocal = null } }
        OpcaoPagamento("+ Outro cartão", "Digite os dados do cartão", escolha == "novo") { escolha = "novo"; erroLocal = null }
        if (escolha != "pix") {
            Cartao {
                if (escolha == "novo") {
                    T("Cartão de crédito", 20, FontWeight.Bold, ls = -0.4f)
                    CampoTexto("Número do cartão", numero, { numero = it.filter { ch -> ch in '0'..'9' || ch == ' ' }.take(23) }, dica = "0000 0000 0000 0000", teclado = KeyboardType.Number, comErro = erroLocal != null)
                    CampoTexto("Validade (MM/AA)", validade, { validade = it.take(5) }, dica = "08/29", teclado = KeyboardType.Number)
                    CampoTexto("CVV", cvv, { cvv = it.filter { ch -> ch in '0'..'9' }.take(4) }, dica = "•••", senha = true, teclado = KeyboardType.Number)
                    CampoTexto("Nome impresso no cartão", nome, { nome = it.take(40) }, dica = "A. FERREIRA")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) { Chave(salvar) { salvar = it }; T("Salvar este cartão no perfil", 15, cor = c.fg) }
                }
                T("Parcelas", 14, FontWeight.Medium, c.muted)
                val maximas = (1..6).filter { it == 1 || total / it >= 500 }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { maximas.forEach { n -> Botao("${n}x", { parcelas = n }, pequeno = true, ativo = parcelas == n) } }
                if (parcelas > 1) T("${parcelas}x de ${centavosParaReais(total / parcelas)} sem juros", 13, cor = c.muted)
                T("🔒 O número e o CVV ficam só neste aparelho: o servidor recebe apenas a bandeira e os 4 últimos dígitos.", 13, cor = c.muted)
            }
        }
        erroLocal?.let { T("✕  $it", 14, FontWeight.SemiBold, c.erro) }
        MensagensCompra(e)
        Botao(if (e.compraOcupada) "Processando…" else "Pagar ${centavosParaReais(total)}", {
            erroLocal = null
            when {
                escolha == "pix" -> e.pagarComPix()
                escolha == "novo" -> {
                    val d = numero.filter { it in '0'..'9' }
                    erroLocal = when {
                        !ValidacaoPerfil.luhn(d) -> "Número de cartão inválido. Confira os dígitos."
                        ValidacaoPerfil.validade(validade) != null -> ValidacaoPerfil.validade(validade)
                        ValidacaoPerfil.vencido(validade.trim(), DATA_INICIAL.ano, DATA_INICIAL.mes) -> "Este cartão está vencido."
                        cvv.length !in 3..4 -> "Informe o CVV (3 ou 4 dígitos)."
                        nome.trim().length < 2 -> "Informe o nome impresso no cartão."
                        else -> null
                    }
                    if (erroLocal == null) { val b = ValidacaoPerfil.bandeiraDe(d); val u4 = d.takeLast(4); val v = validade.trim(); numero = ""; cvv = ""; e.pagarComCartao(u4, b, parcelas, salvar, v) }
                }
                else -> cartoes.firstOrNull { "f${it.id}" == escolha }?.let { f -> e.pagarComCartao(f.ultimos4.orEmpty(), f.bandeira.orEmpty(), parcelas, false, f.validade.orEmpty()) }
            }
        }, Modifier.fillMaxWidth(), primario = true, grande = true)
    }
}

@Composable
private fun OpcaoPagamento(titulo: String, sub: String, marcada: Boolean, onClick: () -> Unit) {
    val c = cores()
    val forma = RoundedCornerShape(20.dp)
    Row(Modifier.fillMaxWidth().clip(forma).background(c.surface).border(if (marcada) 2.dp else 1.dp, if (marcada) c.primary else c.line, forma).clickable(onClick = onClick).padding(18.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { T(titulo, 17, FontWeight.Bold); T(sub, 13, cor = c.muted) }
        T(if (marcada) "●" else "○", 20, FontWeight.Bold, if (marcada) c.primary else c.muted)
    }
}

// ---------------------------------------------------------------- C4 PIX (US15)
@Composable
fun TelaPix(e: Estado) {
    val c = cores()
    val g = e.pagamento
    val ped = e.pedido
    if (g == null || ped == null) { SemPedido(e); return }
    val clip = LocalClipboardManager.current
    var copiado by remember { mutableStateOf(false) }
    // Pergunta ao servidor a cada 3 s enquanto o Pix está pendente.
    LaunchedEffect(g.id, g.status) {
        e.carregarModoDePagamento()
        while (e.pagamento?.status == StatusPagamento.PENDENTE) { delay(3000); e.consultarPagamento() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CabecalhoCompra("Passo 4 de 4", "Pague com Pix", "Valor ${centavosParaReais(ped.totais.totalCentavos)}")
        if (g.status == StatusPagamento.EXPIRADO) {
            Cartao {
                T("O Pix expirou", 22, FontWeight.Bold, cor = c.erro)
                T("As poltronas voltaram à venda. Escolha de novo para tentar outra vez.", 15, cor = c.muted)
                Botao("Escolher poltronas", { e.abrirPoltronas() }, Modifier.fillMaxWidth(), primario = true)
            }
        } else {
            e.pagamentoModo?.let { if (it["simulado"] == true) Aviso("Pix simulado", listOf("Este código não é um Pix de verdade: nenhum banco o reconhece.", "Use o botão de demonstração abaixo para aprovar.")) }
            Cartao {
                Contagem("Expira em", g.expiraEm, g.agora) { e.abrirPoltronas() }
                g.codigoPix?.let { cod -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { QrView(cod) } }
                T("Escaneie o QR code no app do seu banco ou use o Pix copia e cola.", 14, cor = c.muted)
                T("Pix copia e cola", 14, FontWeight.Medium, c.muted)
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.field).padding(14.dp)) { T(g.codigoPix.orEmpty(), 14, FontWeight.Medium, mod = Modifier.fillMaxWidth()) }
                Botao(if (copiado) "✓ Código copiado" else "Copiar código Pix", { clip.setText(AnnotatedString(g.codigoPix.orEmpty())); copiado = true }, Modifier.fillMaxWidth(), ativo = true)
                T("Aguardando pagamento…", 15, FontWeight.SemiBold, c.muted)
            }
            Cartao(raio = 24, pad = 20, borda = true) {
                T("Suas poltronas estão guardadas", 16, FontWeight.Bold)
                T("Se o Pix vencer, as poltronas voltam à venda.", 14, cor = c.muted)
            }
            if (e.pagamentoModo?.get("simulado") == true) Botao(if (e.compraOcupada) "Aprovando…" else "Simular pagamento do Pix (demonstração)", { e.simularPix() }, Modifier.fillMaxWidth(), primario = true)
            MensagensCompra(e)
            Botao("Pagar de outro jeito", { e.ir(Tela.PAGAMENTO) }, Modifier.fillMaxWidth())
        }
    }
}

// ---------------------------------------------------------------- C6 RESULTADO (RQ42)
@Composable
fun TelaResultado(e: Estado) {
    val c = cores()
    val g = e.pagamento
    val ped = e.pedido
    if (g == null || ped == null) { SemPedido(e); return }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (g.status == StatusPagamento.APROVADO) {
            Cartao {
                T("✓  Pagamento confirmado", 28, FontWeight.Bold, c.ok, ls = -0.8f)
                T("${ped.bilhetes.size} ${if (ped.bilhetes.size == 1) "bilhete emitido" else "bilhetes emitidos"} na compra ${ped.codigo}. Eles ficam em Viagens, e você pode guardá-los no aparelho para usar sem internet.", 15, cor = c.muted)
                Botao("Ver meus bilhetes", { e.abrirBilhetes(ped) }, Modifier.fillMaxWidth(), primario = true, grande = true)
                Botao("Voltar para a busca", { e.ir(Tela.BUSCAR) }, Modifier.fillMaxWidth())
            }
        } else {
            Cartao {
                T("✕  Pagamento recusado", 28, FontWeight.Bold, c.erro, ls = -0.8f)
                T(g.motivo ?: "O pagamento não foi aprovado.", 15, cor = c.muted)
                ped.reservaExpiraEm?.let { Contagem("Suas poltronas continuam guardadas por", it, ped.agora) { e.abrirPoltronas() } }
                Botao("Tentar de novo", { e.ir(Tela.PAGAMENTO) }, Modifier.fillMaxWidth(), primario = true, grande = true)
                Botao("Pagar com Pix", { e.pagarComPix() }, Modifier.fillMaxWidth())
                MensagensCompra(e)
            }
        }
    }
}

// ---------------------------------------------------------------- C7 BILHETES (US21)
@Composable
fun TelaBilhetes(e: Estado) {
    val c = cores()
    val p = e.pedidoAberto
    if (p == null || p.status != "pago") {
        Cartao { T("Nenhum bilhete aberto", 22, FontWeight.Bold); Botao("Ver minhas viagens", { e.abrirHistorico() }, primario = true) }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Link("‹  Viagens") { e.abrirHistorico() }
        CabecalhoCompra("Compra ${p.codigo}", "Seus bilhetes")
        p.trechos.forEach { t ->
            Cartao(raio = 24, pad = 20, borda = true) {
                T(e.trechoTxt(t.origem, t.destino), 20, FontWeight.Bold, ls = -0.4f)
                T("${Tempo.dataHora(t.saidaEm)} · ${t.empresa} · ${t.categoria}", 14, cor = c.muted)
                T("Plataforma ${t.plataforma ?: "-"} · ${e.terminal(t.origem)}", 14, cor = c.muted)
                p.bilhetes.filter { it.trecho == t.ordem }.forEach { b ->
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.field).padding(14.dp)) {
                        Column {
                            T(b.passageiro, 16, FontWeight.SemiBold)
                            T(if (b.poltrona != null) "Poltrona ${b.poltrona}" else "Sem poltrona (colo)", 14, cor = c.muted)
                            T(b.codigo, 13, FontWeight.Medium, c.muted)
                        }
                    }
                }
                if (e.estaGuardado(p)) Etiqueta("Disponível sem internet")
            }
        }
        Aviso("Leve um documento com foto", listOf("CPF ou RG de cada passageiro.", "Idoso: documento que comprove a gratuidade."))
        if (e.estaGuardado(p)) T("✓ Guardado neste aparelho. Eles somem se você sair da conta.", 14, FontWeight.SemiBold, c.ok)
        else Botao("Guardar para usar sem internet", { e.guardarBilhetes(p) }, Modifier.fillMaxWidth(), ativo = true)
        Botao("Detalhes da compra", { e.abrirDetalhe(p) }, Modifier.fillMaxWidth())
    }
}
