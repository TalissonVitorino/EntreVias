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

/** F1 (ajuda) e F2 (avaliação e reclamação). */
class AjudaRotasTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val relogioReal = Contas.agora
    private val iteracoesReais = Contas.iteracoes
    private val limitesReais = listOf(Limites.cadastro, Limites.cadastroGlobal, Limites.pedidoPorUsuario, Limites.chamadoPorUsuario)
    private val producaoReal = Email.emProducao
    private val hoje = LocalDate.of(2026, 11, 10).atTime(LocalTime.NOON).toEpochSecond(ZoneOffset.ofHours(-3))

    @BeforeTest fun preparar() {
        BancoDeTeste.pronto; Contas.iteracoes = 1_000; Contas.agora = { hoje }; Email.emProducao = false
        Limites.cadastro = LimiteDeTaxa(1_000_000, 3600); Limites.cadastroGlobal = LimiteDeTaxa(1_000_000, 3600); Limites.pedidoPorUsuario = LimiteDeTaxa(1_000_000, 3600); Limites.chamadoPorUsuario = LimiteDeTaxa(1_000_000, 3600)
        trecho = candidatas[candidatas.size - 1 - proxima.getAndIncrement() % candidatas.size]   // do fim para o começo: longe das viagens que as outras classes usam
    }
    @AfterTest fun restaurar() {
        Contas.agora = relogioReal; Contas.iteracoes = iteracoesReais; Email.emProducao = producaoReal
        Limites.cadastro = limitesReais[0]; Limites.cadastroGlobal = limitesReais[1]; Limites.pedidoPorUsuario = limitesReais[2]; Limites.chamadoPorUsuario = limitesReais[3]
    }

    private companion object { val n = AtomicInteger(); val proxima = AtomicInteger() }
    private class Pessoa(val token: String, val id: Int, val adulto: Int)

    private fun pessoa(papel: String = PAPEL_VIAJANTE): Pessoa {
        val s = (Contas.cadastrar(CadastroPedido("Maria Souza", "ajuda${n.incrementAndGet()}@teste.com", null, "senha-segura-1", true), papel) as ResultadoCadastro.Ok).sessao
        return Pessoa(s.token, s.usuario.id, (Carteira.adicionarPassageiro(s.usuario.id, PassageiroPedido("Ana Adulta", "Você", 30)) as ResultadoCarteira.Ok).valor.id)
    }

    private val viagens = run { BancoDeTeste.pronto; Banco.viagens(LocalDate.of(2026, 11, 20), 2) }
    private val candidatas by lazy { viagens.filter { it.precoCentavos > 0 } }
    private lateinit var trecho: Viagem

    private fun livre(v: Int): Int {
        val reservadas = Banco.conexao().use { c -> c.prepareStatement("select numero from reserva_assento where viagem_id = ?").use { it.setInt(1, v); it.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getInt(1)) } } } }
        return (1..40).first { it !in Compras.ocupadasSimuladas(v) && it !in listOf(1, 5) && it !in reservadas }
    }
    private fun comprar(p: Pessoa): Pedido {
        val ped = Compras.criar(p.id, NovoPedido(listOf(trecho.id), listOf(p.adulto)))
        Compras.escolher(p.id, ped.id, listOf(EscolhaDePoltronas(trecho.id, listOf(livre(trecho.id)))))
        Compras.pagarComCartao(p.id, ped.id, PagamentoCartaoPedido("4242", "Visa"))
        return Compras.pedido(p.id, ped.id)!!
    }

    private fun comApp(bloco: suspend ApplicationTestBuilder.() -> Unit) = testApplication { application { module() }; bloco() }
    private suspend fun HttpClient.req(m: HttpMethod, url: String, token: String?, corpo: String? = null) = request(url) {
        method = m; token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        if (corpo != null) { contentType(ContentType.Application.Json); setBody(corpo) }
    }
    private suspend fun HttpClient.abrir(p: Pessoa, c: NovoChamado) = req(HttpMethod.Post, "/api/chamados", p.token, json.encodeToString(c))
    private suspend fun HttpClient.avaliar(p: Pessoa, pedido: Int, trecho: Int, a: NovaAvaliacao) = req(HttpMethod.Post, "/api/pedidos/$pedido/trechos/$trecho/avaliacao", p.token, json.encodeToString(a))
    private fun depoisDeChegar(p: Pedido) { Contas.agora = { p.trechos.maxOf { it.chegadaReal } + 60 } }

    // ---------- acesso ----------

    @Test fun semLoginTudoDa401EViajanteNaoEntraNoQueEhDoGestor() = comApp {
        val v = pessoa()
        for ((m, u) in listOf(HttpMethod.Post to "/api/chamados", HttpMethod.Get to "/api/chamados", HttpMethod.Get to "/api/gestor/chamados", HttpMethod.Post to "/api/gestor/chamados/1/resposta",
            HttpMethod.Post to "/api/pedidos/1/trechos/1/avaliacao", HttpMethod.Get to "/api/pedidos/1/avaliacoes", HttpMethod.Get to "/api/gestor/reclamacoes"))
            assertEquals(HttpStatusCode.Unauthorized, client.req(m, u, null, "{}").status, "$m $u")
        for ((m, u) in listOf(HttpMethod.Get to "/api/gestor/chamados", HttpMethod.Post to "/api/gestor/chamados/1/resposta", HttpMethod.Get to "/api/gestor/reclamacoes"))
            assertEquals(HttpStatusCode.Forbidden, client.req(m, u, v.token, """{"texto":"oi"}""").status, "$m $u")
    }

    // ---------- F1 ----------

    @Test fun abrirChamadoGeraProtocoloEFicaSoParaQuemAbriu() = comApp {
        val a = pessoa(); val b = pessoa()
        val r = client.abrir(a, NovoChamado(assunto = "Reembolso", mensagem = "Quando cai?"))
        assertEquals(HttpStatusCode.Created, r.status)
        val c = json.decodeFromString<Chamado>(r.bodyAsText())
        assertTrue(Regex("AJ-\\d{5}").matches(c.protocolo)); assertEquals("aberto", c.status); assertNull(c.resposta)
        assertEquals(listOf(c.id), json.decodeFromString(ListSerializer(Chamado.serializer()), client.req(HttpMethod.Get, "/api/chamados", a.token).bodyAsText()).map { it.id })
        assertTrue(json.decodeFromString(ListSerializer(Chamado.serializer()), client.req(HttpMethod.Get, "/api/chamados", b.token).bodyAsText()).isEmpty())
    }

    @Test fun chamadoInvalidoOuDeViagemAlheiaFalha() = comApp {
        val a = pessoa(); val b = pessoa(); val pedidoDaB = comprar(b)
        assertEquals(HttpStatusCode.BadRequest, client.abrir(a, NovoChamado(assunto = "Inventado")).status)
        assertEquals(HttpStatusCode.BadRequest, client.abrir(a, NovoChamado(assunto = "Outro")).status)
        assertEquals(HttpStatusCode.BadRequest, client.abrir(a, NovoChamado(assunto = "Reembolso", mensagem = "x".repeat(1001))).status)
        assertEquals(HttpStatusCode.NotFound, client.abrir(a, NovoChamado(pedidoId = pedidoDaB.id, assunto = "Reembolso")).status, "viagem de outra pessoa")
        assertEquals(HttpStatusCode.NotFound, client.abrir(b, NovoChamado(pedidoId = pedidoDaB.id, trecho = 9, assunto = "Reembolso")).status, "trecho que não existe")
        assertEquals(HttpStatusCode.Created, client.abrir(b, NovoChamado(pedidoId = pedidoDaB.id, trecho = 1, assunto = "Reembolso")).status)
        assertEquals(HttpStatusCode.BadRequest, client.req(HttpMethod.Post, "/api/chamados", a.token, "{quebrado").status)
    }

    @Test fun abrirChamadoTemLimitePorHora() = comApp {
        val a = pessoa(); Limites.chamadoPorUsuario = LimiteDeTaxa(2, 3600)
        repeat(2) { assertEquals(HttpStatusCode.Created, client.abrir(a, NovoChamado(assunto = "Reembolso")).status) }
        val r = client.abrir(a, NovoChamado(assunto = "Reembolso"))
        assertEquals(HttpStatusCode.TooManyRequests, r.status); assertNotNull(r.headers[HttpHeaders.RetryAfter])
    }

    @Test fun gestorVeTudoRespondeEAPessoaRecebeRespostaEAviso() = comApp {
        val g = pessoa(PAPEL_GESTOR); val a = pessoa()
        val c = json.decodeFromString<Chamado>(client.abrir(a, NovoChamado(assunto = "Reembolso")).bodyAsText())
        assertTrue(json.decodeFromString(ListSerializer(Chamado.serializer()), client.req(HttpMethod.Get, "/api/gestor/chamados", g.token).bodyAsText()).any { it.id == c.id })
        val r = client.req(HttpMethod.Post, "/api/gestor/chamados/${c.id}/resposta", g.token, json.encodeToString(RespostaChamado("O reembolso cai em até 72 horas.")))
        assertEquals(HttpStatusCode.OK, r.status)
        val dep = json.decodeFromString(ListSerializer(Chamado.serializer()), client.req(HttpMethod.Get, "/api/chamados", a.token).bodyAsText()).single()
        assertEquals("respondido", dep.status); assertEquals("O reembolso cai em até 72 horas.", dep.resposta)
        assertTrue(Notificacoes.lista(a.id).any { it.tipo == TipoNotificacao.SUPORTE && c.protocolo in it.titulo })
    }

    @Test fun respostaCurtaOuChamadoInexistente() = comApp {
        val g = pessoa(PAPEL_GESTOR); val a = pessoa(); val c = json.decodeFromString<Chamado>(client.abrir(a, NovoChamado(assunto = "Reembolso")).bodyAsText())
        assertEquals(HttpStatusCode.BadRequest, client.req(HttpMethod.Post, "/api/gestor/chamados/${c.id}/resposta", g.token, """{"texto":"x"}""").status)
        assertEquals(HttpStatusCode.NotFound, client.req(HttpMethod.Post, "/api/gestor/chamados/99999999/resposta", g.token, """{"texto":"Resolvido"}""").status)
    }

    // ---------- F2 ----------

    @Test fun naoAvaliaAntesDeChegarMasAvaliaDepois() = comApp {
        val p = pessoa(); val ped = comprar(p)
        val cedo = client.avaliar(p, ped.id, 1, NovaAvaliacao(5))
        assertEquals(HttpStatusCode.Conflict, cedo.status); assertTrue("depois de chegar" in json.decodeFromString<ErroApi>(cedo.bodyAsText()).mensagem)
        depoisDeChegar(ped)
        val ok = client.avaliar(p, ped.id, 1, NovaAvaliacao(4, listOf("Pontual", "Conforto"), "Foi tranquilo"))
        assertEquals(HttpStatusCode.Created, ok.status)
        val a = json.decodeFromString<Avaliacao>(ok.bodyAsText())
        assertEquals(4, a.estrelas); assertEquals(listOf("Pontual", "Conforto"), a.tags); assertFalse(a.reclamacao)
    }

    @Test fun atrasoAdiaOMomentoDeAvaliar() = comApp {
        val g = pessoa(PAPEL_GESTOR); val p = pessoa(); val ped = comprar(p)
        Compras.let { Ocorrencias.registrar(trecho.id, TipoOcorrencia.ATRASO, 120) }
        Contas.agora = { ped.trechos.single().chegadaEm + 60 }   // passou da chegada prevista, mas o ônibus ainda está a caminho
        assertEquals(HttpStatusCode.Conflict, client.avaliar(p, ped.id, 1, NovaAvaliacao(3)).status)
        Contas.agora = { ped.trechos.single().chegadaEm + 121 * 60 }
        assertEquals(HttpStatusCode.Created, client.avaliar(p, ped.id, 1, NovaAvaliacao(3)).status)
        assertNotNull(g)
    }

    @Test fun naoAvaliaDuasVezesNemTrechoInexistenteNemCompraAlheiaCanceladaOuCarrinho() = comApp {
        val p = pessoa(); val o = pessoa(); val ped = comprar(p); depoisDeChegar(ped)
        assertEquals(HttpStatusCode.Created, client.avaliar(p, ped.id, 1, NovaAvaliacao(5)).status)
        assertEquals(HttpStatusCode.Conflict, client.avaliar(p, ped.id, 1, NovaAvaliacao(1)).status, "uma por trecho")
        assertEquals(HttpStatusCode.NotFound, client.avaliar(p, ped.id, 7, NovaAvaliacao(5)).status)
        assertEquals(HttpStatusCode.NotFound, client.avaliar(o, ped.id, 1, NovaAvaliacao(5)).status, "viagem de outra pessoa")
        Contas.agora = { hoje }
        val carrinho = Compras.criar(p.id, NovoPedido(listOf(trecho.id), listOf(p.adulto)))
        depoisDeChegar(carrinho)
        assertEquals(HttpStatusCode.Conflict, client.avaliar(p, carrinho.id, 1, NovaAvaliacao(5)).status, "carrinho não é viagem feita")
    }

    @Test fun avaliacaoInvalidaDa400() = comApp {
        val p = pessoa(); val ped = comprar(p); depoisDeChegar(ped)
        for (a in listOf(NovaAvaliacao(0), NovaAvaliacao(6), NovaAvaliacao(3, listOf("Inventada")), NovaAvaliacao(3, comentario = "x".repeat(501)), NovaAvaliacao(1, reclamacao = true)))
            assertEquals(HttpStatusCode.BadRequest, client.avaliar(p, ped.id, 1, a).status, a.toString())
        assertEquals(HttpStatusCode.BadRequest, client.req(HttpMethod.Post, "/api/pedidos/${ped.id}/trechos/1/avaliacao", p.token, "{quebrado").status)
    }

    @Test fun minhasAvaliacoesSaoSoMinhasEReclamacoesChegamAoGestor() = comApp {
        val g = pessoa(PAPEL_GESTOR); val p = pessoa(); val o = pessoa(); val ped = comprar(p); depoisDeChegar(ped)
        client.avaliar(p, ped.id, 1, NovaAvaliacao(1, listOf("Atraso"), "O ônibus saiu 40 minutos depois", reclamacao = true))
        val minhas = json.decodeFromString(ListSerializer(Avaliacao.serializer()), client.req(HttpMethod.Get, "/api/pedidos/${ped.id}/avaliacoes", p.token).bodyAsText())
        assertEquals(1, minhas.size); assertTrue(minhas.single().reclamacao)
        assertEquals(HttpStatusCode.NotFound, client.req(HttpMethod.Get, "/api/pedidos/${ped.id}/avaliacoes", o.token).status)
        val recl = json.decodeFromString(ListSerializer(Avaliacao.serializer()), client.req(HttpMethod.Get, "/api/gestor/reclamacoes", g.token).bodyAsText())
        assertTrue(recl.any { it.pedidoId == ped.id })
    }

    @Test fun avaliacaoSemReclamacaoNaoApareceParaOGestor() = comApp {
        val g = pessoa(PAPEL_GESTOR); val p = pessoa(); val ped = comprar(p); depoisDeChegar(ped)
        client.avaliar(p, ped.id, 1, NovaAvaliacao(5, listOf("Pontual")))
        val recl = json.decodeFromString(ListSerializer(Avaliacao.serializer()), client.req(HttpMethod.Get, "/api/gestor/reclamacoes", g.token).bodyAsText())
        assertTrue(recl.none { it.pedidoId == ped.id })
    }
}
