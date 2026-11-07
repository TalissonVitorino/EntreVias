package br.com.talisson.entrevias

import br.com.talisson.entrevias.domain.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** C1 a C7 e D3: a compra inteira, com pagamento SIMULADO. Um banco só para a execução (BancoDeTeste). */
class ComprasRotasTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val relogioReal = Contas.agora
    private val iteracoesReais = Contas.iteracoes
    private val limitesReais = listOf(Limites.cadastro, Limites.cadastroGlobal, Limites.pedidoPorUsuario, Limites.cupomPorUsuario)
    private val producaoReal = Email.emProducao
    private val hoje = LocalDate.of(2026, 11, 10).atTime(LocalTime.NOON).toEpochSecond(ZoneOffset.ofHours(-3))

    @BeforeTest fun preparar() {
        BancoDeTeste.pronto; Contas.iteracoes = 1_000; Contas.agora = { hoje }; Email.emProducao = false
        simples = candidatas[proxima.getAndIncrement() % candidatas.size]
        val folga = { LimiteDeTaxa(1_000_000, 3600) }
        Limites.cadastro = folga(); Limites.cadastroGlobal = folga(); Limites.pedidoPorUsuario = folga(); Limites.cupomPorUsuario = folga()
    }
    @AfterTest fun restaurar() {
        Contas.agora = relogioReal; Contas.iteracoes = iteracoesReais; Email.emProducao = producaoReal
        Limites.cadastro = limitesReais[0]; Limites.cadastroGlobal = limitesReais[1]; Limites.pedidoPorUsuario = limitesReais[2]; Limites.cupomPorUsuario = limitesReais[3]
    }

    private companion object { val n = AtomicInteger(); val proxima = AtomicInteger() }

    private class Pessoa(val token: String, val id: Int, val adulto: Int, val idoso: Int, val crianca: Int, val colo: Int, val pcd: Int)

    private fun pessoa(): Pessoa {
        val s = (Contas.cadastrar(CadastroPedido("Maria Souza", "compra${n.incrementAndGet()}@teste.com", null, "senha-segura-1", true)) as ResultadoCadastro.Ok).sessao
        fun add(nome: String, idade: Int, acess: String? = null) = (Carteira.adicionarPassageiro(s.usuario.id, PassageiroPedido(nome, "Dependente", idade, acessibilidade = acess)) as ResultadoCarteira.Ok).valor.id
        return Pessoa(s.token, s.usuario.id, add("Ana Adulta", 30), add("Ivo Idoso", 70), add("Davi Criança", 8), add("Bia Bebê", 1), add("Paula PcD", 40, "Cadeira de rodas"))
    }

    /** Um trecho no futuro e um par encadeado (duas viagens ligadas por uma conexão válida), tirados da base de teste. */
    // BancoDeTeste.pronto PRIMEIRO: ele fixa o caminho do banco de teste antes de o Banco ler o caminho (uma vez só).
    private val viagens = run { BancoDeTeste.pronto; Banco.viagens(LocalDate.of(2026, 11, 20), 2) }
    // Cada teste usa a sua própria viagem: o banco é um só na execução e as poltronas confirmadas de um teste sobrariam para o outro.
    private lateinit var simples: Viagem
    private val candidatas by lazy { viagens.filter { it.precoCentavos > 0 && it.id != par.first.id && it.id != par.second.id } }
    private val par = viagens.firstNotNullOf { a -> viagens.firstOrNull { b -> b.origem == a.destino && b.destino != a.origem && b.saidaMin - a.chegadaMin in 30..480 }?.let { a to it } }

    /** i-ésima poltrona livre (nem simulada como ocupada, nem PcD) da viagem; i começa em 0. */
    private fun livre(i: Int, v: Int = simples.id) = (1..40).filter { it !in Compras.ocupadasSimuladas(v) && it !in listOf(1, 5) }[i]

    private fun comApp(bloco: suspend ApplicationTestBuilder.() -> Unit) = testApplication { application { module() }; bloco() }

    private suspend fun HttpClient.req(m: HttpMethod, url: String, token: String?, corpo: String? = null) = request(url) {
        method = m
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        if (corpo != null) { contentType(ContentType.Application.Json); setBody(corpo) }
    }
    private suspend fun HttpClient.criar(p: Pessoa, ids: List<Int>, pax: List<Int>, cupom: String? = null) =
        req(HttpMethod.Post, "/api/pedidos", p.token, json.encodeToString(NovoPedido(ids, pax, cupom)))
    private suspend fun HttpClient.pedido(p: Pessoa, id: Int) = json.decodeFromString<Pedido>(req(HttpMethod.Get, "/api/pedidos/$id", p.token).bodyAsText())
    private suspend fun HttpClient.novoPedido(p: Pessoa, ids: List<Int> = listOf(simples.id), pax: List<Int> = listOf(p.adulto), cupom: String? = null): Pedido =
        criar(p, ids, pax, cupom).also { assertEquals(HttpStatusCode.Created, it.status, it.bodyAsText()) }.let { json.decodeFromString(it.bodyAsText()) }
    private suspend fun HttpClient.poltronas(p: Pessoa, ped: Pedido, vararg porTrecho: List<Int>) =
        req(HttpMethod.Put, "/api/pedidos/${ped.id}/poltronas", p.token, json.encodeToString(EscolhasPedido(ped.trechos.mapIndexed { i, t -> EscolhaDePoltronas(t.viagemId, porTrecho[i]) })))
    private suspend fun HttpClient.cartao(p: Pessoa, ped: Pedido, ult4: String = "4242", parcelas: Int = 1) =
        req(HttpMethod.Post, "/api/pedidos/${ped.id}/pagamento/cartao", p.token, json.encodeToString(PagamentoCartaoPedido(ult4, "Visa", parcelas)))
    private suspend fun HttpClient.pix(p: Pessoa, ped: Pedido) = req(HttpMethod.Post, "/api/pedidos/${ped.id}/pagamento/pix", p.token)

    /** Pedido de um adulto, 1 trecho, poltrona escolhida, pronto para pagar. */
    private suspend fun HttpClient.pronto(p: Pessoa, poltrona: Int = livre(0)): Pedido {
        val ped = novoPedido(p); assertEquals(HttpStatusCode.OK, poltronas(p, ped, listOf(poltrona)).status); return pedido(p, ped.id)
    }
    private fun erroDe(r: String) = json.decodeFromString<ErroApi>(r).mensagem

    // ---------- acesso ----------

    @Test fun semLoginTudoDa401() = comApp {
        for ((m, u) in listOf(HttpMethod.Post to "/api/pedidos", HttpMethod.Get to "/api/pedidos", HttpMethod.Get to "/api/pedidos/1", HttpMethod.Put to "/api/pedidos/1/cupom",
            HttpMethod.Put to "/api/pedidos/1/poltronas", HttpMethod.Get to "/api/pedidos/1/poltronas/1", HttpMethod.Post to "/api/pedidos/1/pagamento/pix",
            HttpMethod.Post to "/api/pedidos/1/pagamento/cartao", HttpMethod.Get to "/api/pagamentos/1", HttpMethod.Post to "/api/pagamentos/1/simular",
            HttpMethod.Get to "/api/pedidos/1/cancelamento", HttpMethod.Post to "/api/pedidos/1/cancelar", HttpMethod.Get to "/api/notificacoes"))
            assertEquals(HttpStatusCode.Unauthorized, client.req(m, u, null, "{}").status, "$m $u")
    }

    @Test fun outraPessoaNaoVeNemMexeNoPedidoDeQuemNaoEhDela() = comApp {
        val a = pessoa(); val b = pessoa(); val ped = client.pronto(a)
        val rotas = listOf(
            Triple(HttpMethod.Get, "/api/pedidos/${ped.id}", null), Triple(HttpMethod.Put, "/api/pedidos/${ped.id}/cupom", """{"codigo":"VIAJA10"}"""),
            Triple(HttpMethod.Delete, "/api/pedidos/${ped.id}/cupom", null), Triple(HttpMethod.Get, "/api/pedidos/${ped.id}/poltronas/${simples.id}", null),
            Triple(HttpMethod.Put, "/api/pedidos/${ped.id}/poltronas", json.encodeToString(EscolhasPedido(listOf(EscolhaDePoltronas(simples.id, listOf(livre(3))))))),
            Triple(HttpMethod.Post, "/api/pedidos/${ped.id}/pagamento/pix", null), Triple(HttpMethod.Post, "/api/pedidos/${ped.id}/pagamento/cartao", json.encodeToString(PagamentoCartaoPedido("4242", "Visa"))),
            Triple(HttpMethod.Get, "/api/pedidos/${ped.id}/cancelamento", null), Triple(HttpMethod.Post, "/api/pedidos/${ped.id}/cancelar", "{}"),
        )
        for ((m, u, corpo) in rotas) assertEquals(HttpStatusCode.NotFound, client.req(m, u, b.token, corpo).status, "$m $u")
        assertEquals(StatusPedido.CARRINHO, client.pedido(a, ped.id).status); assertEquals(listOf(livre(0)), client.pedido(a, ped.id).trechos.single().poltronas, "nada mudou para a A")
        assertTrue(json.decodeFromString(ListSerializer(Pedido.serializer()), client.req(HttpMethod.Get, "/api/pedidos", b.token).bodyAsText()).isEmpty())
    }

    // ---------- C1: carrinho ----------

    @Test fun oPrecoVemDoBancoENuncaDoApp() = comApp {
        val p = pessoa(); val ped = client.novoPedido(p, listOf(simples.id), listOf(p.adulto, p.crianca))
        assertEquals(simples.precoCentavos, ped.trechos.single().precoUnitCentavos)
        assertEquals(Cotador.cotar(listOf(simples.precoCentavos), listOf("adulto", "crianca"), null), ped.totais)
        assertEquals("EV-%04d".format(4800 + ped.id), ped.codigo)
    }

    @Test fun conexaoEncadeadaEhAceitaENaoEncadeadaNao() = comApp {
        val p = pessoa()
        val ok = client.novoPedido(p, listOf(par.first.id, par.second.id)); assertEquals(2, ok.trechos.size)
        assertEquals(par.first.precoCentavos + par.second.precoCentavos, ok.totais.subtotalCentavos)
        val outra = viagens.first { it.origem != simples.destino && it.id != simples.id }
        assertEquals(HttpStatusCode.Conflict, client.criar(p, listOf(simples.id, outra.id), listOf(p.adulto)).status)
        assertEquals(HttpStatusCode.NotFound, client.criar(p, listOf(99_999_999), listOf(p.adulto)).status)
        assertEquals(HttpStatusCode.BadRequest, client.criar(p, listOf(simples.id, simples.id), listOf(p.adulto)).status, "trecho repetido")
    }

    @Test fun viagemQueJaSaiuNaoSeCompra() = comApp {
        val p = pessoa(); val inicio = LocalDate.of(2026, 11, 20).atStartOfDay().toEpochSecond(ZoneOffset.ofHours(-3)); Contas.agora = { inicio + simples.saidaMin * 60L + 60 }
        val r = client.criar(p, listOf(simples.id), listOf(p.adulto))
        assertEquals(HttpStatusCode.Conflict, r.status); assertTrue("já saiu" in erroDe(r.bodyAsText()))
    }

    @Test fun regrasDePassageiros() = comApp {
        val p = pessoa(); val o = pessoa()
        assertEquals(HttpStatusCode.NotFound, client.criar(p, listOf(simples.id), listOf(o.adulto)).status, "passageiro de outra pessoa")
        assertEquals(HttpStatusCode.BadRequest, client.criar(p, listOf(simples.id), listOf(p.crianca)).status, "criança sozinha")
        assertEquals(HttpStatusCode.BadRequest, client.criar(p, listOf(simples.id), listOf(p.crianca, p.colo)).status, "criança e bebê sem adulto")
        assertEquals(HttpStatusCode.BadRequest, client.criar(p, listOf(simples.id), listOf(p.colo)).status, "só bebê de colo não ocupa poltrona")
        assertEquals(HttpStatusCode.BadRequest, client.criar(p, listOf(simples.id), emptyList()).status)
        assertEquals(HttpStatusCode.BadRequest, client.criar(p, listOf(simples.id), listOf(p.adulto, p.adulto)).status, "repetido")
        assertEquals(HttpStatusCode.Created, client.criar(p, listOf(simples.id), listOf(p.adulto, p.crianca, p.colo)).status)
    }

    @Test fun cupomAplicaRemoveEInvalidoNaoAplica() = comApp {
        val p = pessoa(); val ped = client.novoPedido(p)
        val com = json.decodeFromString<Pedido>(client.req(HttpMethod.Put, "/api/pedidos/${ped.id}/cupom", p.token, """{"codigo":"viaja10"}""").bodyAsText())
        assertEquals("VIAJA10", com.cupom); assertEquals(simples.precoCentavos / 10, com.totais.cupomCentavos); assertEquals(simples.precoCentavos - simples.precoCentavos / 10, com.totais.totalCentavos)
        val sem = json.decodeFromString<Pedido>(client.req(HttpMethod.Delete, "/api/pedidos/${ped.id}/cupom", p.token).bodyAsText())
        assertEquals(null, sem.cupom); assertEquals(simples.precoCentavos, sem.totais.totalCentavos)
        assertEquals(HttpStatusCode.NotFound, client.req(HttpMethod.Put, "/api/pedidos/${ped.id}/cupom", p.token, """{"codigo":"NAOEXISTE"}""").status)
        assertEquals(HttpStatusCode.NotFound, client.criar(p, listOf(simples.id), listOf(p.adulto), "NAOEXISTE").status)
    }

    @Test fun adivinharCupomTemLimite() = comApp {
        val p = pessoa(); val ped = client.novoPedido(p); Limites.cupomPorUsuario = LimiteDeTaxa(3, 600)
        repeat(3) { assertEquals(HttpStatusCode.NotFound, client.req(HttpMethod.Put, "/api/pedidos/${ped.id}/cupom", p.token, """{"codigo":"CHUTE$it"}""").status) }
        val r = client.req(HttpMethod.Put, "/api/pedidos/${ped.id}/cupom", p.token, """{"codigo":"VIAJA10"}""")
        assertEquals(HttpStatusCode.TooManyRequests, r.status); assertNotNull(r.headers[HttpHeaders.RetryAfter])
    }

    // ---------- C2: poltronas ----------

    @Test fun mapaTrazPcdOcupadasEMinhas() = comApp {
        val p = pessoa(); val ped = client.pronto(p, livre(0))
        val m = json.decodeFromString<MapaDePoltronas>(client.req(HttpMethod.Get, "/api/pedidos/${ped.id}/poltronas/${simples.id}", p.token).bodyAsText())
        assertEquals(40, m.total); assertEquals(listOf(1, 5), m.pcd); assertEquals(listOf(livre(0)), m.minhas)
        assertEquals(Compras.ocupadasSimuladas(simples.id).toList().sorted(), m.ocupadas.sorted(), "as minhas não aparecem como ocupadas para mim")
    }

    @Test fun escolherReservaPor15MinutoEPedeAQuantidadeCerta() = comApp {
        val p = pessoa(); val ped = client.novoPedido(p, pax = listOf(p.adulto, p.crianca))
        val livres = (1..40).filter { it !in Compras.ocupadasSimuladas(simples.id) && it !in listOf(1, 5) }
        assertEquals(HttpStatusCode.BadRequest, client.poltronas(p, ped, listOf(livres[0])).status, "faltou uma")
        assertEquals(HttpStatusCode.BadRequest, client.poltronas(p, ped, listOf(livres[0], livres[0])).status, "repetida")
        assertEquals(HttpStatusCode.BadRequest, client.poltronas(p, ped, listOf(0, 41)).status, "fora do mapa")
        assertEquals(HttpStatusCode.OK, client.poltronas(p, ped, listOf(livres[0], livres[1])).status)
        val dep = client.pedido(p, ped.id)
        assertEquals(hoje + 900, dep.reservaExpiraEm); assertEquals(listOf(livres[0], livres[1]), dep.trechos.single().poltronas); assertTrue(dep.poltronasCompletas)
    }

    @Test fun poltronaOcupadaPorOutraPessoaDa409() = comApp {
        val p = pessoa(); val ped = client.novoPedido(p); val ocupada = Compras.ocupadasSimuladas(simples.id).first()
        assertEquals(HttpStatusCode.Conflict, client.poltronas(p, ped, listOf(ocupada)).status)
    }

    @Test fun duasPessoasDisputandoAMesmaPoltronaSoUmaLeva() = comApp {
        val a = pessoa(); val b = pessoa(); val pa = client.novoPedido(a); val pb = client.novoPedido(b)
        val livre = (1..40).first { it !in Compras.ocupadasSimuladas(simples.id) && it !in listOf(1, 5) }
        assertEquals(HttpStatusCode.OK, client.poltronas(a, pa, listOf(livre)).status)
        val r = client.poltronas(b, pb, listOf(livre))
        assertEquals(HttpStatusCode.Conflict, r.status); assertTrue("ocupada" in erroDe(r.bodyAsText()))
        assertEquals(listOf(livre), client.pedido(a, pa.id).trechos.single().poltronas, "a reserva da A não foi tocada")
        assertTrue(client.pedido(b, pb.id).trechos.single().poltronas.isEmpty())
        val m = json.decodeFromString<MapaDePoltronas>(client.req(HttpMethod.Get, "/api/pedidos/${pb.id}/poltronas/${simples.id}", b.token).bodyAsText())
        assertTrue(livre in m.ocupadas, "para a B, a poltrona da A aparece ocupada")
    }

    @Test fun reservaVencidaDevolveAPoltronaParaOutraPessoa() = comApp {
        val a = pessoa(); val b = pessoa(); val pa = client.pronto(a, livre(2)); val pb = client.novoPedido(b)
        Contas.agora = { hoje + 901 }
        assertEquals(HttpStatusCode.OK, client.poltronas(b, pb, listOf(livre(2))).status, "venceu, outra pessoa pode")
        val aDepois = client.pedido(a, pa.id)
        assertEquals(null, aDepois.reservaExpiraEm); assertTrue(aDepois.trechos.single().poltronas.isEmpty())
        assertEquals(HttpStatusCode.Conflict, client.cartao(a, aDepois).status, "e a A não paga sem poltrona")
    }

    @Test fun poltronaPcdSoParaQuemTemAcessibilidade() = comApp {
        val p = pessoa()
        val sem = client.novoPedido(p)
        assertEquals(HttpStatusCode.Conflict, client.poltronas(p, sem, listOf(1)).status)
        val com = client.novoPedido(p, pax = listOf(p.pcd))
        assertEquals(HttpStatusCode.OK, client.poltronas(p, com, listOf(1)).status)
    }

    @Test fun trocarAEscolhaLiberaAAnterior() = comApp {
        val p = pessoa(); val ped = client.pronto(p, livre(0))
        assertEquals(HttpStatusCode.OK, client.poltronas(p, ped, listOf(livre(1))).status)
        assertEquals(listOf(livre(1)), client.pedido(p, ped.id).trechos.single().poltronas)
        val o = pessoa(); val po = client.novoPedido(o)
        assertEquals(HttpStatusCode.OK, client.poltronas(o, po, listOf(livre(0))).status, "a primeira voltou")
    }

    // ---------- C3, C5, C6: cartão ----------

    @Test fun pagarSemPoltronasEscolhidasDa409() = comApp {
        val p = pessoa(); val ped = client.novoPedido(p)
        assertEquals(HttpStatusCode.Conflict, client.cartao(p, ped).status); assertEquals(HttpStatusCode.Conflict, client.pix(p, ped).status)
    }

    @Test fun cartaoAprovadoPagaEmiteBilhetesEAvisa() = comApp {
        val p = pessoa(); val ped = client.novoPedido(p, pax = listOf(p.adulto, p.idoso, p.colo))
        val livres = (1..40).filter { it !in Compras.ocupadasSimuladas(simples.id) && it !in listOf(1, 5) }
        client.poltronas(p, ped, listOf(livres[0], livres[1]))
        val r = client.cartao(p, ped, parcelas = 1)
        assertEquals(HttpStatusCode.Created, r.status)
        assertEquals(StatusPagamento.APROVADO, json.decodeFromString<Pagamento>(r.bodyAsText()).status)
        val pago = client.pedido(p, ped.id)
        assertEquals(StatusPedido.PAGO, pago.status); assertEquals("cartao", pago.metodo); assertNotNull(pago.pagoEm); assertEquals(null, pago.reservaExpiraEm)
        assertEquals(3, pago.bilhetes.size, "2 com poltrona + 1 de colo, em 1 trecho")
        assertEquals(setOf(livres[0], livres[1]), pago.bilhetes.mapNotNull { it.poltrona }.toSet())
        assertTrue(pago.bilhetes.any { it.poltrona == null && "colo" in it.passageiro })
        assertTrue(pago.bilhetes.map { it.codigo }.toSet().size == 3, "códigos diferentes")
        val avisos = json.decodeFromString(ListSerializer(Notificacao.serializer()), client.req(HttpMethod.Get, "/api/notificacoes", p.token).bodyAsText())
        assertTrue(avisos.any { it.tipo == TipoNotificacao.COMPRA && pago.codigo in it.titulo })
        assertEquals(HttpStatusCode.Conflict, client.cartao(p, pago).status, "pedido pago não paga de novo")
    }

    @Test fun doisTrechosEmitemBilhetesPorTrechoEPassageiro() = comApp {
        val p = pessoa(); val ped = client.novoPedido(p, listOf(par.first.id, par.second.id), listOf(p.adulto, p.crianca))
        fun livres(v: Int) = (1..40).filter { it !in Compras.ocupadasSimuladas(v) && it !in listOf(1, 5) }.take(2)
        assertEquals(HttpStatusCode.OK, client.poltronas(p, ped, livres(par.first.id), livres(par.second.id)).status)
        client.cartao(p, ped)
        val pago = client.pedido(p, ped.id)
        assertEquals(4, pago.bilhetes.size); assertEquals(setOf(1, 2), pago.bilhetes.map { it.trecho }.toSet())
    }

    @Test fun cartaoRecusadoMantemAsPoltronasEOPedido() = comApp {
        val p = pessoa(); val ped = client.pronto(p)
        val r = client.cartao(p, ped, "0002")
        assertEquals(StatusPagamento.RECUSADO, json.decodeFromString<Pagamento>(r.bodyAsText()).status)
        val depois = client.pedido(p, ped.id)
        assertEquals(StatusPedido.CARRINHO, depois.status); assertTrue(depois.bilhetes.isEmpty()); assertEquals(listOf(livre(0)), depois.trechos.single().poltronas)
        assertEquals(StatusPagamento.APROVADO, json.decodeFromString<Pagamento>(client.cartao(p, depois, "4242").bodyAsText()).status, "tenta de novo e passa")
    }

    @Test fun cincoTentativasDeCartaoEstouram() = comApp {
        val p = pessoa(); val ped = client.pronto(p)
        repeat(5) { assertEquals(HttpStatusCode.Created, client.cartao(p, ped, "0002").status) }
        assertEquals(HttpStatusCode.TooManyRequests, client.cartao(p, ped, "4242").status)
        assertEquals(HttpStatusCode.Created, client.pix(p, ped).status, "o Pix continua aberto")
    }

    @Test fun cartaoEParcelasInvalidosDao400() = comApp {
        val p = pessoa(); val ped = client.pronto(p)
        assertEquals(HttpStatusCode.BadRequest, client.cartao(p, ped, "42a2").status)
        assertEquals(HttpStatusCode.BadRequest, client.cartao(p, ped, "4242", 0).status)
        assertEquals(HttpStatusCode.BadRequest, client.cartao(p, ped, "4242", 7).status)
        assertEquals(HttpStatusCode.BadRequest, client.req(HttpMethod.Post, "/api/pedidos/${ped.id}/pagamento/cartao", p.token, json.encodeToString(PagamentoCartaoPedido("4242", "Inventada"))).status)
    }

    // ---------- C4: Pix ----------

    @Test fun pixPendenteAprovadoPeloBotaoDeDemonstracao() = comApp {
        val p = pessoa(); val ped = client.pronto(p)
        val pg = json.decodeFromString<Pagamento>(client.pix(p, ped).bodyAsText())
        assertEquals(StatusPagamento.PENDENTE, pg.status); assertEquals(hoje + 600, pg.expiraEm); assertTrue(pg.codigoPix!!.startsWith("EVSIM"), "não é um Pix de verdade")
        assertEquals(StatusPedido.CARRINHO, client.pedido(p, ped.id).status)
        val ok = json.decodeFromString<Pagamento>(client.req(HttpMethod.Post, "/api/pagamentos/${pg.id}/simular", p.token).bodyAsText())
        assertEquals(StatusPagamento.APROVADO, ok.status)
        val pago = client.pedido(p, ped.id); assertEquals(StatusPedido.PAGO, pago.status); assertEquals("pix", pago.metodo); assertEquals(1, pago.bilhetes.size)
        assertEquals(HttpStatusCode.Conflict, client.req(HttpMethod.Post, "/api/pagamentos/${pg.id}/simular", p.token).status, "não aprova duas vezes")
    }

    @Test fun pixVencidoExpiraEDevolveAsPoltronas() = comApp {
        val p = pessoa(); val ped = client.pronto(p, livre(3))
        val pg = json.decodeFromString<Pagamento>(client.pix(p, ped).bodyAsText())
        Contas.agora = { hoje + 601 }
        val dep = json.decodeFromString<Pagamento>(client.req(HttpMethod.Get, "/api/pagamentos/${pg.id}", p.token).bodyAsText())
        assertEquals(StatusPagamento.EXPIRADO, dep.status)
        assertTrue(client.pedido(p, ped.id).trechos.single().poltronas.isEmpty())
        assertEquals(HttpStatusCode.Conflict, client.req(HttpMethod.Post, "/api/pagamentos/${pg.id}/simular", p.token).status)
        val o = pessoa(); val po = client.novoPedido(o)
        assertEquals(HttpStatusCode.OK, client.poltronas(o, po, listOf(livre(3))).status)
    }

    @Test fun pixDeOutraPessoaNaoSeConsultaNemAprova() = comApp {
        val a = pessoa(); val b = pessoa(); val pg = json.decodeFromString<Pagamento>(client.pix(a, client.pronto(a)).bodyAsText())
        assertEquals(HttpStatusCode.NotFound, client.req(HttpMethod.Get, "/api/pagamentos/${pg.id}", b.token).status)
        assertEquals(HttpStatusCode.NotFound, client.req(HttpMethod.Post, "/api/pagamentos/${pg.id}/simular", b.token).status)
    }

    // ---------- simulado em produção ----------

    @Test fun emProducaoComPagamentoSimuladoACompraFicaDesligada() = comApp {
        val p = pessoa(); val ped = client.pronto(p)
        Email.emProducao = true
        assertEquals(HttpStatusCode.ServiceUnavailable, client.criar(p, listOf(simples.id), listOf(p.adulto)).status)
        assertEquals(HttpStatusCode.ServiceUnavailable, client.cartao(p, ped).status)
        assertEquals(HttpStatusCode.ServiceUnavailable, client.pix(p, ped).status)
        assertEquals(StatusPedido.CARRINHO, client.pedido(p, ped.id).status)
        assertEquals(mapOf("simulado" to true, "disponivel" to false), json.decodeFromString<Map<String, Boolean>>(client.req(HttpMethod.Get, "/api/pagamento/estado", null).bodyAsText()))
    }

    @Test fun botaoDeDemonstracaoNaoExisteEmProducao() = comApp {
        val p = pessoa(); val pg = json.decodeFromString<Pagamento>(client.pix(p, client.pronto(p)).bodyAsText())
        Email.emProducao = true
        assertEquals(HttpStatusCode.NotFound, client.req(HttpMethod.Post, "/api/pagamentos/${pg.id}/simular", p.token).status)
    }

    // ---------- D3: cancelamento ----------

    private suspend fun HttpClient.pago(p: Pessoa, poltrona: Int = livre(0)): Pedido { val ped = pronto(p, poltrona); client_cartao(p, ped); return pedido(p, ped.id) }
    private suspend fun HttpClient.client_cartao(p: Pessoa, ped: Pedido) = cartao(p, ped)

    @Test fun previaMostraAsDezCincoPorCentoDeTaxa() = comApp {
        val p = pessoa(); val pago = client.pago(p)
        val prev = json.decodeFromString<CancelamentoPrevia>(client.req(HttpMethod.Get, "/api/pedidos/${pago.id}/cancelamento", p.token).bodyAsText())
        val (taxa, reembolso) = Cotador.reembolso(pago.totais.totalCentavos, 5)
        assertEquals(pago.totais.totalCentavos, prev.totalPagoCentavos); assertEquals(taxa, prev.taxaCentavos); assertEquals(reembolso, prev.reembolsoCentavos); assertEquals(72, prev.prazoReembolsoHoras)
    }

    @Test fun cancelarTiraBilhetesDevolvePoltronasRegistraReembolsoEAvisa() = comApp {
        val p = pessoa(); val pago = client.pago(p, livre(4))
        val r = client.req(HttpMethod.Post, "/api/pedidos/${pago.id}/cancelar", p.token, """{"motivo":"Mudei de plano"}""")
        assertEquals(HttpStatusCode.OK, r.status)
        val c = json.decodeFromString<Pedido>(r.bodyAsText())
        assertEquals(StatusPedido.CANCELADO, c.status); assertEquals(Cotador.reembolso(pago.totais.totalCentavos, 5).second, c.reembolsoCentavos)
        assertTrue(c.bilhetes.isEmpty()); assertTrue(c.trechos.single().poltronas.isEmpty()); assertNotNull(c.canceladoEm)
        val o = pessoa(); val po = client.novoPedido(o)
        assertEquals(HttpStatusCode.OK, client.poltronas(o, po, listOf(livre(4))).status, "a poltrona voltou à venda")
        val avisos = json.decodeFromString(ListSerializer(Notificacao.serializer()), client.req(HttpMethod.Get, "/api/notificacoes", p.token).bodyAsText())
        assertTrue(avisos.any { it.tipo == TipoNotificacao.CANCELAMENTO && "72 horas" in it.texto })
    }

    @Test fun naoCancelaDuasVezesNemCarrinhoNemDepoisDaSaida() = comApp {
        val p = pessoa(); val pago = client.pago(p)
        assertEquals(HttpStatusCode.OK, client.req(HttpMethod.Post, "/api/pedidos/${pago.id}/cancelar", p.token, "{}").status)
        assertEquals(HttpStatusCode.Conflict, client.req(HttpMethod.Post, "/api/pedidos/${pago.id}/cancelar", p.token, "{}").status, "já cancelada")
        assertEquals(HttpStatusCode.Conflict, client.req(HttpMethod.Post, "/api/pedidos/${client.novoPedido(p).id}/cancelar", p.token, "{}").status, "carrinho não cancela")
        val outro = client.pago(p, livre(5)); Contas.agora = { outro.saidaEm + 1 }
        val r = client.req(HttpMethod.Post, "/api/pedidos/${outro.id}/cancelar", p.token, "{}")
        assertEquals(HttpStatusCode.Conflict, r.status); assertTrue("começou" in erroDe(r.bodyAsText()))
    }

    @Test fun taxaDeCancelamentoVemDoParametro() = comApp {
        val p = pessoa(); val pago = client.pago(p)
        BancoDeTeste.definirParametro("taxa_cancelamento_pct", 20)
        try {
            val prev = json.decodeFromString<CancelamentoPrevia>(client.req(HttpMethod.Get, "/api/pedidos/${pago.id}/cancelamento", p.token).bodyAsText())
            assertEquals(20, prev.taxaPct); assertEquals(Cotador.reembolso(pago.totais.totalCentavos, 20).first, prev.taxaCentavos)
        } finally { BancoDeTeste.definirParametro("taxa_cancelamento_pct", 5) }
    }

    // ---------- D1: histórico ----------

    @Test fun historicoListaSoOsMeusSemCarrinhoDoMaisNovoParaOMaisAntigo() = comApp {
        val p = pessoa(); val o = pessoa()
        val primeiro = client.pago(p, livre(6)); Contas.agora = { hoje + 60 }; val segundo = client.pago(p, livre(7)); client.novoPedido(p); client.pago(o, livre(8))
        val lista = json.decodeFromString(ListSerializer(Pedido.serializer()), client.req(HttpMethod.Get, "/api/pedidos", p.token).bodyAsText())
        assertEquals(listOf(segundo.id, primeiro.id), lista.map { it.id }, "sem o carrinho e sem o pedido da outra pessoa")
    }
}
