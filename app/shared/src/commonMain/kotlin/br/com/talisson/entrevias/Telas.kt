package br.com.talisson.entrevias

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.talisson.entrevias.domain.LimiteRoteiros
import br.com.talisson.entrevias.domain.Ordem
import br.com.talisson.entrevias.domain.Rota
import br.com.talisson.entrevias.domain.RotuloRisco
import br.com.talisson.entrevias.domain.TipoPlanoB

private fun Rota.stops() = when (baldeacoes) { 0 -> "Direto"; 1 -> "1 baldeação"; else -> "$baldeacoes baldeações" }
private fun Rota.categoria() = viagens.map { it.categoria }.distinct().let { if (it.size == 1) it.first() else "Categorias mistas" }

@Composable
private fun CaixaVazia(titulo: String, texto: String, botao: String, onClick: () -> Unit) {
    val c = cores()
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.surface).padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        T(titulo, 20, FontWeight.Bold)
        T(texto, 15, cor = c.muted)
        Botao(botao, onClick, primario = true, pequeno = true)
    }
}

// ---------------------------------------------------------------- BUSCAR
@Composable
fun TelaBuscar(e: Estado) {
    val c = cores()
    val cidades = e.terminais.map { it.id to it.cidade }
    Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
        Column {
            Eyebrow("Uma viagem, todas as etapas")
            Spacer(Modifier.height(18.dp))
            Text(
                buildAnnotatedString {
                    append("Compare o\ncaminho ")
                    withStyle(SpanStyle(color = c.primary)) { append("inteiro.") }
                },
                color = c.fg, fontSize = 56.sp, lineHeight = 58.sp, fontWeight = FontWeight.Bold, letterSpacing = (-2.2).sp,
            )
            Spacer(Modifier.height(16.dp))
            T("Veja preços, horários e conexões em um só planejamento.", 19, cor = c.muted, lh = 26)
            Spacer(Modifier.height(20.dp))
            T("•  Sem cadastro. Sem compra real.", 14, cor = c.muted)
        }

        Cartao {
            T("Para onde vamos?", 24, FontWeight.Bold, ls = -0.5f)
            Spacer(Modifier.height(4.dp))
            Campo("Origem", e.origem?.let { e.cidade(it) } ?: "Escolha a cidade", cidades) { e.origem = it }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                Box(
                    Modifier.width(52.dp).height(52.dp).clip(RoundedCornerShape(14.dp)).background(c.field).clickable { e.inverter() },
                    contentAlignment = Alignment.Center,
                ) { T("↕", 20, cor = c.primary) }
            }
            Campo("Destino", e.destino?.let { e.cidade(it) } ?: "Escolha a cidade", cidades) { e.destino = it }
            var calAberto by remember { mutableStateOf(false) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                T("Ida", 14, FontWeight.Medium, c.muted)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp)).background(c.field)
                        .clickable { calAberto = true }.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
                ) { T(e.data.curta(), 17, FontWeight.Medium); T("▾", 14, cor = c.muted) }
            }
            if (calAberto) CalendarioDialog(e.data, { e.data = it; calAberto = false }, { calAberto = false })
            Campo("Passageiros", passageirosTxt(e.pax), (1..6).map { it.toString() to passageirosTxt(it) }) { e.pax = it.toInt() }
            Botao(if (e.buscando) "Buscando…" else "Buscar opções", { e.buscar() }, Modifier.fillMaxWidth(), primario = e.podeBuscar, grande = true)
            e.erroBusca?.let { T(it, 14, cor = c.erro) }
            if (!e.podeBuscar) T(
                if (e.origem != null && e.origem == e.destino) "Origem e destino precisam ser diferentes." else "Escolha a origem e o destino para buscar.",
                14, cor = c.muted,
            )
            T("Na demonstração, todos os passageiros usam a mesma tarifa.", 14, cor = c.muted)
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            T("Do primeiro passo ao destino.", 32, FontWeight.Bold, ls = -1f, lh = 36)
            Eyebrow("Como explorar")
            Passo("01", "Busque seu trajeto", "Escolha as cidades, a data e com quem vai viajar.")
            Passo("02", "Compare a viagem completa", "Veja o total, a duração e cada conexão, sem surpresas escondidas.")
            Passo("03", "Salve e simule", "Guarde o planejamento e simule a viagem trecho a trecho, sem comprar.")
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Eyebrow("Seu jeito de viajar")
            T("O caminho também é uma escolha.", 32, FontWeight.Bold, ls = -1f, lh = 36)
            Escolha("Economizar", "Mais possibilidades para quem prefere um total menor, mesmo com uma conexão a mais.", "Ver menor preço") {
                e.ordem = Ordem.MAIS_BARATA; e.ir(Tela.RESULTADOS)
            }
            Escolha("Priorizar conforto", "Compare categorias e comodidades de cada trecho para escolher como quer ir.", "Ver mais conforto") {
                e.ordem = Ordem.MAIS_CONFORTO; e.ir(Tela.RESULTADOS)
            }
        }

        Cartao {
            T("Conexões sem trocar de terminal.", 24, FontWeight.Bold, ls = -0.5f)
            T("Uma viagem direta ou até duas baldeações. A chegada e a próxima saída acontecem no mesmo terminal de cada cidade, com tempo mínimo de espera de ${e.params.minimaMin} minutos.", 15, cor = c.muted)
            T(SEPARADAS, 13, cor = c.muted)
            T("Terminais ilustrativos. Os tempos de conexão não foram validados com empresas.", 13, cor = c.muted)
        }

        Cartao {
            Eyebrow("Planeje sem compromisso")
            T("Salve neste aparelho. Compare no seu tempo.", 24, FontWeight.Bold, ls = -0.5f)
            Botao("Meus planejamentos", { e.ir(Tela.PLANEJAMENTOS) }, primario = true)
        }
    }
}

