package br.com.talisson.entrevias

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import br.com.talisson.entrevias.domain.CadastroPedido
import br.com.talisson.entrevias.domain.Empresa
import br.com.talisson.entrevias.domain.EmpresaPedido
import br.com.talisson.entrevias.domain.PerfilPedido

@Composable
private fun Mensagens(e: Estado) {
    val c = cores()
    e.contaErro?.let { T("✕  $it", 14, FontWeight.SemiBold, c.erro) }
    e.contaAviso?.let { T("✓  $it", 14, FontWeight.SemiBold, c.ok) }
}

// ---------------------------------------------------------------- CONTA (US9)
@Composable
fun TelaConta(e: Estado) {
    val c = cores()
    val u = e.usuario
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (u == null) {
            Column {
                Eyebrow("Sua conta")
                Spacer(Modifier.height(8.dp))
                T("Entre para comprar e guardar suas viagens.", 30, FontWeight.Bold, ls = -0.9f)
                Spacer(Modifier.height(6.dp))
                T("Você pode buscar e comparar rotas sem conta.", 15, cor = c.muted)
            }
            Cartao {
                Botao("Entrar", { e.limparMensagensDeConta(); e.ir(Tela.ENTRAR) }, Modifier.fillMaxWidth(), primario = true, grande = true)
                Botao("Criar conta", { e.limparMensagensDeConta(); e.ir(Tela.CADASTRO) }, Modifier.fillMaxWidth(), grande = true)
            }
            return@Column
        }
        var nome by remember(u.id) { mutableStateOf(u.nome) }
        var celular by remember(u.id) { mutableStateOf(celularBr(u.celular)) }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(c.soft), contentAlignment = Alignment.Center) {
                T(u.nome.firstOrNull()?.uppercase() ?: "?", 24, FontWeight.Bold, c.primary)
            }
            Column {
                T(u.nome, 24, FontWeight.Bold, ls = -0.5f)
                T(u.email, 14, cor = c.muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (u.ehGestor) Etiqueta("Gestor")
                    if (u.emailVerificado) Etiqueta("E-mail confirmado")
                }
            }
        }
        if (!u.emailVerificado) Cartao {
            T("Confirme seu e-mail", 20, FontWeight.Bold, ls = -0.4f)
            T("Enviamos um código de 6 dígitos para ${u.email}. Confirmar garante que você consiga recuperar a senha.", 14, cor = c.muted)
            Mensagens(e)
            Botao("Informar o código", { e.abrirVerificacao() }, Modifier.fillMaxWidth(), primario = true)
        }
        Cartao {
            T("Seus dados", 20, FontWeight.Bold, ls = -0.4f)
            CampoTexto("Nome completo", nome, { nome = it })
            CampoTexto("Celular", celular, { celular = it }, dica = "(41) 99999-0000", teclado = KeyboardType.Phone)
            T("O e-mail é o seu acesso e não pode ser alterado aqui.", 13, cor = c.muted)
            Mensagens(e)
            Botao(if (e.contaOcupada) "Salvando…" else "Salvar", { e.salvarPerfil(PerfilPedido(nome, celular)) }, Modifier.fillMaxWidth(), primario = true)
        }
        if (u.ehGestor) Cartao {
            T("Gestão", 20, FontWeight.Bold, ls = -0.4f)
            T("Empresas parceiras que aparecem nas buscas.", 14, cor = c.muted)
            Botao("Empresas parceiras", { e.ir(Tela.EMPRESAS) }, Modifier.fillMaxWidth())
        }
        Botao("Sair da conta", { e.sair() }, Modifier.fillMaxWidth())
    }
}

// ---------------------------------------------------------------- ENTRAR (US11)
@Composable
fun TelaEntrar(e: Estado) {
    val c = cores()
    var email by remember { mutableStateOf("") }
    var senha by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column {
            Eyebrow("Bem-vindo de volta")
            Spacer(Modifier.height(8.dp))
            T("Entrar", 30, FontWeight.Bold, ls = -0.9f)
        }
        Cartao {
            CampoTexto("E-mail", email, { email = it }, dica = "voce@email.com", teclado = KeyboardType.Email, comErro = e.contaErro != null)
            CampoTexto("Senha", senha, { senha = it }, senha = true, comErro = e.contaErro != null)
            Mensagens(e)
            Botao(if (e.contaOcupada) "Entrando…" else "Entrar", { e.entrar(email, senha) }, Modifier.fillMaxWidth(), primario = true, grande = true)
        }
        Link("Esqueci minha senha") { e.abrirRecuperacao() }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            T("Ainda não tem conta?", 15, cor = c.muted)
            Link("Criar conta") { e.limparMensagensDeConta(); e.ir(Tela.CADASTRO) }
        }
    }
}

