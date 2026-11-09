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
import br.com.talisson.entrevias.domain.CategoriaPassageiro
import br.com.talisson.entrevias.domain.FormaPagamento
import br.com.talisson.entrevias.domain.Passageiro
import br.com.talisson.entrevias.domain.PassageiroPedido
import br.com.talisson.entrevias.domain.TIPO_CARTAO
import br.com.talisson.entrevias.domain.ValidacaoPerfil

@Composable
private fun MensagensCarteira(e: Estado) {
    val c = cores()
    e.carteiraErro?.let { T("✕  $it", 14, FontWeight.SemiBold, c.erro) }
    e.carteiraAviso?.let { T("✓  $it", 14, FontWeight.SemiBold, c.ok) }
}

@Composable
private fun PrecisaDeLogin(e: Estado): Boolean {
    if (e.usuario != null) return false
    val c = cores()
    Cartao {
        T("Entre na sua conta", 22, FontWeight.Bold)
        T("Esta área é só para quem está logado.", 15, cor = c.muted)
        Botao("Ir para a conta", { e.ir(Tela.CONTA) }, primario = true)
    }
    return true
}

/** Linha de chips de escolha única (o design usa chips; aqui são botões pequenos, no mesmo tom do resto do app). */
@Composable
private fun Escolhas(opcoes: List<String>, atual: String, onEscolher: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { opcoes.forEach { o -> Botao(o, { onEscolher(o) }, pequeno = true, ativo = o == atual) } }
}

// ---------------------------------------------------------------- A5 FORMAS DE PAGAMENTO (US10)
@Composable
fun TelaPagamentos(e: Estado) {
    val c = cores()
    if (PrecisaDeLogin(e)) return
    LaunchedEffect(e.usuario?.id) { e.limparMensagensDaCarteira(); e.carregarCarteira() }
    var adicionando by remember { mutableStateOf(false) }
    var confirmando by remember { mutableStateOf<Int?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Link("‹  Conta") { e.ir(Tela.CONTA) }
        Column {
            Eyebrow("Conta")
            Spacer(Modifier.height(8.dp))
            T("Formas de pagamento", 30, FontWeight.Bold, ls = -0.9f)
            Spacer(Modifier.height(6.dp))
            T("Guardamos só os 4 últimos dígitos. O número completo e o CVV nunca saem do seu aparelho (LGPD).", 15, cor = c.muted)
        }
        when (val carga = e.carteiraCarga) {
            is Carga.Carregando -> T("Carregando…", 15, cor = c.muted)
            is Carga.Erro -> T("✕  ${carga.mensagem}", 14, FontWeight.SemiBold, c.erro)
            Carga.Pronto -> if (e.formas.isEmpty() && !adicionando) T("Nenhuma forma de pagamento guardada.", 15, cor = c.muted)
        }
        e.formas.toList().forEach { f ->
            Cartao(raio = 24, pad = 20, borda = true) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        T(if (f.tipo == TIPO_CARTAO) "Cartão de crédito" else "Pix", 14, cor = c.muted)
                        T(f.rotulo, 20, FontWeight.Bold, ls = -0.4f)
                        f.validade?.let { T("vence $it", 14, cor = c.muted) }
                    }
                    if (f.padrao) Etiqueta("Padrão")
                }
                if (confirmando == f.id) {
                    T(if (f.padrao) "Remover a forma de pagamento padrão? Compras já feitas e reembolsos em andamento não são afetados." else "Remover esta forma de pagamento?", 14, FontWeight.SemiBold, c.erro)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Botao("Remover", { e.removerForma(f); confirmando = null }, pequeno = true, primario = true)
                        Botao("Cancelar", { confirmando = null }, pequeno = true)
                    }
                } else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!f.padrao) Botao("Tornar padrão", { e.tornarPadrao(f) }, pequeno = true, primario = true)
                    Botao("Remover", { confirmando = f.id }, pequeno = true)
                }
            }
        }
        MensagensCarteira(e)
        if (!adicionando) {
            if (e.formas.size < ValidacaoPerfil.MAX_FORMAS) Botao("+ Adicionar forma de pagamento", { e.limparMensagensDaCarteira(); adicionando = true }, Modifier.fillMaxWidth(), ativo = true)
            else T("Você já tem ${ValidacaoPerfil.MAX_FORMAS} formas de pagamento. Remova uma para adicionar outra.", 13, cor = c.muted)
        } else FormNovaForma(e) { adicionando = false }
    }
}