@Composable
private fun Passo(n: String, titulo: String, texto: String) {
    val c = cores()
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.surface).padding(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        T(n, 13, FontWeight.Bold, c.primary, Modifier.width(30.dp).padding(top = 3.dp), ls = 0.8f)
        Column { T(titulo, 17, FontWeight.SemiBold); T(texto, 15, cor = c.muted) }
    }
}

@Composable
private fun Escolha(titulo: String, texto: String, botao: String, onClick: () -> Unit) {
    val c = cores()
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.surface).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        T(titulo, 20, FontWeight.Bold)
        T(texto, 15, cor = c.muted)
        Botao(botao, onClick, pequeno = true)
    }
}

// ---------------------------------------------------------------- RESULTADOS
@Composable
fun TelaResultados(e: Estado) {
    val c = cores()
    val lista = e.resultado
    if (lista == null) {
        CaixaVazia("Nenhuma busca ainda", "Escolha origem, destino e data para ver as opções.", "Buscar opções") { e.ir(Tela.BUSCAR) }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Eyebrow("Opções de viagem")
                Spacer(Modifier.height(8.dp))
                T("${e.cidade(e.rOrigem)} → ${e.cidade(e.rDestino)}", 30, FontWeight.Bold, ls = -0.9f, lh = 34)
                Spacer(Modifier.height(6.dp))
                T("${e.rData.br()} · ${passageirosTxt(e.rPax)}", 15, cor = c.muted)
            }
            Spacer(Modifier.width(12.dp))
            Botao("Editar busca", { e.ir(Tela.BUSCAR) }, pequeno = true)
        }
        T("Opções completas", 24, FontWeight.Bold, ls = -0.5f)
        Segmentos(listOf(Ordem.MAIS_BARATA to "Menor preço", Ordem.MAIS_RAPIDA to "Mais rápida", Ordem.MAIS_CONFORTO to "Mais conforto"), e.ordem) { e.ordem = it }
        T("Duração completa inclui as esperas. Preços somam todos os trechos para o grupo.", 14, cor = c.muted)
        T("${lista.size} ${if (lista.size == 1) "opção demonstrativa" else "opções demonstrativas"} · Ida", 14, cor = c.muted)
        if (lista.isEmpty()) {
            CaixaVazia("Sem opções para esta busca", "Nenhuma rota encontrada nessa data. Tente outra data ou outras cidades.", "Editar busca") { e.ir(Tela.BUSCAR) }
        } else lista.forEach { CartaoOpcao(e, it) }
        T(TAXAS, 13, cor = c.muted)
    }
}

