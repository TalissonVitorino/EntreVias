package br.com.talisson.entrevias

import br.com.talisson.entrevias.domain.AvisoApi
import br.com.talisson.entrevias.domain.CadastroPedido
import br.com.talisson.entrevias.domain.CodigoPedido
import br.com.talisson.entrevias.domain.EsqueciSenhaPedido
import br.com.talisson.entrevias.domain.RedefinirSenhaPedido
import br.com.talisson.entrevias.domain.EmpresaPedido
import br.com.talisson.entrevias.domain.ErroApi
import br.com.talisson.entrevias.domain.LoginPedido
import br.com.talisson.entrevias.domain.PerfilPedido
import br.com.talisson.entrevias.domain.Usuario
import br.com.talisson.entrevias.domain.Validacao
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.origin
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

private suspend fun ApplicationCall.erro(status: HttpStatusCode, mensagem: String) = respond(status, ErroApi(mensagem))

private fun ApplicationCall.token(): String? =
    request.headers[HttpHeaders.Authorization]?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()

/** Quem fez o pedido, para os limites de taxa. Ver [Limites] sobre o X-Forwarded-For. */
private fun ApplicationCall.chaveDoCliente(): String {
    val direto = request.origin.remoteHost
    if (!Limites.confiarNoProxy) return direto
    return request.headers["X-Forwarded-For"]?.split(',')?.lastOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: direto
}

/** Corpo JSON inválido ou de outro formato vira `null` (e o chamador responde 400), nunca 500. */
private suspend inline fun <reified T : Any> ApplicationCall.corpo(): T? = runCatching { receiveNullable<T>() }.getOrNull()

/** Exige login; sem sessão válida responde 401 e devolve `null`. */
private suspend fun ApplicationCall.exigeLogin(): Usuario? =
    Contas.usuarioDoToken(token()) ?: run { erro(HttpStatusCode.Unauthorized, "Entre na sua conta para continuar."); null }

/** Exige o papel de gestor: 401 sem login, 403 para quem não é gestor. */
private suspend fun ApplicationCall.exigeGestor(): Usuario? {
    val u = exigeLogin() ?: return null
    if (!u.ehGestor) { erro(HttpStatusCode.Forbidden, "Você não tem acesso a esta área."); return null }
    return u
}