@Composable
private fun FormNovaForma(e: Estado, aoFechar: () -> Unit) {
    var tipo by remember { mutableStateOf("Cartão") }
    var numero by remember { mutableStateOf("") }
    var validade by remember { mutableStateOf("") }
    var pixTipo by remember { mutableStateOf("CPF") }
    var pixFinal by remember { mutableStateOf("") }
    var padrao by remember { mutableStateOf(false) }
    val c = cores()
    Cartao {
        T("Nova forma de pagamento", 20, FontWeight.Bold, ls = -0.4f)
        Escolhas(listOf("Cartão", "Pix"), tipo) { tipo = it; e.limparMensagensDaCarteira() }
        if (tipo == "Cartão") {
            CampoTexto("Número do cartão", numero, { numero = it.filter { ch -> ch in '0'..'9' || ch == ' ' }.take(23) }, dica = "0000 0000 0000 0000", teclado = KeyboardType.Number, comErro = e.carteiraErro != null)
            CampoTexto("Validade (MM/AA)", validade, { validade = it.take(5) }, dica = "08/29", teclado = KeyboardType.Number)
            T("Conferimos o número aqui no aparelho. Só a bandeira, os 4 últimos dígitos e a validade são guardados.", 13, cor = c.muted)
        } else {
            T("Tipo da chave", 14, FontWeight.Medium, c.muted)
            Escolhas(listOf("CPF", "E-mail"), pixTipo) { pixTipo = it }
            Escolhas(listOf("Celular", "Aleatória"), pixTipo) { pixTipo = it }
            CampoTexto("Final da chave (até 4 caracteres)", pixFinal, { pixFinal = it.take(4) }, dica = "321")
            T("Não guardamos a chave inteira, só o final para você reconhecer.", 13, cor = c.muted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Chave(padrao) { padrao = it }
            T("Usar como padrão", 15, cor = c.fg)
        }
        MensagensCarteira(e)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Botao(if (e.carteiraOcupada) "Salvando…" else "Salvar", {
                if (tipo == "Cartão") e.adicionarCartao(numero, validade, padrao) else e.adicionarPix(pixTipo, pixFinal, padrao)
            }, primario = true, pequeno = true)
            Botao("Fechar", { numero = ""; aoFechar() }, pequeno = true)
        }
    }
}

// ---------------------------------------------------------------- A6 PASSAGEIROS
@Composable
fun TelaPassageiros(e: Estado) {
    val c = cores()
    if (PrecisaDeLogin(e)) return
    LaunchedEffect(e.usuario?.id) { e.limparMensagensDaCarteira(); e.carregarCarteira() }
    // null = formulário fechado; -1 = novo; outro valor = id em edição
    var editando by remember { mutableStateOf<Int?>(null) }
    var confirmando by remember { mutableStateOf<Int?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Link("‹  Conta") { e.ir(Tela.CONTA) }
        Column {
            Eyebrow("Conta")
            Spacer(Modifier.height(8.dp))
            T("Passageiros", 30, FontWeight.Bold, ls = -0.9f)
            Spacer(Modifier.height(6.dp))
            T("Quem viaja com você. Guardamos só os 3 últimos dígitos do documento.", 15, cor = c.muted)
        }
        when (val carga = e.carteiraCarga) {
            is Carga.Carregando -> T("Carregando…", 15, cor = c.muted)
            is Carga.Erro -> T("✕  ${carga.mensagem}", 14, FontWeight.SemiBold, c.erro)
            Carga.Pronto -> if (e.passageiros.isEmpty() && editando == null) T("Nenhum passageiro cadastrado.", 15, cor = c.muted)
        }
        e.passageiros.toList().forEach { p -> CartaoPassageiro(e, p, confirmando == p.id, { confirmando = it }) { editando = p.id } }
        MensagensCarteira(e)
        editando?.let { id -> FormPassageiro(e, e.passageiros.firstOrNull { it.id == id }) { editando = null } }
        if (editando == null) {
            if (e.passageiros.size < ValidacaoPerfil.MAX_PASSAGEIROS) Botao("+ Adicionar passageiro", { e.limparMensagensDaCarteira(); editando = -1 }, Modifier.fillMaxWidth(), ativo = true)
            else T("Você já tem ${ValidacaoPerfil.MAX_PASSAGEIROS} passageiros. Remova um para adicionar outro.", 13, cor = c.muted)
        }
    }
}