@Composable
private fun Segmentos(opcoes: List<Pair<Ordem, String>>, atual: Ordem, onEscolher: (Ordem) -> Unit) {
    val c = cores()
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.field).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        opcoes.forEach { (o, nome) ->
            val sel = o == atual
            Box(
                Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (sel) c.surface else Color.Transparent).clickable { onEscolher(o) },
                contentAlignment = Alignment.Center,
            ) { T(if (sel) "✓ $nome" else nome, 14, if (sel) FontWeight.SemiBold else FontWeight.Medium, if (sel) c.primary else c.muted, align = TextAlign.Center) }
        }
    }
}

@Composable
private fun CartaoOpcao(e: Estado, r: Rota) {
    val c = cores()
    val picked = r.id in e.selecionadas
    val total = r.totalCentavos * e.rPax
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.surface).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Etiqueta("Opção ${e.nome(r)}"); Etiqueta(r.categoria()); Etiqueta(r.stops(), simples = true)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column {
                T(hora(r.saidaMin), 28, FontWeight.Bold, ls = -0.8f)
                T(e.cidade(r.origem), 14, FontWeight.SemiBold)
                T(dataViagem(e.rData, r.saidaMin), 13, cor = c.muted)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                T("◷ ${duracao(r.duracaoMin)}", 14, FontWeight.SemiBold)
                T(if (r.viagens.size > 1) "${r.viagens.size} trechos" else "Sem conexão", 13, cor = c.muted)
            }
            Column(horizontalAlignment = Alignment.End) {
                T(hora(r.chegadaMin), 28, FontWeight.Bold, ls = -0.8f)
                T(e.cidade(r.destino), 14, FontWeight.SemiBold)
                T(dataViagem(e.rData, r.chegadaMin), 13, cor = c.muted)
            }
        }
        val risco = e.risco(r)
        risco.pior?.let { pior ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SeloRisco(pior.rotulo, pior.nota)
                    T("Risco da conexão", 13, cor = c.muted)
                }
                T(pior.frase, 13, cor = c.muted)
                if (risco.simulado) T("Atraso típico simulado (dados de demonstração).", 12, cor = c.muted)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            T("Total conhecido · ${passageirosTxt(e.rPax)}", 13, cor = c.muted, mod = Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                T(centavosParaReais(total), 32, FontWeight.Bold, ls = -1f)
                T("${centavosParaReais(r.totalCentavos)} por pessoa", 13, cor = c.muted)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            r.viagens.forEachIndexed { i, v ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    T("${i + 1}. ${e.cidade(v.origem)} → ${e.cidade(v.destino)} · ${v.empresa} · ${centavosParaReais(v.precoCentavos)} / pessoa", 14)
                    T("${e.terminal(v.origem)} → ${e.terminal(v.destino)}", 13, cor = c.muted)
                    if (i < r.esperasMin.size) {
                        val w = r.esperasMin[i]
                        val apertada = w < r.params.apertadaMin
                        T("Espera de ${esperaTxt(w)} em ${e.terminal(v.destino)}" + if (apertada) " · conexão apertada" else "", 13, cor = if (apertada) c.erro else c.muted)
                    }
                }
            }
        }
        T("$VENDEDOR\n$COTACAO", 13, cor = c.muted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Botao("Ver rota", { e.abrirRota(r) }, Modifier.weight(1f), pequeno = true)
            Botao(if (picked) "✓ Comparando" else "Comparar", { e.alternarSelecao(r.id) }, Modifier.weight(1f), ativo = picked, pequeno = true)
            Botao("Escolher opção", { e.abrirRota(r) }, Modifier.weight(1f), primario = true, pequeno = true)
        }
    }
}

