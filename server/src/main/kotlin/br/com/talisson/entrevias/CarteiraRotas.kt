package br.com.talisson.entrevias

import br.com.talisson.entrevias.domain.ErroApi
import br.com.talisson.entrevias.domain.FormaPagamentoPedido
import br.com.talisson.entrevias.domain.PassageiroPedido
import br.com.talisson.entrevias.domain.ValidacaoPerfil
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

private suspend fun io.ktor.server.application.ApplicationCall.naoEncontrado() = respond(HttpStatusCode.NotFound, ErroApi("Não encontrado."))
private suspend fun io.ktor.server.application.ApplicationCall.limite(msg: String) = respond(HttpStatusCode.Conflict, ErroApi(msg))

/** A5 e A6. Só para quem está logado, e só dos próprios dados. */
fun Route.rotasDeCarteira() {
    get("/api/formas-pagamento") { call.respond(Carteira.formas((call.exigeLogin() ?: return@get).id)) }
    post("/api/formas-pagamento") {
        val u = call.exigeLogin() ?: return@post
        val p = call.corpo<FormaPagamentoPedido>() ?: return@post call.erro(HttpStatusCode.BadRequest, "Dados inválidos.")
        ValidacaoPerfil.forma(p)?.let { return@post call.erro(HttpStatusCode.BadRequest, it) }
        when (val r = Carteira.adicionarForma(u.id, p)) {
            is ResultadoCarteira.Ok -> call.respond(HttpStatusCode.Created, r.valor)
            else -> call.limite("Você já tem ${ValidacaoPerfil.MAX_FORMAS} formas de pagamento. Remova uma para adicionar outra.")
        }
    }
    put("/api/formas-pagamento/{id}/padrao") {
        val u = call.exigeLogin() ?: return@put
        val id = call.parameters["id"]?.toIntOrNull() ?: return@put call.naoEncontrado()
        when (val r = Carteira.tornarPadrao(u.id, id)) { is ResultadoCarteira.Ok -> call.respond(r.valor); else -> call.naoEncontrado() }
    }
    delete("/api/formas-pagamento/{id}") {
        val u = call.exigeLogin() ?: return@delete
        val id = call.parameters["id"]?.toIntOrNull() ?: return@delete call.naoEncontrado()
        when (val r = Carteira.removerForma(u.id, id)) { is ResultadoCarteira.Ok -> call.respond(r.valor); else -> call.naoEncontrado() }
    }

    get("/api/passageiros") { call.respond(Carteira.passageiros((call.exigeLogin() ?: return@get).id)) }
    post("/api/passageiros") {
        val u = call.exigeLogin() ?: return@post
        val p = call.corpo<PassageiroPedido>() ?: return@post call.erro(HttpStatusCode.BadRequest, "Dados inválidos.")
        ValidacaoPerfil.passageiro(p)?.let { return@post call.erro(HttpStatusCode.BadRequest, it) }
        when (val r = Carteira.adicionarPassageiro(u.id, p)) {
            is ResultadoCarteira.Ok -> call.respond(HttpStatusCode.Created, r.valor)
            else -> call.limite("Você já tem ${ValidacaoPerfil.MAX_PASSAGEIROS} passageiros. Remova um para adicionar outro.")
        }
    }
    put("/api/passageiros/{id}") {
        val u = call.exigeLogin() ?: return@put
        val id = call.parameters["id"]?.toIntOrNull() ?: return@put call.naoEncontrado()
        val p = call.corpo<PassageiroPedido>() ?: return@put call.erro(HttpStatusCode.BadRequest, "Dados inválidos.")
        ValidacaoPerfil.passageiro(p)?.let { return@put call.erro(HttpStatusCode.BadRequest, it) }
        when (val r = Carteira.editarPassageiro(u.id, id, p)) { is ResultadoCarteira.Ok -> call.respond(r.valor); else -> call.naoEncontrado() }
    }
    delete("/api/passageiros/{id}") {
        val u = call.exigeLogin() ?: return@delete
        val id = call.parameters["id"]?.toIntOrNull() ?: return@delete call.naoEncontrado()
        when (val r = Carteira.removerPassageiro(u.id, id)) { is ResultadoCarteira.Ok -> call.respond(r.valor); else -> call.naoEncontrado() }
    }
}