// ---------------------------------------------------------------- E-MAIL: RECUPERAR SENHA (RQ9) E CONFIRMAR (RQ10)
/** Avisa, em toda tela que depende de e-mail, que hoje o envio é simulado (docs/SIMULADOS.md) ou está desligado. */
@Composable
private fun AvisoDeEmail(e: Estado) {
    LaunchedEffect(Unit) { e.carregarEstadoEmail() }
    val s = e.emailEstado ?: return
    if (!s.disponivel) Aviso("Indisponível no momento", listOf("O envio de e-mail ainda não está ativo neste servidor. Volte mais tarde."))
    else if (s.simulado) Aviso("E-mail simulado", listOf("Nenhum e-mail de verdade é enviado nesta versão.", "O código aparece só na caixa de saída do servidor de desenvolvimento."))
}

@Composable
fun TelaEsqueciSenha(e: Estado) {
    val c = cores()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Link("‹  Entrar") { e.limparMensagensDeConta(); e.ir(Tela.ENTRAR) }
        Column {
            Eyebrow("Recuperar acesso")
            Spacer(Modifier.height(8.dp))
            T("Esqueci minha senha", 30, FontWeight.Bold, ls = -0.9f)
            Spacer(Modifier.height(6.dp))
            T("Informe o e-mail da conta. Se ele existir, enviamos um código de 6 dígitos que vale por 15 minutos.", 15, cor = c.muted)
        }
        AvisoDeEmail(e)
        Cartao {
            CampoTexto("E-mail", e.recEmail, { e.recEmail = it }, dica = "voce@email.com", teclado = KeyboardType.Email, comErro = e.contaErro != null)
            Mensagens(e)
            Botao(if (e.contaOcupada) "Enviando…" else "Enviar código", { e.pedirCodigoDeSenha(e.recEmail) }, Modifier.fillMaxWidth(), primario = true, grande = true)
        }
    }
}

@Composable
fun TelaRedefinirSenha(e: Estado) {
    val c = cores()
    var codigo by remember { mutableStateOf("") }
    var nova by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Link("‹  Pedir outro código") { e.limparMensagensDeConta(); e.ir(Tela.ESQUECI_SENHA) }
        Column {
            Eyebrow("Recuperar acesso")
            Spacer(Modifier.height(8.dp))
            T("Nova senha", 30, FontWeight.Bold, ls = -0.9f)
            Spacer(Modifier.height(6.dp))
            T("Digite o código enviado para ${e.recEmail} e escolha a nova senha. Você será desconectado dos outros aparelhos.", 15, cor = c.muted)
        }
        AvisoDeEmail(e)
        Cartao {
            CampoTexto("Código de 6 dígitos", codigo, { codigo = it.filter { ch -> ch in '0'..'9' }.take(6) }, dica = "000000", teclado = KeyboardType.Number, comErro = e.contaErro != null)
            CampoTexto("Nova senha (mínimo 8 caracteres)", nova, { nova = it }, senha = true)
            Mensagens(e)
            Botao(if (e.contaOcupada) "Salvando…" else "Trocar a senha", { e.redefinirSenha(codigo, nova) }, Modifier.fillMaxWidth(), primario = true, grande = true)
        }
    }
}