// ---------------------------------------------------------------- ROTA
@Composable
fun TelaRota(e: Estado) {
    val c = cores()
    val r = e.rotaAtual
    if (r == null) { CaixaVazia("Nenhuma rota aberta", "Busque uma viagem para ver os detalhes.", "Buscar opções") { e.ir(Tela.BUSCAR) }; return }
    val total = r.totalCentavos * e.rPax
    val risco = e.risco(r)
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Link(if (e.voltarPara == Tela.PLANEJAMENTOS) "← Voltar aos planejamentos" else "← Voltar aos resultados") { e.ir(e.voltarPara) }
        Column {
            Eyebrow("Detalhes da rota")
            Spacer(Modifier.height(8.dp))
            T("${e.cidade(r.origem)} → ${e.cidade(r.destino)}", 30, FontWeight.Bold, ls = -0.9f, lh = 34)
            Spacer(Modifier.height(6.dp))
            T("${e.rData.br()} · ${passageirosTxt(e.rPax)}", 15, cor = c.muted)
            Spacer(Modifier.height(10.dp))
            T("Ida: ${duracao(r.duracaoMin)}, incluindo esperas", 15, FontWeight.SemiBold)
            risco.pior?.let { pior ->
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SeloRisco(pior.rotulo, pior.nota)
                    T("Risco da rota (pior conexão)", 13, cor = c.muted)
                }
            }
        }
        T("Trecho por trecho", 22, FontWeight.Bold, ls = -0.4f)
        r.viagens.forEachIndexed { i, v ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.surface).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    T("Trecho ${i + 1} · ${v.categoria}", 15, FontWeight.SemiBold)
                    T(duracao(v.chegadaMin - v.saidaMin), 15, cor = c.muted)
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column { T("${hora(v.saidaMin)} ${e.cidade(v.origem)}", 18, FontWeight.Bold); T("${dataViagem(e.rData, v.saidaMin)} · ${e.terminal(v.origem)}", 14, cor = c.muted) }
                    Column { T("${hora(v.chegadaMin)} ${e.cidade(v.destino)}", 18, FontWeight.Bold); T("${dataViagem(e.rData, v.chegadaMin)} · ${e.terminal(v.destino)}", 14, cor = c.muted) }
                }
                Column { T(v.empresa, 15, FontWeight.SemiBold); T(VENDEDOR, 13, cor = c.muted) }
                Column {
                    T("Comodidades do exemplo", 14, FontWeight.SemiBold)
                    T(v.comodidades.joinToString(" · "), 14)
                    T("Disponibilidade não confirmada para viagens reais. Wi-Fi: Não informado.", 13, cor = c.muted)
                }
                T("${centavosParaReais(v.precoCentavos * e.rPax)} · ${centavosParaReais(v.precoCentavos)} por pessoa × ${e.rPax}", 14, FontWeight.SemiBold)
            }
            if (i < r.esperasMin.size) {
                val w = r.esperasMin[i]
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    T("Conexão de ${esperaTxt(w)}", 14, FontWeight.SemiBold)
                    T(e.terminal(v.destino), 14)
                    if (w < r.params.apertadaMin) T("Conexão apertada: abaixo de ${r.params.apertadaMin} min.", 14, cor = c.erro)
                    T("Chegada e próxima saída no mesmo terminal. Tempo de conexão ilustrativo, não validado com a empresa.", 14, cor = c.muted)
                    val cx = risco.conexoes[i]
                    Spacer(Modifier.height(8.dp))
                    SeloRisco(cx.rotulo, cx.nota)
                    Spacer(Modifier.height(4.dp))
                    T(cx.frase, 14, FontWeight.SemiBold)
                    cx.fatos.forEach { T("• $it", 14) }
                    T("A nota estima o risco; não é proteção: cada passagem é separada.", 13, cor = c.muted)
                    if (risco.simulado) T("Atraso típico simulado (dados de demonstração).", 13, cor = c.muted)
                    Spacer(Modifier.height(8.dp))
                    PlanoBUi(e, r, i, abertoPorPadrao = cx.rotulo == RotuloRisco.ARRISCADO)
                }
            }
        }
        Aviso("Antes de continuar", listOf(SEPARADAS, "Esta experiência não reserva assentos nem emite passagens.",
            "Terminais e horários são ilustrativos. Nenhum tempo de conexão foi validado com transportadoras."))
        Cartao(raio = 22, pad = 18) {
            T("O que este valor inclui", 20, FontWeight.Bold)
            T("Ida", 14, FontWeight.Medium, c.muted)
            r.viagens.forEach { v ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        T("${e.cidade(v.origem)} → ${e.cidade(v.destino)}", 15)
                        T("${centavosParaReais(v.precoCentavos)} × ${e.rPax}", 14, cor = c.muted)
                    }
                    T(centavosParaReais(v.precoCentavos * e.rPax), 15)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                T("Subtotal da ida", 15, FontWeight.SemiBold); T(centavosParaReais(total), 15, FontWeight.SemiBold)
            }
            T("Total conhecido · ${passageirosTxt(e.rPax)}", 14, cor = c.muted)
            T(centavosParaReais(total), 36, FontWeight.Bold, ls = -1f)
            T("${centavosParaReais(r.totalCentavos)} por pessoa", 14, cor = c.muted)
            T(COTACAO, 13, cor = c.muted)
            T(TAXAS, 13, cor = c.muted)
        }
        Cartao {
            T("Guardar este caminho · Total conhecido", 14, cor = c.muted)
            T(centavosParaReais(total), 30, FontWeight.Bold, ls = -1f)
            if (e.salvoAgora) T("✓ Salvo neste aparelho", 15, FontWeight.SemiBold, c.ok)
            else Botao("Salvar planejamento", { e.salvar(r) }, Modifier.fillMaxWidth(), primario = true, grande = true)
            if (e.limiteAtingido) T(LimiteRoteiros.MENSAGEM_CHEIO, 14, FontWeight.SemiBold, c.erro)
            Botao("Simular esta viagem", { e.simular(r) }, Modifier.fillMaxWidth())
            Botao("Ver planejamentos", { e.ir(Tela.PLANEJAMENTOS) }, Modifier.fillMaxWidth())
        }
    }
}

