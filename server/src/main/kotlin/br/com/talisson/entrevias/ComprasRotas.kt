package br.com.talisson.entrevias

import br.com.talisson.entrevias.domain.AvisoApi
import br.com.talisson.entrevias.domain.CancelamentoPedido
import br.com.talisson.entrevias.domain.CupomPedido
import br.com.talisson.entrevias.domain.EscolhasPedido
import br.com.talisson.entrevias.domain.LinkDoComprovante
import br.com.talisson.entrevias.domain.NovoChamado
import br.com.talisson.entrevias.domain.RespostaChamado
import br.com.talisson.entrevias.domain.NovaAvaliacao
import br.com.talisson.entrevias.domain.NovoPedido
import br.com.talisson.entrevias.domain.OcorrenciaPedido
import br.com.talisson.entrevias.domain.PagamentoCartaoPedido
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/** Roda uma regra de compra e traduz [ErroDeCompra] no status HTTP certo, com texto genérico. */
private suspend inline fun ApplicationCall.regra(bloco: () -> Any) {
    try { respond(bloco()) } catch (e: ErroDeCompra) { erro(HttpStatusCode.fromValue(e.status), e.message) }
}

private suspend fun ApplicationCall.limitar(l: LimiteDeTaxa, chave: String): Boolean {
    val espera = l.tentar(chave) ?: return true
    response.header(HttpHeaders.RetryAfter, espera.toString())
    erro(HttpStatusCode.TooManyRequests, "Muitas tentativas. Tente de novo mais tarde.")
    return false
}

private fun ApplicationCall.id(nome: String = "id") = parameters[nome]?.toIntOrNull()