@Composable
fun TelaVerificarEmail(e: Estado) {
    val c = cores()
    var codigo by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Link("‹  Conta") { e.limparMensagensDeConta(); e.ir(Tela.CONTA) }
        Column {
            Eyebrow("Confirmar e-mail")
            Spacer(Modifier.height(8.dp))
            T("Digite o código", 30, FontWeight.Bold, ls = -0.9f)
            Spacer(Modifier.height(6.dp))
            T("Enviamos 6 dígitos para ${e.usuario?.email.orEmpty()}. O código vale por 15 minutos.", 15, cor = c.muted)
        }
        AvisoDeEmail(e)
        Cartao {
            CampoTexto("Código de 6 dígitos", codigo, { codigo = it.filter { ch -> ch in '0'..'9' }.take(6) }, dica = "000000", teclado = KeyboardType.Number, comErro = e.contaErro != null)
            Mensagens(e)
            Botao(if (e.contaOcupada) "Confirmando…" else "Confirmar e-mail", { e.confirmarEmail(codigo) }, Modifier.fillMaxWidth(), primario = true, grande = true)
        }
        Link("Enviar um código novo") { e.reenviarVerificacao() }
    }
}

// ---------------------------------------------------------------- CADASTRO (US8)
@Composable
fun TelaCadastro(e: Estado) {
    val c = cores()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column {
            Eyebrow("Criar conta")
            Spacer(Modifier.height(8.dp))
            T("Crie sua conta.", 30, FontWeight.Bold, ls = -0.9f)
            Spacer(Modifier.height(6.dp))
            T("Para comprar e guardar seus bilhetes. Buscar rotas continua sem conta.", 15, cor = c.muted)
        }
        Cartao {
            CampoTexto("Nome completo", e.cadNome, { e.cadNome = it })
            CampoTexto("E-mail", e.cadEmail, { e.cadEmail = it }, dica = "voce@email.com", teclado = KeyboardType.Email)
            CampoTexto("Celular (opcional)", e.cadCelular, { e.cadCelular = it }, dica = "(41) 99999-0000", teclado = KeyboardType.Phone)
            CampoTexto("Senha (mínimo 8 caracteres)", e.cadSenha, { e.cadSenha = it }, senha = true)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Chave(e.cadAceita) { e.marcarAceite(it) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    T("Li e aceito:", 14, cor = c.muted)
                    Link("Termos de uso  ›") { e.abrirDocumento(Documento.TERMOS) }
                    Link("Política de privacidade  ›") { e.abrirDocumento(Documento.PRIVACIDADE) }
                }
            }
            Mensagens(e)
            Botao(
                if (e.contaOcupada) "Criando…" else "Criar conta",
                { e.cadastrar(CadastroPedido(e.cadNome, e.cadEmail, e.cadCelular.ifBlank { null }, e.cadSenha, e.cadAceita)) },
                Modifier.fillMaxWidth(), primario = true, grande = true,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            T("Já tem conta?", 15, cor = c.muted)
            Link("Entrar") { e.limparMensagensDeConta(); e.ir(Tela.ENTRAR) }
        }
    }
}

// ---------------------------------------------------------------- TERMOS E PRIVACIDADE (RQ11)
@Composable
fun TelaTermos(e: Estado) {
    val c = cores()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Link("‹  Voltar ao cadastro") { e.ir(Tela.CADASTRO) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Documento.entries.forEach { d -> Botao(d.titulo, { e.documento = d }, ativo = e.documento == d, pequeno = true) }
        }
        Column {
            Eyebrow(TextosLegais.VERSAO)
            Spacer(Modifier.height(8.dp))
            T(e.documento.titulo, 30, FontWeight.Bold, ls = -0.9f)
        }
        Aviso("Rascunho", listOf(TextosLegais.AVISO))
        TextosLegais.secoes(e.documento).forEach { s ->
            Cartao(raio = 24, pad = 20, borda = true) {
                T(s.titulo, 18, FontWeight.Bold, ls = -0.3f)
                s.paragrafos.forEach { T(it, 15, cor = c.muted, lh = 22) }
            }
        }
        Botao("Voltar ao cadastro", { e.ir(Tela.CADASTRO) }, Modifier.fillMaxWidth(), primario = true)
    }
}