/**
 * RF20 / RN15: o que fazer se esta conexão for perdida. Sempre uma alternativa, nunca uma proteção (RN07).
 * [abertoPorPadrao]: já aberto quando a conexão é Arriscada (RF19); nas demais o plano B começa recolhido.
 */
@Composable
private fun PlanoBUi(e: Estado, r: Rota, conexao: Int, abertoPorPadrao: Boolean = false) {
    val c = cores()
    val forma = RoundedCornerShape(12.dp)
    var aberto by remember(r.id, conexao) { mutableStateOf(abertoPorPadrao) }
    Column(Modifier.fillMaxWidth().clip(forma).border(1.dp, c.line, forma).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).clickable { aberto = !aberto }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            T("Plano B: se você perder esta conexão", 14, FontWeight.SemiBold, mod = Modifier.weight(1f))
            T(if (aberto) "Ocultar" else "Ver plano B", 14, FontWeight.SemiBold, c.primary)
        }
        if (!aberto) return@Column
        T("Alternativa, não proteção: você compra um novo bilhete.", 13, cor = c.muted)
        val plano = remember(r.id, conexao, e.base, e.paramsPlanoB, e.params, e.terminais) { e.planoB(r, conexao) }
        if (plano == null) {
            val carga = e.baseCarga
            if (carga is Carga.Erro) {
                T(carga.mensagem, 14, cor = c.erro)
                Link("Tentar de novo") { e.garantirBase(e.rData) }
            } else T("Carregando as viagens do dia…", 14, cor = c.muted)
            return@Column
        }
        if (plano.semOpcao) {
            T("Sem outra saída nas próximas ${e.paramsPlanoB.janelaH} h.", 14, FontWeight.SemiBold)
            T("Tente outra data ou outra rota.", 13, cor = c.muted)
            Botao("Editar busca", { e.ir(Tela.BUSCAR) }, pequeno = true)
            return@Column
        }
        val destinoPerdida = r.viagens[conexao + 1].destino
        plano.opcoes.forEachIndexed { n, o ->
            if (n > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                T(
                    if (o.tipo == TipoPlanoB.MESMA_CIDADE) "Opção ${n + 1} · outra saída para ${e.cidade(destinoPerdida)}"
                    else "Rota nova até ${e.cidade(r.destino)}",
                    14, FontWeight.SemiBold,
                )
                o.novas.forEach { v ->
                    T("${hora(v.saidaMin)} ${e.cidade(v.origem)} → ${hora(v.chegadaMin)} ${e.cidade(v.destino)} · ${v.empresa} · ${v.categoria} · ${centavosParaReais(v.precoCentavos)} / pessoa", 13)
                    T("${dataViagem(e.rData, v.saidaMin)} · ${e.terminal(v.origem)} → ${e.terminal(v.destino)}", 13, cor = c.muted)
                }
                if (o.aproveitadas.isNotEmpty()) {
                    T("Continuam valendo: " + o.aproveitadas.joinToString(" · ") { "${e.cidade(it.origem)} → ${e.cidade(it.destino)} (${hora(it.saidaMin)})" }, 13, cor = c.muted)
                }
                val fim = o.chegadaFinalMin
                if (fim == null) T("Depois desta saída não há como completar a viagem até ${e.cidade(r.destino)}.", 13, FontWeight.SemiBold, c.erro)
                else T("Chegada em ${e.cidade(r.destino)}: ${hora(fim)} · ${dataViagem(e.rData, fim)} (${atrasoTxt(o.atrasoMin ?: 0)})", 13, FontWeight.SemiBold)
                T(
                    "Custo novo: ${centavosParaReais(o.novosBilhetesCentavos)} por pessoa" +
                        if (e.rPax > 1) " · ${centavosParaReais(o.novosBilhetesCentavos * e.rPax)} para o grupo" else "",
                    13,
                )
                T("Bilhetes que deixam de ser usados: ${centavosParaReais(o.bilhetesNaoUsadosCentavos)} por pessoa (podem não ser reembolsados, conforme a empresa).", 13, cor = c.muted)
            }
        }
    }
}

