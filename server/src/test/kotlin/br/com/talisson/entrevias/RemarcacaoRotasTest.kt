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

/** Remarcação: uma vez, até 2 h antes, sem taxa, para viagem da mesma rota de preço igual ou menor. */
class RemarcacaoRotasTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val relogioReal = Contas.agora
    private val iteracoesReais = Contas.iteracoes
    private val limitesReais = listOf(Limites.cadastro, Limites.cadastroGlobal, Limites.pedidoPorUsuario, Limites.remarcacaoPorUsuario)
    private val producaoReal = Email.emProducao
    private val inicio = LocalDate.of(2026, 11, 10).atTime(LocalTime.NOON).toEpochSecond(ZoneOffset.ofHours(-3))
    private var agora = inicio

    @BeforeTest fun preparar() {
        BancoDeTeste.pronto; Contas.iteracoes = 1_000; Contas.agora = { agora }; Email.emProducao = false
        Limites.cadastro = LimiteDeTaxa(1_000_000, 3600); Limites.cadastroGlobal = LimiteDeTaxa(1_000_000, 3600)
        Limites.pedidoPorUsuario = LimiteDeTaxa(1_000_000, 3600); Limites.remarcacaoPorUsuario = LimiteDeTaxa(1_000_000, 3600)
        par = pares[proxima.getAndIncrement() % pares.size]
    }
    @AfterTest fun restaurar() {
        Contas.agora = relogioReal; Contas.iteracoes = iteracoesReais; Email.emProducao = producaoReal
        Limites.cadastro = limitesReais[0]; Limites.cadastroGlobal = limitesReais[1]; Limites.pedidoPorUsuario = limitesReais[2]; Limites.remarcacaoPorUsuario = limitesReais[3]
    }

    private companion object { val n = AtomicInteger(); val proxima = AtomicInteger() }

    private class Pessoa(val token: String, val id: Int, val adulto: Int)

    private fun pessoa(): Pessoa {
        val s = (Contas.cadastrar(CadastroPedido("Maria Souza", "remarca${n.incrementAndGet()}@teste.com", null, "senha-segura-1", true), PAPEL_VIAJANTE) as ResultadoCadastro.Ok).sessao
        val a = (Carteira.adicionarPassageiro(s.usuario.id, PassageiroPedido("Ana Adulta (remarca)", "Você", 30)) as ResultadoCarteira.Ok).valor.id
        return Pessoa(s.token, s.usuario.id, a)
    }

    private val viagens = run { BancoDeTeste.pronto; Banco.viagens(LocalDate.of(2026, 11, 20), 2) }
    /** (a comprada, b alternativa): mesma rota, a custa o mesmo ou mais que b. */
    private val pares by lazy {
        viagens.groupBy { it.origem to it.destino }.values.filter { it.size >= 2 }.map { g -> val s = g.sortedByDescending { it.precoCentavos }; s[0] to s[1] }.filter { (a, b) -> a.precoCentavos > 0 && b.precoCentavos <= a.precoCentavos }
    }
    private lateinit var par: Pair<Viagem, Viagem>

    private fun comApp(bloco: suspend ApplicationTestBuilder.() -> Unit) = testApplication { application { module() }; bloco() }
    private suspend fun HttpClient.req(m: HttpMethod, url: String, token: String? = null, corpo: String? = null) = request(url) {
        method = m; token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        if (corpo != null) { contentType(ContentType.Application.Json); setBody(corpo) }
    }

    private fun reservadas(v: Int): Set<Int> = Banco.conexao().use { c -> c.prepareStatement("select numero from reserva_assento where viagem_id = ?").use { it.setInt(1, v); it.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getInt(1)) } } } }
    private fun livres(v: Int) = (1..40).filter { it !in Compras.ocupadasSimuladas(v) && it !in listOf(1, 5) && it !in reservadas(v) }

    private fun comprar(p: Pessoa, vararg ids: Int): Pedido {
        val ped = Compras.criar(p.id, NovoPedido(ids.toList(), listOf(p.adulto)))
        Compras.escolher(p.id, ped.id, ped.trechos.map { EscolhaDePoltronas(it.viagemId, listOf(livres(it.viagemId)[0])) })
        Compras.pagarComCartao(p.id, ped.id, PagamentoCartaoPedido("4242", "Visa"))
        return Compras.pedido(p.id, ped.id)!!
    }

    private suspend fun HttpClient.alternativas(p: Pessoa?, ped: Int, trecho: Int = 1) = req(HttpMethod.Get, "/api/pedidos/$ped/trechos/$trecho/alternativas", p?.token)
    private suspend fun HttpClient.remarcar(p: Pessoa?, ped: Int, viagem: Int, poltronas: List<Int>, trecho: Int = 1) =
        req(HttpMethod.Post, "/api/pedidos/$ped/remarcar", p?.token, json.encodeToString(RemarcacaoPedido(trecho, viagem, poltronas)))
    private fun lista(r: HttpResponse, corpo: String) = json.decodeFromString(ListSerializer(Alternativa.serializer()), corpo).also { assertEquals(HttpStatusCode.OK, r.status, corpo) }

    // ---------- acesso ----------

    @Test fun exigeLoginDonoECompraPaga() = comApp {
        val a = pessoa(); val b = pessoa(); val ped = comprar(a, par.first.id)
        assertEquals(HttpStatusCode.Unauthorized, client.alternativas(null, ped.id).status)
        assertEquals(HttpStatusCode.NotFound, client.alternativas(b, ped.id).status)
        assertEquals(HttpStatusCode.NotFound, client.remarcar(b, ped.id, par.second.id, listOf(livres(par.second.id)[0])).status)
        val carrinho = Compras.criar(a.id, NovoPedido(listOf(par.first.id), listOf(a.adulto)))
        assertEquals(HttpStatusCode.Conflict, client.alternativas(a, carrinho.id).status, "só compra paga")
        assertEquals(HttpStatusCode.NotFound, client.alternativas(a, ped.id, trecho = 9).status)
    }

    // ---------- alternativas ----------

    @Test fun alternativasSaoDaMesmaRotaDePrecoIgualOuMenorENuncaAPropria() = comApp {
        val p = pessoa(); val ped = comprar(p, par.first.id)
        val r = client.alternativas(p, ped.id); val alts = lista(r, r.bodyAsText())
        assertTrue(alts.any { it.viagemId == par.second.id }, "a outra viagem da rota aparece")
        assertTrue(alts.none { it.viagemId == par.first.id }, "nunca a própria viagem")
        val porId = Banco.viagens(LocalDate.of(2026, 11, 1), 60).associateBy { it.id }
        assertTrue(alts.all { porId[it.viagemId]!!.origem == par.first.origem && porId[it.viagemId]!!.destino == par.first.destino }, "mesma rota")
        assertTrue(alts.all { it.precoCentavos <= par.first.precoCentavos }, "preço igual ou menor")
    }

    @Test fun viagemCanceladaPelaEmpresaNaoEhAlternativa() = comApp {
        val p = pessoa(); val ped = comprar(p, par.first.id)
        Ocorrencias.registrar(par.second.id, TipoOcorrencia.CANCELADA, 0)
        try {
            val alts = lista(client.alternativas(p, ped.id).let { it }, client.alternativas(p, ped.id).bodyAsText())
            assertTrue(alts.none { it.viagemId == par.second.id })
        } finally { Banco.conexao().use { c -> c.prepareStatement("delete from viagem_estado where viagem_id = ?").use { it.setInt(1, par.second.id); it.executeUpdate() } } }
    }

    @Test fun naoSugereViagemQueNaoEncaixaComAVolta() = comApp {
        // ida e volta: a nova ida não pode chegar depois de a volta sair (com a folga mínima)
        val ida = par.first
        val volta = viagens.firstOrNull { it.origem == ida.destino && it.destino == ida.origem && it.saidaMin >= ida.chegadaMin + 600 && it.id != ida.id } ?: return@comApp
        val p = pessoa()
        val ped = Compras.criar(p.id, NovoPedido(listOf(ida.id), listOf(p.adulto), viagemIdsVolta = listOf(volta.id)))
        Compras.escolher(p.id, ped.id, ped.trechos.map { EscolhaDePoltronas(it.viagemId, listOf(livres(it.viagemId)[0])) })
        Compras.pagarComCartao(p.id, ped.id, PagamentoCartaoPedido("4242", "Visa"))
        val r = client.alternativas(p, ped.id, 1); val alts = lista(r, r.bodyAsText())
        val minima = Banco.parametros().minimaMin * 60L
        val voltaEm = Compras.pedido(p.id, ped.id)!!.trechos.first { it.volta }.saidaReal
        assertTrue(alts.all { it.chegadaEm + minima <= voltaEm }, "toda ida sugerida chega a tempo da volta")
    }

    // ---------- a troca ----------

    @Test fun remarcarTrocaOTrechoRefazBilhetesELiberaAPoltronaAntiga() = comApp {
        val p = pessoa(); val ped = comprar(p, par.first.id)
        val antigaPoltrona = ped.trechos.single().poltronas.single(); val codigosAntigos = ped.bilhetes.map { it.codigo }.toSet()
        val nova = livres(par.second.id)[0]
        val r = client.remarcar(p, ped.id, par.second.id, listOf(nova)); assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val dep = json.decodeFromString<Pedido>(r.bodyAsText())
        assertEquals(par.second.id, dep.trechos.single().viagemId); assertEquals(listOf(nova), dep.trechos.single().poltronas)
        assertEquals(ped.totais, dep.totais, "o valor pago não muda"); assertEquals(StatusPedido.PAGO, dep.status); assertEquals(agora, dep.remarcadaEm)
        assertEquals(ped.bilhetes.size, dep.bilhetes.size); assertTrue(dep.bilhetes.none { it.codigo in codigosAntigos }, "os bilhetes antigos deixam de valer")
        assertTrue(dep.bilhetes.all { it.poltrona == nova })
        assertTrue(antigaPoltrona !in reservadas(par.first.id), "a poltrona antiga volta à venda")
        Banco.conexao().use { c -> c.prepareStatement("select count(*) from reserva_assento where pedido_id = ? and viagem_id = ?").use { it.setInt(1, ped.id); it.setInt(2, par.first.id); it.executeQuery().use { rs -> rs.next(); assertEquals(0, rs.getInt(1), "a reserva da viagem antiga some") } } }
        val aviso = Notificacoes.lista(p.id).single { it.tipo == TipoNotificacao.REMARCACAO }
        assertEquals(ped.id, aviso.pedidoId); assertTrue("Nova saída" in aviso.texto)
    }

    @Test fun soUmaRemarcacaoPorCompra() = comApp {
        val p = pessoa(); val ped = comprar(p, par.first.id)
        assertEquals(HttpStatusCode.OK, client.remarcar(p, ped.id, par.second.id, listOf(livres(par.second.id)[0])).status)
        val r = client.remarcar(p, ped.id, par.first.id, listOf(livres(par.first.id)[0]))
        assertEquals(HttpStatusCode.Conflict, r.status); assertTrue("já foi remarcada" in r.bodyAsText())
        assertEquals(HttpStatusCode.Conflict, client.alternativas(p, ped.id).status)
    }

    @Test fun ate2HorasAntesPodeEMenosDisso() = comApp {
        val p = pessoa(); val ped = comprar(p, par.first.id)
        val saida = ped.trechos.single().saidaReal
        agora = saida - RegrasDePreco.REMARCACAO_ANTECEDENCIA_SEG + 1
        val tarde = client.remarcar(p, ped.id, par.second.id, listOf(livres(par.second.id)[0]))
        assertEquals(HttpStatusCode.Conflict, tarde.status); assertTrue("2 horas antes" in tarde.bodyAsText())
        agora = saida - RegrasDePreco.REMARCACAO_ANTECEDENCIA_SEG
        assertEquals(HttpStatusCode.OK, client.remarcar(p, ped.id, par.second.id, listOf(livres(par.second.id)[0])).status, "exatamente 2 h antes ainda vale")
    }

    @Test fun viagemForaDaListaNaoServe() = comApp {
        val p = pessoa(); val ped = comprar(p, par.first.id)
        val outra = viagens.first { it.origem != par.first.origem || it.destino != par.first.destino }
        assertEquals(HttpStatusCode.Conflict, client.remarcar(p, ped.id, outra.id, listOf(livres(outra.id)[0])).status, "outra rota")
        assertEquals(HttpStatusCode.Conflict, client.remarcar(p, ped.id, par.first.id, listOf(livres(par.first.id)[0])).status, "a mesma viagem")
        assertEquals(HttpStatusCode.Conflict, client.remarcar(p, ped.id, 99_999_999, listOf(1)).status)
        assertNull(Compras.pedido(p.id, ped.id)!!.remarcadaEm, "nada mudou")
    }

    // ---------- poltronas ----------

    @Test fun poltronasInvalidasNaoMudamNada() = comApp {
        val p = pessoa(); val ped = comprar(p, par.first.id); val v = par.second.id
        assertEquals(HttpStatusCode.BadRequest, client.remarcar(p, ped.id, v, emptyList()).status, "faltou")
        assertEquals(HttpStatusCode.BadRequest, client.remarcar(p, ped.id, v, listOf(livres(v)[0], livres(v)[1])).status, "sobrou")
        assertEquals(HttpStatusCode.BadRequest, client.remarcar(p, ped.id, v, listOf(41)).status, "fora do ônibus")
        assertEquals(HttpStatusCode.Conflict, client.remarcar(p, ped.id, v, listOf(Compras.ocupadasSimuladas(v).first())).status, "ocupada")
        assertEquals(HttpStatusCode.Conflict, client.remarcar(p, ped.id, v, listOf(1)).status, "PcD sem acessibilidade")
        val depois = Compras.pedido(p.id, ped.id)!!
        assertNull(depois.remarcadaEm); assertEquals(par.first.id, depois.trechos.single().viagemId); assertEquals(ped.bilhetes.map { it.codigo }, depois.bilhetes.map { it.codigo }, "bilhetes intactos")
    }

    @Test fun poltronaJaReservadaPorOutraPessoaDa409ENaoDesfazNada() = comApp {
        val a = pessoa(); val b = pessoa(); val pedA = comprar(a, par.first.id)
        val v = par.second.id; val disputada = livres(v)[0]
        val pedB = Compras.criar(b.id, NovoPedido(listOf(v), listOf(b.adulto))); Compras.escolher(b.id, pedB.id, listOf(EscolhaDePoltronas(v, listOf(disputada))))
        val r = client.remarcar(a, pedA.id, v, listOf(disputada))
        assertEquals(HttpStatusCode.Conflict, r.status, r.bodyAsText())
        val depois = Compras.pedido(a.id, pedA.id)!!
        assertNull(depois.remarcadaEm, "a tentativa que falhou não gasta a única remarcação")
        assertEquals(pedA.trechos.single().poltronas, depois.trechos.single().poltronas); assertEquals(par.first.id, depois.trechos.single().viagemId)
        assertEquals(HttpStatusCode.OK, client.remarcar(a, pedA.id, v, listOf(livres(v).first { it != disputada })).status, "e dá para tentar de novo")
    }

    @Test fun mapaDaAlternativaMostraOcupadasEPcdSoParaViagemValida() = comApp {
        val p = pessoa(); val ped = comprar(p, par.first.id); val v = par.second.id
        val r = client.req(HttpMethod.Get, "/api/pedidos/${ped.id}/trechos/1/alternativas/$v/poltronas", p.token)
        assertEquals(HttpStatusCode.OK, r.status)
        val m = json.decodeFromString<MapaDePoltronas>(r.bodyAsText())
        assertEquals(40, m.total); assertEquals(listOf(1, 5), m.pcd); assertTrue(Compras.ocupadasSimuladas(v).all { it in m.ocupadas })
        assertEquals(HttpStatusCode.Conflict, client.req(HttpMethod.Get, "/api/pedidos/${ped.id}/trechos/1/alternativas/${par.first.id}/poltronas", p.token).status, "viagem que não é alternativa")
    }

    @Test fun limiteDeRemarcacoesPorPessoa() = comApp {
        Limites.remarcacaoPorUsuario = LimiteDeTaxa(1, 3600)
        val p = pessoa(); val ped = comprar(p, par.first.id)
        assertEquals(HttpStatusCode.BadRequest, client.remarcar(p, ped.id, par.second.id, emptyList()).status)
        assertEquals(HttpStatusCode.TooManyRequests, client.remarcar(p, ped.id, par.second.id, emptyList()).status)
    }
}
