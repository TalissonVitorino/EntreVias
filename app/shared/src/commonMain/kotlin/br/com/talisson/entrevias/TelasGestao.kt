package br.com.talisson.entrevias

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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import br.com.talisson.entrevias.domain.Avaliacao
import br.com.talisson.entrevias.domain.Chamado
import br.com.talisson.entrevias.domain.TipoOcorrencia
import br.com.talisson.entrevias.domain.ViagemDoGestor

private enum class AbaGestao(val rotulo: String) { VIAGENS("Viagens"), AJUDA("Ajuda"), RECLAMACOES("Reclamações") }

// ---------------------------------------------------------------- GESTÃO (D4, F1, F2): só o gestor
@Composable
fun TelaGestao(e: Estado) {
    val c = cores()
    val u = e.usuario
    if (u == null || !u.ehGestor) {
        Cartao {
            T("Área do gestor", 22, FontWeight.Bold)
            T("Você não tem acesso a esta área.", 15, cor = c.muted)
            Botao("Voltar", { e.ir(Tela.CONTA) }, primario = true)
        }
        return
    }
    LaunchedEffect(u.id) { e.carregarGestao() }
    var aba by remember { mutableStateOf(AbaGestao.VIAGENS) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Link("‹  Conta") { e.ir(Tela.CONTA) }
        Column {
            Eyebrow("Gestão")
            Spacer(Modifier.height(8.dp))
            T("Viagens e suporte", 30, FontWeight.Bold, ls = -0.9f)
            Spacer(Modifier.height(6.dp))
            T("O que você registra aqui avisa quem comprou, na hora.", 15, cor = c.muted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AbaGestao.entries.forEach { a ->
                val n = when (a) { AbaGestao.AJUDA -> e.gestorChamados.count { it.status == "aberto" }; AbaGestao.RECLAMACOES -> e.gestorReclamacoes.size; else -> 0 }
                Botao(if (n > 0) "${a.rotulo} ($n)" else a.rotulo, { aba = a }, Modifier.weight(1f), ativo = aba == a, pequeno = true)
            }
        }
        e.gestorAviso?.let { T("✓  $it", 14, FontWeight.SemiBold, c.ok) }
        when (val carga = e.gestorCarga) {
            is Carga.Erro -> T("✕  ${carga.mensagem}", 14, FontWeight.SemiBold, c.erro)
            is Carga.Carregando -> T("Carregando…", 15, cor = c.muted)
            Carga.Pronto -> Unit
        }
        when (aba) {
            AbaGestao.VIAGENS -> ViagensDoDia(e)
            AbaGestao.AJUDA -> ChamadosDoGestor(e)
            AbaGestao.RECLAMACOES -> Reclamacoes(e)
        }
    }
}

@Composable
private fun ViagensDoDia(e: Estado) {
    val c = cores()
    var aberta by remember { mutableStateOf<Int?>(null) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Botao("‹", { e.mudarDiaDaGestao(-1) }, pequeno = true)
        T(e.gestorData.br(), 18, FontWeight.Bold)
        Botao("›", { e.mudarDiaDaGestao(1) }, pequeno = true)
    }
    if (e.gestorCarga == Carga.Pronto && e.gestorViagens.isEmpty()) T("Nenhuma viagem neste dia.", 15, cor = c.muted)
    e.gestorViagens.toList().forEach { v ->
        CartaoViagemDoGestor(e, v, aberta == v.viagem.id) { aberta = if (aberta == v.viagem.id) null else v.viagem.id }
    }
}

@Composable
private fun CartaoViagemDoGestor(e: Estado, g: ViagemDoGestor, aberta: Boolean, alternar: () -> Unit) {
    val c = cores()
    val v = g.viagem
    Cartao(raio = 24, pad = 20, borda = true) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            T("${e.cidade(v.origem)} → ${e.cidade(v.destino)}", 18, FontWeight.Bold, ls = -0.3f, mod = Modifier.weight(1f))
            if (g.cancelada) Etiqueta("Cancelada") else if (g.atrasoMin != 0) Etiqueta(if (g.atrasoMin > 0) "+${g.atrasoMin} min" else "${g.atrasoMin} min")
        }
        T("${v.empresa} · ${hora(v.saidaMin)} → ${hora(v.chegadaMin)} · ${g.comprasPagas} ${if (g.comprasPagas == 1) "compra paga" else "compras pagas"}", 14, cor = c.muted)
        Botao(if (aberta) "Fechar" else "Registrar ocorrência", alternar, pequeno = true, primario = !aberta)
        if (aberta) FormOcorrencia(e, g)
    }
}