// ---------------------------------------------------------------- SIMULAÇÃO
@Composable
fun TelaSimulacao(e: Estado) {
    val c = cores()
    val r = e.rotaAtual
    if (r == null) { CaixaVazia("Nenhuma rota para simular", "Escolha uma rota nos resultados.", "Buscar opções") { e.ir(Tela.BUSCAR) }; return }
    val n = r.viagens.size
    val feitos = e.concluidos.coerceIn(0, n)
    val fim = feitos == n
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Link("← Voltar à rota") { e.ir(Tela.ROTA) }
        Column {
            Eyebrow("Simulação")
            Spacer(Modifier.height(8.dp))
            T("Você está em uma simulação.", 30, FontWeight.Bold, ls = -0.9f, lh = 34)
            Spacer(Modifier.height(6.dp))
            T("${e.cidade(r.origem)} → ${e.cidade(r.destino)} · ${e.rData.br()}", 15, cor = c.muted)
        }
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(99.dp)).background(c.field)) {
            Box(Modifier.fillMaxWidth(feitos.toFloat() / n).height(8.dp).clip(RoundedCornerShape(99.dp)).background(c.primary))
        }
        T("$feitos de $n ${if (n == 1) "trecho concluído" else "trechos concluídos"}", 14, cor = c.muted)
        T("Uma passagem por trecho", 22, FontWeight.Bold, ls = -0.4f)
        r.viagens.forEachIndexed { i, v ->
            val estado = if (i < feitos) "Concluído" else if (i == feitos) "Próximo trecho" else "A seguir"
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.surface)
                    .then(if (i < feitos) Modifier.border(2.dp, c.ok, RoundedCornerShape(28.dp)) else Modifier).padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(if (i < feitos) c.ok else c.soft), contentAlignment = Alignment.Center) {
                    T(if (i < feitos) "✓" else "${i + 1}", 16, FontWeight.Bold, if (i < feitos) c.onPrimary else c.primary)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    T("${e.cidade(v.origem)} → ${e.cidade(v.destino)}", 20, FontWeight.Bold)
                    T(estado, 14, FontWeight.SemiBold, if (i < feitos) c.ok else if (i == feitos) c.primary else c.muted)
                    T("${hora(v.saidaMin)} → ${hora(v.chegadaMin)} · ${duracao(v.chegadaMin - v.saidaMin)} · ${v.empresa}", 14, cor = c.muted)
                    T("${e.terminal(v.origem)}", 13, cor = c.muted)
                    T("${centavosParaReais(v.precoCentavos * e.rPax)} · ${passageirosTxt(e.rPax)}", 15, FontWeight.SemiBold)
                    if (i == feitos && i < r.esperasMin.size) T("Depois: espera de ${esperaTxt(r.esperasMin[i])} em ${e.terminal(v.destino)}.", 13, cor = c.muted)
                    if (i == feitos) Botao("Concluir este trecho", { e.concluidos = feitos + 1 }, Modifier.padding(top = 6.dp), primario = true, pequeno = true)
                }
            }
        }
        Cartao(raio = 22, pad = 18) {
            T("Resumo da simulação", 20, FontWeight.Bold)
            val gasto = r.viagens.take(feitos).sumOf { it.precoCentavos } * e.rPax
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { T("Trechos concluídos", 15); T("$feitos de $n", 15, FontWeight.SemiBold) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { T("Valor dos trechos concluídos", 15); T(centavosParaReais(gasto), 15, FontWeight.SemiBold) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { T("Total da viagem", 15); T(centavosParaReais(r.totalCentavos * e.rPax), 15, FontWeight.SemiBold) }
            if (fim) T("✓ Viagem concluída na simulação. Nenhuma compra foi feita.", 15, FontWeight.SemiBold, c.ok)
            else T("Nenhuma compra é feita nesta simulação.", 14, cor = c.muted)
            Botao("Reiniciar simulação", { e.concluidos = 0 }, Modifier.fillMaxWidth())
        }
    }
}