// ---------------------------------------------------------------- EMPRESAS PARCEIRAS (US7)
@Composable
fun TelaEmpresas(e: Estado) {
    val c = cores()
    val u = e.usuario
    if (u == null || !u.ehGestor) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Cartao {
                T("Área do gestor", 22, FontWeight.Bold)
                T("Você não tem acesso a esta área.", 15, cor = c.muted)
                Botao("Voltar", { e.ir(Tela.CONTA) }, primario = true)
            }
        }
        return
    }
    LaunchedEffect(u.id) { e.carregarEmpresas() }
    // null = formulário fechado; -1 = nova empresa; outro valor = id da empresa em edição
    var editando by remember { mutableStateOf<Int?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Link("‹  Conta") { e.ir(Tela.CONTA) }
        Column {
            Eyebrow("Gestão")
            Spacer(Modifier.height(8.dp))
            T("Empresas parceiras", 30, FontWeight.Bold, ls = -0.9f)
            Spacer(Modifier.height(6.dp))
            T("Empresa pausada deixa de aparecer nas buscas.", 15, cor = c.muted)
        }
        if (editando == null) Botao("+ Nova empresa", { editando = -1 }, Modifier.fillMaxWidth(), primario = true)
        editando?.let { id ->
            val atual = e.empresas.firstOrNull { it.id == id }
            FormEmpresa(e, atual, aoFechar = { editando = null })
        }
        when (val carga = e.empresasCarga) {
            is Carga.Carregando -> T("Carregando…", 15, cor = c.muted)
            is Carga.Erro -> T("✕  ${carga.mensagem}", 14, FontWeight.SemiBold, c.erro)
            Carga.Pronto -> if (e.empresas.isEmpty()) T("Nenhuma empresa cadastrada.", 15, cor = c.muted)
        }
        e.empresas.toList().forEach { emp -> CartaoEmpresa(e, emp) { editando = emp.id } }
    }
}

@Composable
private fun CartaoEmpresa(e: Estado, emp: Empresa, onEditar: () -> Unit) {
    val c = cores()
    Cartao(raio = 24, pad = 20, borda = true) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            T(emp.nome, 20, FontWeight.Bold, ls = -0.4f, mod = Modifier.weight(1f))
            Etiqueta(if (emp.ativa) "Ativa" else "Pausada")
        }
        val detalhes = listOfNotNull(emp.sigla, emp.cnpj?.let { cnpjBr(it) }, "${emp.linhas} ${if (emp.linhas == 1) "linha" else "linhas"}")
        T(detalhes.joinToString(" · "), 14, cor = c.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Botao("Editar", onEditar, pequeno = true, primario = true)
            Botao(
                if (emp.ativa) "Pausar" else "Reativar",
                { e.salvarEmpresa(emp.id, EmpresaPedido(emp.nome, emp.sigla, emp.cnpj, !emp.ativa)) {} },
                pequeno = true,
            )
        }
    }
}

@Composable
private fun FormEmpresa(e: Estado, atual: Empresa?, aoFechar: () -> Unit) {
    val c = cores()
    var nome by remember(atual?.id) { mutableStateOf(atual?.nome.orEmpty()) }
    var sigla by remember(atual?.id) { mutableStateOf(atual?.sigla.orEmpty()) }
    var cnpj by remember(atual?.id) { mutableStateOf(cnpjBr(atual?.cnpj)) }
    var erro by remember(atual?.id) { mutableStateOf<String?>(null) }
    var ocupado by remember(atual?.id) { mutableStateOf(false) }
    Cartao {
        T(if (atual == null) "Nova empresa" else "Editar empresa", 20, FontWeight.Bold, ls = -0.4f)
        CampoTexto("Nome", nome, { nome = it })
        CampoTexto("Sigla (2 a 4 letras, opcional)", sigla, { sigla = it })
        CampoTexto("CNPJ (opcional)", cnpj, { cnpj = it }, dica = "00.000.000/0000-00", teclado = KeyboardType.Number, comErro = erro?.contains("CNPJ") == true)
        erro?.let { T("✕  $it", 14, FontWeight.SemiBold, c.erro) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Botao(if (ocupado) "Salvando…" else "Salvar", {
                if (!ocupado) {
                    ocupado = true; erro = null
                    e.salvarEmpresa(atual?.id, EmpresaPedido(nome, sigla.ifBlank { null }, cnpj.ifBlank { null }, atual?.ativa ?: true)) { msg ->
                        ocupado = false
                        if (msg == null) aoFechar() else erro = msg
                    }
                }
            }, primario = true, pequeno = true)
            Botao("Cancelar", aoFechar, pequeno = true)
        }
    }
}
