package br.com.talisson.entrevias

import br.com.talisson.entrevias.domain.CadastroPedido
import br.com.talisson.entrevias.domain.EmpresaPedido
import br.com.talisson.entrevias.domain.ErroApi
import br.com.talisson.entrevias.domain.LoginPedido
import br.com.talisson.entrevias.domain.PerfilPedido
import br.com.talisson.entrevias.domain.Usuario
import br.com.talisson.entrevias.domain.Validacao
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

private suspend fun ApplicationCall.erro(status: HttpStatusCode, mensagem: String) = respond(status, ErroApi(mensagem))

private fun ApplicationCall.token(): String? =
    request.headers[HttpHeaders.Authorization]?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()

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
        val p = call.corpo<CadastroPedido>() ?: return@post call.erro(HttpStatusCode.BadRequest, "Dados inválidos.")
        Validacao.cadastro(p)?.let { return@post call.erro(HttpStatusCode.BadRequest, it) }
        when (val r = Contas.cadastrar(p)) {
            is ResultadoCadastro.Ok -> call.respond(HttpStatusCode.Created, r.sessao)
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
}