// ---------------------------------------------------------------- COMPARAR
@Composable
fun TelaComparar(e: Estado) {
    val c = cores()
    val rs = e.selecionadas.mapNotNull { id -> e.todas?.firstOrNull { it.id == id } }
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Link("← Voltar aos resultados") { e.ir(Tela.RESULTADOS) }
        if (rs.size < 2) {
            CaixaVazia("Escolha ao menos duas opções", "Marque “Comparar” em duas ou três opções para vê-las lado a lado.", "Ver opções") { e.ir(Tela.RESULTADOS) }
            return@Column
        }
        Column {
            Eyebrow("Comparar viagens")
            Spacer(Modifier.height(8.dp))
            T("Opções lado a lado.", 30, FontWeight.Bold, ls = -0.9f)
            Spacer(Modifier.height(6.dp))
            T("${e.cidade(e.rOrigem)} → ${e.cidade(e.rDestino)} · ${e.rData.br()} · ${passageirosTxt(e.rPax)}", 15, cor = c.muted)
        }
        val linhas: List<Pair<String, (Rota) -> String>> = listOf(
            "Total conhecido" to { r -> centavosParaReais(r.totalCentavos * e.rPax) },
            "Por pessoa" to { r -> centavosParaReais(r.totalCentavos) },
            "Preços por trecho" to { r -> r.viagens.joinToString("\n") { "${e.cidade(it.origem)} → ${e.cidade(it.destino)}: ${centavosParaReais(it.precoCentavos)} por pessoa" } },
            "Saída" to { r -> "${hora(r.saidaMin)} · ${dataViagem(e.rData, r.saidaMin)}" },
            "Chegada" to { r -> "${hora(r.chegadaMin)} · ${dataViagem(e.rData, r.chegadaMin)}" },
            "Duração completa" to { r -> duracao(r.duracaoMin) },
            "Esperas nas conexões" to { r -> if (r.esperasMin.isEmpty()) "Sem conexão" else r.viagens.dropLast(1).mapIndexed { i, v -> "${esperaTxt(r.esperasMin[i])} em ${e.terminal(v.destino)}" }.joinToString("\n") },
            "Empresas" to { r -> r.viagens.map { it.empresa }.distinct().joinToString("\n") },
            "Categorias" to { r -> r.viagens.mapIndexed { i, v -> "Trecho ${i + 1}: ${v.categoria}" }.joinToString("\n") },
            "Comodidades do exemplo" to { r -> r.viagens.mapIndexed { i, v -> "Trecho ${i + 1}: ${v.comodidades.joinToString(", ")}. Wi-Fi: Não informado." }.joinToString("\n") },
        )
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.surface).horizontalScroll(rememberScrollState())) {
            Row {
                Box(Modifier.width(130.dp).padding(14.dp)) { T("Viagem completa", 14, cor = c.muted) }
                rs.forEach { r ->
                    Column(Modifier.width(210.dp).padding(14.dp)) {
                        T("Opção ${e.nome(r)}", 16, FontWeight.Bold)
                        T("${r.categoria()} · ${r.stops()}", 14, cor = c.muted)
                    }
                }
            }
            linhas.forEach { (rotulo, f) ->
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
                Row {
                    Box(Modifier.width(130.dp).padding(14.dp)) { T(rotulo, 14, FontWeight.Medium, c.muted) }
                    rs.forEach { r -> Box(Modifier.width(210.dp).padding(14.dp)) { T(f(r), 14, if (rotulo == "Total conhecido") FontWeight.SemiBold else FontWeight.Normal) } }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
            Row {
                Box(Modifier.width(130.dp).padding(14.dp)) { T("Escolher com clareza", 14, FontWeight.Medium, c.muted) }
                rs.forEach { r -> Box(Modifier.width(210.dp).padding(14.dp)) { Botao("Ver detalhes", { e.abrirRota(r) }, pequeno = true) } }
            }
        }
        T(SEPARADAS, 13, cor = c.muted)
        T("Comodidades ilustrativas. Disponibilidade não confirmada para nenhuma viagem real.", 13, cor = c.muted)
        T(COTACAO, 13, cor = c.muted)
        T(TAXAS, 13, cor = c.muted)
    }
}