@Composable
private fun CartaoPassageiro(e: Estado, p: Passageiro, confirmando: Boolean, onConfirmar: (Int?) -> Unit, onEditar: () -> Unit) {
    val c = cores()
    Cartao(raio = 24, pad = 20, borda = true) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            T(p.nome, 20, FontWeight.Bold, ls = -0.4f, mod = Modifier.weight(1f))
            Etiqueta(p.relacao)
        }
        T("${p.idade} ${if (p.idade == 1) "ano" else "anos"} · ${CategoriaPassageiro.rotulo(p.categoria)}", 14, cor = c.muted)
        p.documentoFinal?.let { T("Documento ••• $it", 14, cor = c.muted) }
        p.acessibilidade?.let { T("Acessibilidade: $it", 14, cor = c.muted) }
        if (confirmando) {
            T("Remover ${p.nome}? Compras já feitas não são afetadas.", 14, FontWeight.SemiBold, c.erro)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Botao("Remover", { e.removerPassageiro(p); onConfirmar(null) }, pequeno = true, primario = true)
                Botao("Cancelar", { onConfirmar(null) }, pequeno = true)
            }
        } else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Botao("Editar", onEditar, pequeno = true, primario = true)
            Botao("Remover", { onConfirmar(p.id) }, pequeno = true)
        }
    }
}

@Composable
private fun FormPassageiro(e: Estado, atual: Passageiro?, aoFechar: () -> Unit) {
    val c = cores()
    var nome by remember(atual?.id) { mutableStateOf(atual?.nome.orEmpty()) }
    var relacao by remember(atual?.id) { mutableStateOf(atual?.relacao ?: "Dependente") }
    var idade by remember(atual?.id) { mutableStateOf(atual?.idade?.toString().orEmpty()) }
    var doc by remember(atual?.id) { mutableStateOf(atual?.documentoFinal.orEmpty()) }
    var acess by remember(atual?.id) { mutableStateOf(atual?.acessibilidade.orEmpty()) }
    var erro by remember(atual?.id) { mutableStateOf<String?>(null) }
    var ocupado by remember(atual?.id) { mutableStateOf(false) }
    Cartao {
        T(if (atual == null) "Novo passageiro" else "Editar passageiro", 20, FontWeight.Bold, ls = -0.4f)
        CampoTexto("Nome completo", nome, { nome = it })
        T("Relação", 14, FontWeight.Medium, c.muted)
        Escolhas(ValidacaoPerfil.RELACOES, relacao) { relacao = it }
        CampoTexto("Idade", idade, { idade = it.filter { ch -> ch in '0'..'9' }.take(3) }, dica = "8", teclado = KeyboardType.Number)
        idade.toIntOrNull()?.let { T("Categoria: ${CategoriaPassageiro.rotulo(CategoriaPassageiro.daIdade(it))}", 13, cor = c.muted) }
        CampoTexto("Documento: 3 últimos dígitos (opcional)", doc, { doc = it.filter { ch -> ch in '0'..'9' }.take(3) }, dica = "411", teclado = KeyboardType.Number)
        CampoTexto("Necessidade de acessibilidade (opcional)", acess, { acess = it.take(80) }, dica = "Cadeira de rodas")
        erro?.let { T("✕  $it", 14, FontWeight.SemiBold, c.erro) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Botao(if (ocupado) "Salvando…" else "Salvar", {
                if (!ocupado) {
                    ocupado = true; erro = null
                    val pedido = PassageiroPedido(nome, relacao, idade.toIntOrNull() ?: -1, doc.ifBlank { null }, acess.ifBlank { null })
                    e.salvarPassageiro(atual?.id, pedido) { msg -> ocupado = false; if (msg == null) aoFechar() else erro = msg }
                }
            }, primario = true, pequeno = true)
            Botao("Cancelar", aoFechar, pequeno = true)
        }
    }
}