/** C1 a C7 e D1 a D3. Tudo exige login e só enxerga os próprios pedidos. */
fun Route.rotasDeCompra() {
    get("/api/pagamento/estado") { call.respond(mapOf("simulado" to Psp.simulado, "disponivel" to Psp.disponivel)) }

    post("/api/pedidos") {
        val u = call.exigeLogin() ?: return@post
        if (!call.limitar(Limites.pedidoPorUsuario, u.id.toString())) return@post
        val p = call.corpo<NovoPedido>() ?: return@post call.erro(HttpStatusCode.BadRequest, "Dados inválidos.")
        try { call.respond(HttpStatusCode.Created, Compras.criar(u.id, p)) } catch (e: ErroDeCompra) { call.erro(HttpStatusCode.fromValue(e.status), e.message) }
    }
    get("/api/pedidos") { val u = call.exigeLogin() ?: return@get; call.respond(Compras.pedidos(u.id, call.request.queryParameters["filtro"])) }
    get("/api/pedidos/{id}") {
        val u = call.exigeLogin() ?: return@get
        val ped = call.id()?.let { Compras.pedido(u.id, it) } ?: return@get call.erro(HttpStatusCode.NotFound, "Pedido não encontrado.")
        call.respond(ped)
    }

    put("/api/pedidos/{id}/cupom") {
        val u = call.exigeLogin() ?: return@put
        if (!call.limitar(Limites.cupomPorUsuario, u.id.toString())) return@put
        val id = call.id() ?: return@put call.erro(HttpStatusCode.NotFound, "Pedido não encontrado.")
        val p = call.corpo<CupomPedido>() ?: return@put call.erro(HttpStatusCode.BadRequest, "Dados inválidos.")
        call.regra { Compras.definirCupom(u.id, id, p.codigo) }
    }
    delete("/api/pedidos/{id}/cupom") {
        val u = call.exigeLogin() ?: return@delete
        val id = call.id() ?: return@delete call.erro(HttpStatusCode.NotFound, "Pedido não encontrado.")
        call.regra { Compras.definirCupom(u.id, id, null) }
    }

    get("/api/pedidos/{id}/poltronas/{viagemId}") {
        val u = call.exigeLogin() ?: return@get
        val id = call.id(); val v = call.id("viagemId")
        if (id == null || v == null) return@get call.erro(HttpStatusCode.NotFound, "Pedido não encontrado.")
        call.regra { Compras.mapa(u.id, id, v) }
    }
    put("/api/pedidos/{id}/poltronas") {
        val u = call.exigeLogin() ?: return@put
        val id = call.id() ?: return@put call.erro(HttpStatusCode.NotFound, "Pedido não encontrado.")
        val p = call.corpo<EscolhasPedido>() ?: return@put call.erro(HttpStatusCode.BadRequest, "Dados inválidos.")
        call.regra { Compras.escolher(u.id, id, p.escolhas) }
    }

    post("/api/pedidos/{id}/pagamento/pix") {
        val u = call.exigeLogin() ?: return@post
        val id = call.id() ?: return@post call.erro(HttpStatusCode.NotFound, "Pedido não encontrado.")
        try { call.respond(HttpStatusCode.Created, Compras.pagarComPix(u.id, id)) } catch (e: ErroDeCompra) { call.erro(HttpStatusCode.fromValue(e.status), e.message) }
    }
    post("/api/pedidos/{id}/pagamento/cartao") {
        val u = call.exigeLogin() ?: return@post
        val id = call.id() ?: return@post call.erro(HttpStatusCode.NotFound, "Pedido não encontrado.")
        val p = call.corpo<PagamentoCartaoPedido>() ?: return@post call.erro(HttpStatusCode.BadRequest, "Dados inválidos.")
        try { call.respond(HttpStatusCode.Created, Compras.pagarComCartao(u.id, id, p)) } catch (e: ErroDeCompra) { call.erro(HttpStatusCode.fromValue(e.status), e.message) }
    }
    get("/api/pagamentos/{id}") {
        val u = call.exigeLogin() ?: return@get
        val id = call.id() ?: return@get call.erro(HttpStatusCode.NotFound, "Pagamento não encontrado.")
        call.regra { Compras.pagamento(u.id, id) }
    }
    post("/api/pagamentos/{id}/simular") {
        val u = call.exigeLogin() ?: return@post
        val id = call.id() ?: return@post call.erro(HttpStatusCode.NotFound, "Pagamento não encontrado.")
        call.regra { Compras.simularPixPago(u.id, id) }
    }

    get("/api/pedidos/{id}/cancelamento") {
        val u = call.exigeLogin() ?: return@get
        val id = call.id() ?: return@get call.erro(HttpStatusCode.NotFound, "Pedido não encontrado.")
        call.regra { Compras.previaDeCancelamento(u.id, id) }
    }
    post("/api/pedidos/{id}/cancelar") {
        val u = call.exigeLogin() ?: return@post
        val id = call.id() ?: return@post call.erro(HttpStatusCode.NotFound, "Pedido não encontrado.")
        val p = call.corpo<CancelamentoPedido>() ?: CancelamentoPedido()
        call.regra { Compras.cancelar(u.id, id, p.motivo) }
    }

    // Comprovante: o conteúdo (tela e texto) e o link temporário do PDF. O PDF em si abre sem login, só com o token do link.
    get("/api/pedidos/{id}/comprovante") {
        val u = call.exigeLogin() ?: return@get
        val id = call.id() ?: return@get call.erro(HttpStatusCode.NotFound, "Pedido não encontrado.")
        call.regra { Comprovantes.montar(u.id, id) }
    }
    post("/api/pedidos/{id}/comprovante/link") {
        val u = call.exigeLogin() ?: return@post
        if (!call.limitar(Limites.comprovantePorUsuario, u.id.toString())) return@post
        val id = call.id() ?: return@post call.erro(HttpStatusCode.NotFound, "Pedido não encontrado.")
        call.regra { LinkDoComprovante("/comprovante/${Comprovantes.link(u.id, id)}.pdf", Comprovantes.VALIDADE_SEG) }
    }
    get("/comprovante/{arquivo}") {
        val token = call.parameters["arquivo"].orEmpty().removeSuffix(".pdf")
        val pdf = Comprovantes.pdf(token) ?: return@get call.erro(HttpStatusCode.NotFound, "Link inválido ou vencido.")
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.response.header(HttpHeaders.ContentDisposition, "inline; filename=\"comprovante.pdf\"")
        call.respondBytes(pdf, ContentType.Application.Pdf)
    }

    get("/api/notificacoes") { val u = call.exigeLogin() ?: return@get; call.respond(Notificacoes.lista(u.id)) }
    post("/api/notificacoes/lidas") {
        val u = call.exigeLogin() ?: return@post
        Notificacoes.marcarLidas(u.id); call.respond(AvisoApi("Marcadas como lidas."))
    }

    // D4: o gestor vê as viagens do dia e registra o que aconteceu com uma; quem comprou é avisado.
    get("/api/gestor/viagens") {
        call.exigeGestor() ?: return@get
        val data = call.request.queryParameters["data"]?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
            ?: return@get call.erro(HttpStatusCode.BadRequest, "Informe data=AAAA-MM-DD.")
        call.respond(Ocorrencias.painel(data))
    }
    post("/api/gestor/viagens/{id}/ocorrencia") {
        call.exigeGestor() ?: return@post
        val id = call.id() ?: return@post call.erro(HttpStatusCode.NotFound, "Viagem não encontrada.")
        val p = call.corpo<OcorrenciaPedido>() ?: return@post call.erro(HttpStatusCode.BadRequest, "Dados inválidos.")
        call.regra { Ocorrencias.registrar(id, p.tipo, p.minutos) }
    }

    // D5: a viagem em andamento ou as próximas, com a etapa de cada trecho.
    get("/api/viagens-ativas") { val u = call.exigeLogin() ?: return@get; call.respond(Ocorrencias.ativas(u.id)) }

    // F1: ajuda. A pessoa abre e acompanha os próprios chamados; o gestor lê todos e responde (tela de gestão no app).
    post("/api/chamados") {
        val u = call.exigeLogin() ?: return@post
        if (!call.limitar(Limites.chamadoPorUsuario, u.id.toString())) return@post
        val p = call.corpo<NovoChamado>() ?: return@post call.erro(HttpStatusCode.BadRequest, "Dados inválidos.")
        try { call.respond(HttpStatusCode.Created, Ajuda.abrir(u.id, p)) } catch (e: ErroDeCompra) { call.erro(HttpStatusCode.fromValue(e.status), e.message) }
    }
    get("/api/chamados") { val u = call.exigeLogin() ?: return@get; call.respond(Ajuda.meus(u.id)) }
    get("/api/gestor/chamados") { call.exigeGestor() ?: return@get; call.respond(Ajuda.todos()) }
    post("/api/gestor/chamados/{id}/resposta") {
        call.exigeGestor() ?: return@post
        val id = call.id() ?: return@post call.erro(HttpStatusCode.NotFound, "Chamado não encontrado.")
        val p = call.corpo<RespostaChamado>() ?: return@post call.erro(HttpStatusCode.BadRequest, "Dados inválidos.")
        call.regra { Ajuda.responder(id, p.texto) }
    }

    // F2: avaliação e reclamação, por trecho, depois de chegar.
    post("/api/pedidos/{id}/trechos/{trecho}/avaliacao") {
        val u = call.exigeLogin() ?: return@post
        val id = call.id(); val t = call.id("trecho")
        if (id == null || t == null) return@post call.erro(HttpStatusCode.NotFound, "Viagem não encontrada.")
        val p = call.corpo<NovaAvaliacao>() ?: return@post call.erro(HttpStatusCode.BadRequest, "Dados inválidos.")
        try { call.respond(HttpStatusCode.Created, Ajuda.avaliar(u.id, id, t, p)) } catch (e: ErroDeCompra) { call.erro(HttpStatusCode.fromValue(e.status), e.message) }
    }
    get("/api/pedidos/{id}/avaliacoes") {
        val u = call.exigeLogin() ?: return@get
        val id = call.id() ?: return@get call.erro(HttpStatusCode.NotFound, "Viagem não encontrada.")
        call.regra { Ajuda.minhas(u.id, id) }
    }
    get("/api/gestor/reclamacoes") { call.exigeGestor() ?: return@get; call.respond(Ajuda.todasAsReclamacoes()) }
}
