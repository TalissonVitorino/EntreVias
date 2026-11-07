package br.com.talisson.entrevias

import br.com.talisson.entrevias.domain.CadastroPedido
import br.com.talisson.entrevias.domain.FormaPagamento
import br.com.talisson.entrevias.domain.FormaPagamentoPedido
import br.com.talisson.entrevias.domain.Passageiro
import br.com.talisson.entrevias.domain.PassageiroPedido
import br.com.talisson.entrevias.domain.TIPO_CARTAO
import br.com.talisson.entrevias.domain.TIPO_PIX
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** A5 (formas de pagamento) e A6 (passageiros): só para quem está logado e só dos próprios dados. */
class CarteiraRotasTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val iteracoesReais = Contas.iteracoes
    private val limitesReais = listOf(Limites.cadastro, Limites.cadastroGlobal)

    @BeforeTest fun preparar() {
        BancoDeTeste.pronto; Contas.iteracoes = 1_000
        Limites.cadastro = LimiteDeTaxa(1_000_000, 3600); Limites.cadastroGlobal = LimiteDeTaxa(1_000_000, 3600)
        Email.envio = EnvioSimulado()
    }
    @AfterTest fun restaurar() { Contas.iteracoes = iteracoesReais; Limites.cadastro = limitesReais[0]; Limites.cadastroGlobal = limitesReais[1] }

    private companion object { val n = AtomicInteger() }
    private fun usuario(): String =
        (Contas.cadastrar(CadastroPedido("Maria Souza", "carteira${n.incrementAndGet()}@teste.com", null, "senha-segura-1", true)) as ResultadoCadastro.Ok).sessao.token

    private fun comApp(bloco: suspend ApplicationTestBuilder.() -> Unit) = testApplication { application { module() }; bloco() }

    private suspend fun HttpClient.req(m: HttpMethod, url: String, token: String?, corpo: String? = null) = request(url) {
        method = m
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        if (corpo != null) { contentType(ContentType.Application.Json); setBody(corpo) }
    }

    private val visa = FormaPagamentoPedido(TIPO_CARTAO, "Visa", "4242", "08/29")
    private val pix = FormaPagamentoPedido(TIPO_PIX, pixTipo = "CPF", pixFinal = "321")
    private suspend fun HttpClient.formas(t: String) = json.decodeFromString(ListSerializer(FormaPagamento.serializer()), req(HttpMethod.Get, "/api/formas-pagamento", t).bodyAsText())
    private suspend fun HttpClient.addForma(t: String, p: FormaPagamentoPedido) = req(HttpMethod.Post, "/api/formas-pagamento", t, json.encodeToString(p))
    private suspend fun HttpClient.passageiros(t: String) = json.decodeFromString(ListSerializer(Passageiro.serializer()), req(HttpMethod.Get, "/api/passageiros", t).bodyAsText())
    private suspend fun HttpClient.addPass(t: String, p: PassageiroPedido) = req(HttpMethod.Post, "/api/passageiros", t, json.encodeToString(p))

    @Test fun semLoginTodasAsRotasDao401() = comApp {
        for ((m, u) in listOf(HttpMethod.Get to "/api/formas-pagamento", HttpMethod.Post to "/api/formas-pagamento", HttpMethod.Delete to "/api/formas-pagamento/1",
            HttpMethod.Put to "/api/formas-pagamento/1/padrao", HttpMethod.Get to "/api/passageiros", HttpMethod.Post to "/api/passageiros",
            HttpMethod.Put to "/api/passageiros/1", HttpMethod.Delete to "/api/passageiros/1"))
            assertEquals(HttpStatusCode.Unauthorized, client.req(m, u, null, "{}").status, "$m $u")
    }

    @Test fun primeiraFormaViraPadraoEORotuloNaoTemNumeroCompleto() = comApp {
        val t = usuario()
        val r = client.addForma(t, visa); assertEquals(HttpStatusCode.Created, r.status)
        val f = client.formas(t).single()
        assertTrue(f.padrao); assertEquals("Visa •••• 4242", f.rotulo); assertEquals("4242", f.ultimos4)
        val banco = Banco.conexao().use { c -> c.createStatement().use { s -> s.executeQuery("select group_concat(rotulo || ultimos4 || validade) from forma_pagamento").use { it.next(); it.getString(1) } } }
        assertFalse(banco.contains("4242 4242"), "o número completo nunca existe no banco")
    }

    @Test fun pixGuardaSoOFinalDaChave() = comApp {
        val t = usuario(); client.addForma(t, pix)
        val f = client.formas(t).single()
        assertEquals("Pix CPF final ••• 321", f.rotulo); assertNull(f.ultimos4)
    }

    @Test fun trocarOPadraoDeixaUmSo() = comApp {
        val t = usuario(); client.addForma(t, visa); val segunda = json.decodeFromString<FormaPagamento>(client.addForma(t, pix).bodyAsText())
        assertFalse(segunda.padrao, "a segunda não toma o lugar sozinha")
        val lista = json.decodeFromString(ListSerializer(FormaPagamento.serializer()), client.req(HttpMethod.Put, "/api/formas-pagamento/${segunda.id}/padrao", t).bodyAsText())
        assertEquals(1, lista.count { it.padrao }); assertTrue(lista.first { it.id == segunda.id }.padrao)
        assertEquals(segunda.id, lista.first().id, "o padrão vem primeiro")
    }

    @Test fun removerOPadraoPromoveOutraParaNuncaFicarSemPadrao() = comApp {
        val t = usuario(); val a = json.decodeFromString<FormaPagamento>(client.addForma(t, visa).bodyAsText()); client.addForma(t, pix)
        client.req(HttpMethod.Delete, "/api/formas-pagamento/${a.id}", t)
        val sobra = client.formas(t).single()
        assertTrue(sobra.padrao)
    }

    @Test fun outraPessoaNaoVeNemApagaNemMexeNaFormaDeQuemNaoEhDela() = comApp {
        val a = usuario(); val b = usuario()
        val f = json.decodeFromString<FormaPagamento>(client.addForma(a, visa).bodyAsText())
        assertTrue(client.formas(b).isEmpty())
        assertEquals(HttpStatusCode.NotFound, client.req(HttpMethod.Delete, "/api/formas-pagamento/${f.id}", b).status)
        assertEquals(HttpStatusCode.NotFound, client.req(HttpMethod.Put, "/api/formas-pagamento/${f.id}/padrao", b).status)
        assertEquals(1, client.formas(a).size, "a forma da A continua lá")
    }

    @Test fun formaInvalidaDa400ELimiteDeCincoDa409() = comApp {
        val t = usuario()
        assertEquals(HttpStatusCode.BadRequest, client.addForma(t, visa.copy(bandeira = "Inventada")).status)
        assertEquals(HttpStatusCode.BadRequest, client.addForma(t, visa.copy(ultimos4 = "4242424242424242")).status, "número inteiro é recusado")
        assertEquals(HttpStatusCode.BadRequest, client.req(HttpMethod.Post, "/api/formas-pagamento", t, "{isso não é json").status)
        repeat(5) { assertEquals(HttpStatusCode.Created, client.addForma(t, visa).status) }
        assertEquals(HttpStatusCode.Conflict, client.addForma(t, visa).status)
        assertEquals(5, client.formas(t).size)
    }

    // ---------- A6 ----------

    @Test fun categoriaDoPassageiroSaiDaIdadeNoServidor() = comApp {
        val t = usuario()
        for ((idade, cat) in listOf(1 to "colo", 8 to "crianca", 30 to "adulto", 70 to "idoso")) {
            val p = json.decodeFromString<Passageiro>(client.addPass(t, PassageiroPedido("Fulano $idade", "Dependente", idade)).bodyAsText())
            assertEquals(cat, p.categoria)
        }
    }

    @Test fun vocePassaPrimeiroNaLista() = comApp {
        val t = usuario(); client.addPass(t, PassageiroPedido("Davi Lima", "Dependente", 8)); client.addPass(t, PassageiroPedido("Ivanete Lima", "Você", 62))
        assertEquals(listOf("Você", "Dependente"), client.passageiros(t).map { it.relacao })
    }

    @Test fun editarMudaACategoriaJuntoComAIdade() = comApp {
        val t = usuario(); val p = json.decodeFromString<Passageiro>(client.addPass(t, PassageiroPedido("Davi", "Dependente", 8)).bodyAsText())
        val e = json.decodeFromString<Passageiro>(client.req(HttpMethod.Put, "/api/passageiros/${p.id}", t, json.encodeToString(PassageiroPedido("Davi Lima", "Dependente", 12, "411", "Cadeira de rodas"))).bodyAsText())
        assertEquals("adulto", e.categoria); assertEquals("Davi Lima", e.nome); assertEquals("411", e.documentoFinal)
    }

    @Test fun outraPessoaNaoEditaNemRemovePassageiroAlheio() = comApp {
        val a = usuario(); val b = usuario()
        val p = json.decodeFromString<Passageiro>(client.addPass(a, PassageiroPedido("Davi", "Dependente", 8)).bodyAsText())
        assertTrue(client.passageiros(b).isEmpty())
        assertEquals(HttpStatusCode.NotFound, client.req(HttpMethod.Put, "/api/passageiros/${p.id}", b, json.encodeToString(PassageiroPedido("Invasor", "Dependente", 30))).status)
        assertEquals(HttpStatusCode.NotFound, client.req(HttpMethod.Delete, "/api/passageiros/${p.id}", b).status)
        assertEquals("Davi", client.passageiros(a).single().nome)
    }

    @Test fun passageiroInvalidoDa400ELimiteDeDezDa409() = comApp {
        val t = usuario()
        assertEquals(HttpStatusCode.BadRequest, client.addPass(t, PassageiroPedido("D", "Dependente", 8)).status)
        assertEquals(HttpStatusCode.BadRequest, client.addPass(t, PassageiroPedido("Davi", "Dependente", 8, documentoFinal = "41234567890")).status, "documento completo é recusado")
        repeat(10) { assertEquals(HttpStatusCode.Created, client.addPass(t, PassageiroPedido("Pessoa $it", "Dependente", 30)).status) }
        assertEquals(HttpStatusCode.Conflict, client.addPass(t, PassageiroPedido("Pessoa 11", "Dependente", 30)).status)
    }

    @Test fun removerPassageiroDevolveAListaAtualizada() = comApp {
        val t = usuario(); val p = json.decodeFromString<Passageiro>(client.addPass(t, PassageiroPedido("Davi", "Dependente", 8)).bodyAsText()); client.addPass(t, PassageiroPedido("Ana", "Dependente", 1))
        val r = client.req(HttpMethod.Delete, "/api/passageiros/${p.id}", t)
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(listOf("Ana"), json.decodeFromString(ListSerializer(Passageiro.serializer()), r.bodyAsText()).map { it.nome })
    }
}