@Composable
private fun FormOcorrencia(e: Estado, g: ViagemDoGestor) {
    val c = cores()
    val id = g.viagem.id
    var tipo by remember(id) { mutableStateOf(TipoOcorrencia.ATRASO) }
    var minutos by remember(id) { mutableStateOf("") }
    var confirmando by remember(id) { mutableStateOf(false) }
    var erro by remember(id) { mutableStateOf<String?>(null) }
    var ocupado by remember(id) { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(TipoOcorrencia.ATRASO to "Atraso", TipoOcorrencia.HORARIO to "Horário", TipoOcorrencia.CANCELADA to "Cancelar").forEach { (t, r) ->
            Botao(r, { tipo = t; confirmando = false; erro = null }, Modifier.weight(1f), ativo = tipo == t, pequeno = true)
        }
    }
    if (tipo != TipoOcorrencia.CANCELADA) {
        CampoTexto(
            if (tipo == TipoOcorrencia.ATRASO) "Atraso, em minutos" else "Mudança de horário, em minutos (negativo = mais cedo)",
            minutos, { minutos = it.filter { ch -> ch.isDigit() || ch == '-' }.take(4) },
            teclado = KeyboardType.Number, comErro = erro != null,
        )
    } else {
        T("A empresa cancela a viagem: quem pagou recebe 100% de volta, sem taxa. Não dá para desfazer.", 14, cor = c.muted)
    }
    erro?.let { T("✕  $it", 14, FontWeight.SemiBold, c.erro) }
    val enviar = {
        if (!ocupado) {
            ocupado = true; erro = null
            e.registrarOcorrencia(id, tipo, if (tipo == TipoOcorrencia.CANCELADA) 0 else minutos.toIntOrNull() ?: 0) { msg -> ocupado = false; erro = msg; if (msg == null) { minutos = ""; confirmando = false } }
        }
    }
    if (tipo == TipoOcorrencia.CANCELADA && !confirmando) Botao("Cancelar esta viagem", { confirmando = true }, Modifier.fillMaxWidth(), primario = true)
    else Botao(if (tipo == TipoOcorrencia.CANCELADA) "Confirmar cancelamento" else "Registrar e avisar", { enviar() }, Modifier.fillMaxWidth(), primario = true)
}

@Composable
private fun ChamadosDoGestor(e: Estado) {
    val c = cores()
    if (e.gestorCarga == Carga.Pronto && e.gestorChamados.isEmpty()) T("Nenhum pedido de ajuda.", 15, cor = c.muted)
    e.gestorChamados.toList().forEach { ch -> CartaoChamado(e, ch) }
}

@Composable
private fun CartaoChamado(e: Estado, ch: Chamado) {
    val c = cores()
    var texto by remember(ch.id) { mutableStateOf("") }
    var erro by remember(ch.id) { mutableStateOf<String?>(null) }
    var ocupado by remember(ch.id) { mutableStateOf(false) }
    Cartao(raio = 24, pad = 20, borda = true) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            T(ch.assunto, 17, FontWeight.Bold, mod = Modifier.weight(1f))
            Etiqueta(if (ch.status == "aberto") "Aberto" else "Respondido")
        }
        T("${ch.protocolo} · ${Tempo.dataHora(ch.criadoEm)}${ch.pedidoId?.let { " · compra $it" } ?: ""}", 13, cor = c.muted)
        ch.mensagem?.let { T(it, 15) }
        if (ch.resposta != null) T("Resposta: ${ch.resposta}", 14, FontWeight.Medium, c.ok)
        else {
            CampoTexto("Sua resposta", texto, { texto = it; erro = null }, comErro = erro != null)
            erro?.let { T("✕  $it", 14, FontWeight.SemiBold, c.erro) }
            Botao("Responder", {
                if (!ocupado) { ocupado = true; e.responderChamado(ch.id, texto) { msg -> ocupado = false; erro = msg } }
            }, Modifier.fillMaxWidth(), primario = true, pequeno = true)
        }
    }
}

@Composable
private fun Reclamacoes(e: Estado) {
    val c = cores()
    if (e.gestorCarga == Carga.Pronto && e.gestorReclamacoes.isEmpty()) T("Nenhuma reclamação.", 15, cor = c.muted)
    e.gestorReclamacoes.toList().forEach { a -> CartaoReclamacao(a) }
}

@Composable
private fun CartaoReclamacao(a: Avaliacao) {
    val c = cores()
    Cartao(raio = 24, pad = 20, borda = true) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            T("★".repeat(a.estrelas) + "☆".repeat((5 - a.estrelas).coerceAtLeast(0)), 18, FontWeight.Bold, c.primary)
            T(Tempo.dataHora(a.criadaEm), 13, cor = c.muted)
        }
        T("Compra ${a.pedidoId} · trecho ${a.trecho}", 13, cor = c.muted)
        if (a.tags.isNotEmpty()) T(a.tags.joinToString(" · "), 14, FontWeight.Medium)
        a.comentario?.let { T(it, 15) }
    }
}