fun Route.rotasDeConta() {
    // US8: cadastro. Erro de duplicidade é genérico para não revelar quais e-mails existem.
    post("/api/cadastro") {
        val espera = Limites.cadastro.tentar(call.chaveDoCliente()) ?: Limites.cadastroGlobal.tentar("*")
        if (espera != null) {
            call.response.header(HttpHeaders.RetryAfter, espera.toString())
            return@post call.erro(HttpStatusCode.TooManyRequests, "Muitas tentativas. Tente de novo mais tarde.")
        }
        val p = call.corpo<CadastroPedido>() ?: return@post call.erro(HttpStatusCode.BadRequest, "Dados inválidos.")
        Validacao.cadastro(p)?.let { return@post call.erro(HttpStatusCode.BadRequest, it) }
        when (val r = Contas.cadastrar(p)) {
            is ResultadoCadastro.Ok -> {
                // RQ10: já manda o código de confirmação. Falha de envio não derruba o cadastro: a pessoa pede de novo depois.
                if (Email.disponivel) runCatching { Recuperacao.enviarVerificacao(r.sessao.usuario) }
                call.respond(HttpStatusCode.Created, r.sessao)
            }
            ResultadoCadastro.Duplicado -> call.erro(HttpStatusCode.Conflict, "Não foi possível criar a conta com esses dados.")
        }
    }

    // US11: login. Mesma resposta para e-mail inexistente e senha errada; bloqueio de 15 min após 3 erros (RQ12).
    post("/api/login") {
        val p = call.corpo<LoginPedido>() ?: return@post call.erro(HttpStatusCode.BadRequest, "Dados inválidos.")
        if (p.email.length > 254 || p.senha.length > Validacao.SENHA_MAX) return@post call.erro(HttpStatusCode.Unauthorized, "E-mail ou senha incorretos.")
        when (val r = Contas.entrar(p.email, p.senha)) {
            is ResultadoEntrada.Ok -> call.respond(r.sessao)
            ResultadoEntrada.Invalido -> call.erro(HttpStatusCode.Unauthorized, "E-mail ou senha incorretos.")
            is ResultadoEntrada.Bloqueado -> {
                call.response.header(HttpHeaders.RetryAfter, (r.ateEpoch - Contas.agora()).coerceAtLeast(1).toString())
                call.erro(HttpStatusCode.TooManyRequests, "Muitas tentativas. Tente de novo em alguns minutos.")
            }
        }
    }

    post("/api/logout") {
        Contas.sair(call.token())
        call.respond(HttpStatusCode.NoContent)
    }

    // US9: a pessoa só vê e edita o PRÓPRIO perfil (o id vem da sessão, nunca do pedido).
    get("/api/perfil") {
        val u = call.exigeLogin() ?: return@get
        call.respond(u)
    }
    put("/api/perfil") {
        val u = call.exigeLogin() ?: return@put
        val p = call.corpo<PerfilPedido>() ?: return@put call.erro(HttpStatusCode.BadRequest, "Dados inválidos.")
        Validacao.perfil(p)?.let { return@put call.erro(HttpStatusCode.BadRequest, it) }
        call.respond(Contas.atualizarPerfil(u.id, p) ?: return@put call.erro(HttpStatusCode.Unauthorized, "Entre na sua conta para continuar."))
    }

    // US7: empresas parceiras, só para o gestor.
    get("/api/empresas") {
        call.exigeGestor() ?: return@get
        call.respond(Contas.empresas())
    }
    post("/api/empresas") {
        call.exigeGestor() ?: return@post
        val p = call.corpo<EmpresaPedido>() ?: return@post call.erro(HttpStatusCode.BadRequest, "Dados inválidos.")
        Validacao.empresa(p)?.let { return@post call.erro(HttpStatusCode.BadRequest, it) }
        when (val r = Contas.criarEmpresa(p)) {
            is ResultadoEmpresa.Ok -> call.respond(HttpStatusCode.Created, r.empresa)
            ResultadoEmpresa.Duplicada -> call.erro(HttpStatusCode.Conflict, "Já existe uma empresa com esse nome ou CNPJ.")
            ResultadoEmpresa.NaoEncontrada -> call.erro(HttpStatusCode.NotFound, "Empresa não encontrada.")
        }
    }
    put("/api/empresas/{id}") {
        call.exigeGestor() ?: return@put
        val id = call.parameters["id"]?.toIntOrNull() ?: return@put call.erro(HttpStatusCode.NotFound, "Empresa não encontrada.")
        val p = call.corpo<EmpresaPedido>() ?: return@put call.erro(HttpStatusCode.BadRequest, "Dados inválidos.")
        Validacao.empresa(p)?.let { return@put call.erro(HttpStatusCode.BadRequest, it) }
        when (val r = Contas.atualizarEmpresa(id, p)) {
            is ResultadoEmpresa.Ok -> call.respond(r.empresa)
            ResultadoEmpresa.Duplicada -> call.erro(HttpStatusCode.Conflict, "Já existe uma empresa com esse nome ou CNPJ.")
            ResultadoEmpresa.NaoEncontrada -> call.erro(HttpStatusCode.NotFound, "Empresa não encontrada.")
        }
    }

    // ---------- RQ9 / RQ10: e-mail (hoje SIMULADO, ver docs/SIMULADOS.md) ----------

    get("/api/email/estado") { call.respond(Email.estado()) }

    /** Limite por cliente e global; devolve `false` (já respondendo 429) se estourou. */
    suspend fun ApplicationCall.dentroDoLimiteDeRecuperacao(): Boolean {
        val espera = Limites.recuperacao.tentar(chaveDoCliente()) ?: Limites.recuperacaoGlobal.tentar("*")
        if (espera == null) return true
        response.header(HttpHeaders.RetryAfter, espera.toString())
        erro(HttpStatusCode.TooManyRequests, "Muitas tentativas. Tente de novo mais tarde.")
        return false
    }

    // Sempre 202 com o mesmo texto, exista a conta ou não (não revela quais e-mails têm conta).
    post("/api/senha/esqueci") {
        if (!Email.disponivel) return@post call.erro(HttpStatusCode.ServiceUnavailable, "A recuperação de senha está indisponível no momento.")
        if (!call.dentroDoLimiteDeRecuperacao()) return@post
        val p = call.corpo<EsqueciSenhaPedido>() ?: return@post call.erro(HttpStatusCode.BadRequest, "Dados inválidos.")
        Validacao.email(p.email)?.let { return@post call.erro(HttpStatusCode.BadRequest, it) }
        Recuperacao.pedirCodigoDeSenha(p.email)
        call.respond(HttpStatusCode.Accepted, AvisoApi("Se esse e-mail tiver conta, enviamos um código. Ele vale por 15 minutos."))
    }

    post("/api/senha/redefinir") {
        if (!Email.disponivel) return@post call.erro(HttpStatusCode.ServiceUnavailable, "A recuperação de senha está indisponível no momento.")
        if (!call.dentroDoLimiteDeRecuperacao()) return@post
        val p = call.corpo<RedefinirSenhaPedido>() ?: return@post call.erro(HttpStatusCode.BadRequest, "Dados inválidos.")
        (Validacao.email(p.email) ?: Validacao.codigo(p.codigo) ?: Validacao.senha(p.novaSenha))?.let { return@post call.erro(HttpStatusCode.BadRequest, it) }
        if (!Recuperacao.redefinirSenha(p.email, p.codigo, p.novaSenha)) return@post call.erro(HttpStatusCode.BadRequest, "Código inválido ou vencido. Peça um novo.")
        call.respond(AvisoApi("Senha alterada. Entre com a nova senha."))
    }

    post("/api/verificacao/enviar") {
        val u = call.exigeLogin() ?: return@post
        if (!Email.disponivel) return@post call.erro(HttpStatusCode.ServiceUnavailable, "A confirmação de e-mail está indisponível no momento.")
        if (!Recuperacao.enviarVerificacao(u)) {
            call.response.header(HttpHeaders.RetryAfter, "3600")
            return@post call.erro(HttpStatusCode.TooManyRequests, "Muitas tentativas. Tente de novo mais tarde.")
        }
        call.respond(HttpStatusCode.Accepted, AvisoApi("Enviamos um código para o seu e-mail."))
    }

    post("/api/verificacao/confirmar") {
        val u = call.exigeLogin() ?: return@post
        val p = call.corpo<CodigoPedido>() ?: return@post call.erro(HttpStatusCode.BadRequest, "Dados inválidos.")
        Validacao.codigo(p.codigo)?.let { return@post call.erro(HttpStatusCode.BadRequest, it) }
        if (!Recuperacao.confirmarEmail(u.id, p.codigo)) return@post call.erro(HttpStatusCode.BadRequest, "Código inválido ou vencido. Peça um novo.")
        call.respond(Contas.usuarioDoToken(call.token()) ?: u)
    }

    // Só em desenvolvimento e só com CAIXA_DE_SAIDA_DEV=true: mostra os e-mails simulados (com códigos de qualquer pessoa).
    get("/api/dev/caixa-de-saida") {
        val envio = Email.envio
        if (!Email.caixaDeSaidaAberta || Email.emProducao || envio !is EnvioSimulado) return@get call.respond(HttpStatusCode.NotFound)
        call.respond(envio.mensagens().map { mapOf("para" to it.para, "assunto" to it.assunto, "texto" to it.texto) })
    }
}
