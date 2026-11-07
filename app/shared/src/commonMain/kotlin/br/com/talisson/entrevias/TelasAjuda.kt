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
import br.com.talisson.entrevias.domain.NovaAvaliacao
import br.com.talisson.entrevias.domain.StatusPedido
import br.com.talisson.entrevias.domain.ValidacaoAjuda
import kotlin.time.Clock

// ---------------------------------------------------------------- F1 AJUDA COM A VIAGEM (RQ53)
@Composable
fun TelaAjuda(e: Estado) {
    val c = cores()
    if (e.usuario == null) {
        Cartao { T("Entre na sua conta", 22, FontWeight.Bold); T("Para pedir ajuda com uma viagem, entre na sua conta.", 15, cor = c.muted); Botao("Ir para a conta", { e.ir(Tela.CONTA) }, primario = true) }
        return
    }
    var assunto by remember { mutableStateOf<String?>(null) }
    var mensagem by remember { mutableStateOf("") }
    val novo = e.chamadoNovo
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Link("‹  Viagens") { e.abrirHistorico() }
        Column {
            Eyebrow("Suporte")
            Spacer(Modifier.height(8.dp))
            T("Como podemos ajudar?", 30, FontWeight.Bold, ls = -0.9f)
            Spacer(Modifier.height(6.dp))
            T("Sobre uma viagem ou sobre o app. Você acompanha a resposta aqui e nos Avisos.", 15, cor = c.muted)
        }
        if (novo != null) {
            Cartao {
                T("✓  Pedido enviado", 22, FontWeight.Bold, c.ok)
                T("Protocolo ${novo.protocolo}", 18, FontWeight.Bold)
                T("Quando houver resposta, ela aparece abaixo e em Avisos.", 14, cor = c.muted)
                Botao("Fazer outro pedido", { e.chamadoNovo = null; assunto = null; mensagem = "" }, Modifier.fillMaxWidth())
            }
        } else {
            Cartao {
                T("Sobre qual viagem?", 18, FontWeight.Bold)
                val viagens = e.historico.filter { it.status != StatusPedido.CARRINHO }.take(4)
                Botao("Nenhuma específica", { e.ajudaPedido = null; e.ajudaTrecho = null }, Modifier.fillMaxWidth(), ativo = e.ajudaPedido == null, pequeno = true)
                viagens.forEach { p -> Botao("${p.codigo} · ${e.cidade(p.trechos.first().origem)} → ${e.cidade(p.trechos.last().destino)}", { e.ajudaPedido = p; e.ajudaTrecho = null }, Modifier.fillMaxWidth(), ativo = e.ajudaPedido?.id == p.id, pequeno = true) }
                e.ajudaPedido?.let { p ->
                    if (p.trechos.size > 1) {
                        T("O problema é com…", 14, FontWeight.Medium, c.muted)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Botao("Viagem inteira", { e.ajudaTrecho = null }, pequeno = true, ativo = e.ajudaTrecho == null)
                            p.trechos.forEach { t -> Botao("Só ${e.cidade(t.origem)} → ${e.cidade(t.destino)}", { e.ajudaTrecho = t.ordem }, pequeno = true, ativo = e.ajudaTrecho == t.ordem) }
                        }
                    }
                }
            }
            Cartao {
                T("Assunto", 18, FontWeight.Bold)
                ValidacaoAjuda.ASSUNTOS.forEach { a -> Botao(a, { assunto = a; e.ajudaErro = null }, Modifier.fillMaxWidth(), ativo = assunto == a, pequeno = true) }
                CampoTexto("Conte o que aconteceu (opcional)", mensagem, { mensagem = it.take(ValidacaoAjuda.MENSAGEM_MAX) }, dica = "Escreva aqui", comErro = e.ajudaErro != null)
                e.ajudaErro?.let { T("✕  $it", 14, FontWeight.SemiBold, c.erro) }
                Botao(if (e.ajudaOcupada) "Enviando…" else "Falar com o suporte", { e.enviarChamado(assunto.orEmpty(), mensagem) }, Modifier.fillMaxWidth(), primario = true, grande = true)
                T("Nesta versão o suporte não é um atendimento ao vivo: o pedido vira um protocolo e a resposta chega por aqui.", 13, cor = c.muted)
            }
        }
        if (e.chamados.isNotEmpty()) {
            T("Meus pedidos de ajuda", 20, FontWeight.Bold, ls = -0.4f)
            e.chamados.toList().forEach { ch ->
                Cartao(raio = 24, pad = 20, borda = true) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        T("${ch.protocolo} · ${ch.assunto}", 16, FontWeight.Bold, mod = Modifier.weight(1f))
                        Etiqueta(if (ch.status == "respondido") "Respondido" else "Aberto")
                    }
                    ch.mensagem?.let { T(it, 14, cor = c.muted) }
                    ch.resposta?.let { T("Resposta: $it", 14, FontWeight.SemiBold, c.ok) }
                    T(Tempo.dataHora(ch.criadoEm), 12, cor = c.muted)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- F2 AVALIAR A VIAGEM (RQ56)
@Composable
fun TelaAvaliar(e: Estado) {
    val c = cores()
    val p = e.pedidoAberto
    if (p == null || e.usuario == null) { Cartao { T("Nenhuma viagem aberta", 22, FontWeight.Bold); Botao("Ver minhas viagens", { e.abrirHistorico() }, primario = true) }; return }
    val t = p.trechos.firstOrNull { it.ordem == e.avaliarTrecho } ?: p.trechos.first()
    var estrelas by remember(t.ordem) { mutableStateOf(0) }
    var tags by remember(t.ordem) { mutableStateOf(setOf<String>()) }
    var comentario by remember(t.ordem) { mutableStateOf("") }
    val feita = e.avaliacaoEnviada
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Link("‹  Detalhes da compra") { e.abrirDetalhe(p) }
        Column {
            Eyebrow("Como foi?")
            Spacer(Modifier.height(8.dp))
            T("Avalie sua viagem", 30, FontWeight.Bold, ls = -0.9f)
            Spacer(Modifier.height(6.dp))
            T("${e.cidade(t.origem)} → ${e.cidade(t.destino)} · ${t.empresa}", 15, cor = c.muted)
        }
        if (feita != null) {
            Cartao {
                T("✓  Obrigado pela avaliação", 22, FontWeight.Bold, c.ok)
                T("${"★".repeat(feita.estrelas)}${"☆".repeat(5 - feita.estrelas)}", 24, FontWeight.Bold, c.primary)
                if (feita.tags.isNotEmpty()) T(feita.tags.joinToString(" · "), 14, cor = c.muted)
                feita.comentario?.let { T(it, 15, cor = c.fg) }
                if (feita.reclamacao) T("Sua reclamação foi registrada e o gestor da plataforma pode lê-la.", 14, FontWeight.SemiBold, c.ok)
                Botao("Voltar à compra", { e.abrirDetalhe(p) }, Modifier.fillMaxWidth(), primario = true)
            }
        } else {
            Cartao {
                T("Sua nota", 18, FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { (1..5).forEach { n -> T(if (n <= estrelas) "★" else "☆", 36, FontWeight.Bold, c.primary, Modifier.clickable { estrelas = n }) } }
                T("O que aconteceu?", 14, FontWeight.Medium, c.muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ValidacaoAjuda.TAGS.take(3).forEach { g -> Botao(g, { tags = if (g in tags) tags - g else tags + g }, pequeno = true, ativo = g in tags) } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ValidacaoAjuda.TAGS.drop(3).forEach { g -> Botao(g, { tags = if (g in tags) tags - g else tags + g }, pequeno = true, ativo = g in tags) } }
                CampoTexto("Comentário ou reclamação (opcional)", comentario, { comentario = it.take(ValidacaoAjuda.COMENTARIO_MAX) }, dica = "Conte o que podemos melhorar…", comErro = e.avaliacaoErro != null)
                e.avaliacaoErro?.let { T("✕  $it", 14, FontWeight.SemiBold, c.erro) }
                Botao(if (e.avaliacaoOcupada) "Enviando…" else "Enviar avaliação", { e.enviarAvaliacao(NovaAvaliacao(estrelas, tags.toList(), comentario.ifBlank { null }, false)) }, Modifier.fillMaxWidth(), primario = true, grande = true)
                Botao("Registrar reclamação", { e.enviarAvaliacao(NovaAvaliacao(estrelas.coerceAtLeast(1), tags.toList(), comentario.ifBlank { null }, true)) }, Modifier.fillMaxWidth())
                T("Reclamação pede que você conte o que aconteceu (pelo menos ${ValidacaoAjuda.RECLAMACAO_MIN} caracteres).", 13, cor = c.muted)
            }
        }
    }
}

/** Só para mostrar ou esconder o botão "Avaliar": quem decide de verdade se já dá para avaliar é o servidor. */
fun jaChegou(chegadaRealSeg: Long) = chegadaRealSeg <= Clock.System.now().epochSeconds