// ---------------------------------------------------------------- PLANEJAMENTOS
@Composable
fun TelaPlanejamentos(e: Estado) {
    val c = cores()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column {
            Eyebrow("Meus planejamentos")
            Spacer(Modifier.height(8.dp))
            T("Seus próximos caminhos.", 30, FontWeight.Bold, ls = -0.9f)
            Spacer(Modifier.height(6.dp))
            T("Salvos neste aparelho, para revisar no seu tempo.", 15, cor = c.muted)
        }
        if (e.planos.isEmpty()) CaixaVazia("Nenhum planejamento salvo", "Escolha uma rota e toque em Salvar planejamento.", "Buscar opções") { e.ir(Tela.BUSCAR) }
        e.planos.toList().forEach { p ->
            val r = p.rota
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.surface).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                T("${e.cidade(r.origem)} → ${e.cidade(r.destino)}", 22, FontWeight.Bold, ls = -0.4f)
                T("${p.data.br()} · ${hora(r.saidaMin)} → ${hora(r.chegadaMin)} · ${duracao(r.duracaoMin)} · ${r.stops()}", 15, cor = c.muted)
                T(centavosParaReais(r.totalCentavos * p.pax) + " · " + passageirosTxt(p.pax), 18, FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Botao("Abrir", { e.abrirPlano(p) }, pequeno = true, primario = true)
                    Botao("Simular", { e.rData = p.data; e.rPax = p.pax; e.simular(r) }, pequeno = true)
                    Botao("Remover", { e.planos.remove(p) }, pequeno = true)
                }
            }
        }
        T("Os planejamentos ficam só na memória por enquanto. O armazenamento permanente chega com a conta de usuário (história H9).", 13, cor = c.muted)
    }
}
