package br.com.talisson.entrevias

import br.com.talisson.entrevias.domain.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Comprovante da compra: conteúdo, link temporário do PDF e o PDF em si. */
class ComprovanteRotasTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val relogioReal = Contas.agora
    private val iteracoesReais = Contas.iteracoes
    private val limitesReais = listOf(Limites.cadastro, Limites.cadastroGlobal, Limites.pedidoPorUsuario, Limites.comprovantePorUsuario)
    private val producaoReal = Email.emProducao
    private var agora = LocalDate.of(2026, 11, 10).atTime(LocalTime.NOON).toEpochSecond(ZoneOffset.ofHours(-3))

    @BeforeTest fun preparar() {
        BancoDeTeste.pronto; Contas.iteracoes = 1_000; Contas.agora = { agora }; Email.emProducao = false
        Limites.cadastro = LimiteDeTaxa(1_000_000, 3600); Limites.cadastroGlobal = LimiteDeTaxa(1_000_000, 3600)
        Limites.pedidoPorUsuario = LimiteDeTaxa(1_000_000, 3600); Limites.comprovantePorUsuario = LimiteDeTaxa(1_000_000, 3600)
        trecho = candidatas[(candidatas.size - 1 - proxima.getAndIncrement() % candidatas.size)]
    }
    @AfterTest fun restaurar() {
        Contas.agora = relogioReal; Contas.iteracoes = iteracoesReais; Email.emProducao = producaoReal
        Limites.cadastro = limitesReais[0]; Limites.cadastroGlobal = limitesReais[1]; Limites.pedidoPorUsuario = limitesReais[2]; Limites.comprovantePorUsuario = limitesReais[3]
    }

    private companion object { val n = AtomicInteger(); val proxima = AtomicInteger() }

    private class Pessoa(val token: String, val id: Int, val adulto: Int)

    private fun pessoa(): Pessoa {
        val s = (Contas.cadastrar(CadastroPedido("Maria Souza", "comprov${n.incrementAndGet()}@teste.com", null, "senha-segura-1", true), PAPEL_VIAJANTE) as ResultadoCadastro.Ok).sessao
        val a = (Carteira.adicionarPassageiro(s.usuario.id, PassageiroPedido("Ana Adulta (comprovante)", "Você", 30, "1234")) as ResultadoCarteira.Ok).valor.id
        return Pessoa(s.token, s.usuario.id, a)
    }

    private val viagens = run { BancoDeTeste.pronto; Banco.viagens(LocalDate.of(2026, 11, 20), 2) }
    private val candidatas by lazy { viagens.filter { it.precoCentavos > 0 } }
    private lateinit var trecho: Viagem

    private fun comApp(bloco: suspend ApplicationTestBuilder.() -> Unit) = testApplication { application { module() }; bloco() }
    private suspend fun HttpClient.req(m: HttpMethod, url: String, token: String? = null) = request(url) { method = m; token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }

    private fun livre(v: Int): Int {
        val reservadas = Banco.conexao().use { c -> c.prepareStatement("select numero from reserva_assento where viagem_id = ?").use { it.setInt(1, v); it.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getInt(1)) } } } }
        return (1..40).first { it !in Compras.ocupadasSimuladas(v) && it !in listOf(1, 5) && it !in reservadas }
    }

    private fun comprar(p: Pessoa, cupom: String? = null): Pedido {
        val ped = Compras.criar(p.id, NovoPedido(listOf(trecho.id), listOf(p.adulto), cupom))
        Compras.escolher(p.id, ped.id, ped.trechos.map { EscolhaDePoltronas(it.viagemId, listOf(livre(it.viagemId))) })
        Compras.pagarComCartao(p.id, ped.id, PagamentoCartaoPedido("4242", "Visa"))
        return Compras.pedido(p.id, ped.id)!!
    }

    private suspend fun HttpClient.comprovante(p: Pessoa?, id: Int) = req(HttpMethod.Get, "/api/pedidos/$id/comprovante", p?.token)
    private suspend fun HttpClient.link(p: Pessoa?, id: Int) = req(HttpMethod.Post, "/api/pedidos/$id/comprovante/link", p?.token)
    private fun linkDe(r: HttpResponse, corpo: String) = json.decodeFromString<LinkDoComprovante>(corpo).also { assertEquals(HttpStatusCode.OK, r.status, corpo) }

    // ---------- conteúdo ----------

    @Test fun exigeLoginECompraPaga() = comApp {
        val p = pessoa(); val ped = comprar(p)
        assertEquals(HttpStatusCode.Unauthorized, client.comprovante(null, ped.id).status)
        assertEquals(HttpStatusCode.OK, client.comprovante(p, ped.id).status)
        val carrinho = Compras.criar(p.id, NovoPedido(listOf(trecho.id), listOf(p.adulto)))
        assertEquals(HttpStatusCode.Conflict, client.comprovante(p, carrinho.id).status, "sem pagamento não há comprovante")
        assertEquals(HttpStatusCode.Conflict, client.link(p, carrinho.id).status)
    }

    @Test fun soODonoVe() = comApp {
        val a = pessoa(); val b = pessoa(); val ped = comprar(a)
        assertEquals(HttpStatusCode.NotFound, client.comprovante(b, ped.id).status)
        assertEquals(HttpStatusCode.NotFound, client.link(b, ped.id).status)
        assertEquals(HttpStatusCode.NotFound, client.comprovante(a, 99_999_999).status)
    }

    @Test fun trazCodigoPassageiroTrechoBilheteETotal() = comApp {
        val p = pessoa(); val ped = comprar(p, "VIAJA10")
        val c = json.decodeFromString<Comprovante>(client.comprovante(p, ped.id).bodyAsText())
        val texto = c.texto()
        assertEquals(ped.codigo, c.codigo)
        assertTrue("Ana Adulta (comprovante)" in texto && "documento final 1234" in texto)
        assertTrue(ped.bilhetes.all { it.codigo in texto }, "todos os bilhetes")
        assertTrue(Notificacoes.reais(ped.totais.totalCentavos) in texto && "Cupom VIAJA10" in texto)
        assertTrue("IDA · " in texto && "Poltronas" in texto, texto)
        assertTrue("Pagamento simulado" in c.aviso, "avisa que o pagamento é simulado")
    }

    @Test fun compraCanceladaMostraOReembolso() = comApp {
        val p = pessoa(); val ped = comprar(p)
        Compras.cancelar(p.id, ped.id, null)
        val c = json.decodeFromString<Comprovante>(client.comprovante(p, ped.id).bodyAsText())
        assertTrue("Cancelada" in c.texto() && "Reembolso" in c.texto())
    }

    // ---------- link e PDF ----------

    @Test fun linkAbreOPdfSemLoginComCabecalhosSeguros() = comApp {
        val p = pessoa(); val ped = comprar(p)
        val r = client.link(p, ped.id); val l = linkDe(r, r.bodyAsText())
        assertTrue(l.caminho.startsWith("/comprovante/") && l.caminho.endsWith(".pdf")); assertEquals(Comprovantes.VALIDADE_SEG, l.validoPorSeg)
        val pdf = client.req(HttpMethod.Get, l.caminho)
        assertEquals(HttpStatusCode.OK, pdf.status); assertEquals(ContentType.Application.Pdf, pdf.contentType()?.withoutParameters())
        assertEquals("no-store", pdf.headers[HttpHeaders.CacheControl])
        val bytes = pdf.readRawBytes(); val s = bytes.toString(Charsets.ISO_8859_1)
        assertTrue(s.startsWith("%PDF-1.4") && s.trimEnd().endsWith("%%EOF"))
        assertTrue(ped.codigo in s && Notificacoes.reais(ped.totais.totalCentavos) in s)
    }

    @Test fun pdfTemTabelaDeReferenciasCerta() = comApp {
        val p = pessoa(); val ped = comprar(p)
        val l = linkDe(client.link(p, ped.id).let { it }, client.link(p, ped.id).bodyAsText())
        val s = client.req(HttpMethod.Get, l.caminho).readRawBytes().toString(Charsets.ISO_8859_1)
        val inicio = Regex("startxref\\s+(\\d+)").find(s)!!.groupValues[1].toInt()
        assertTrue(s.substring(inicio).startsWith("xref"), "startxref aponta para a tabela")
        val entradas = Regex("(\\d{10}) 00000 n ").findAll(s.substring(inicio)).map { it.groupValues[1].toInt() }.toList()
        assertTrue(entradas.isNotEmpty())
        entradas.forEachIndexed { i, off -> assertTrue(s.substring(off).startsWith("${i + 1} 0 obj"), "objeto ${i + 1} no deslocamento $off") }
        assertEquals(entradas.size + 1, Regex("/Size (\\d+)").find(s)!!.groupValues[1].toInt())
    }

    @Test fun linkVenceEOutroTokenNaoServe() = comApp {
        val p = pessoa(); val ped = comprar(p)
        val l = linkDe(client.link(p, ped.id).let { it }, client.link(p, ped.id).bodyAsText())
        assertEquals(HttpStatusCode.NotFound, client.req(HttpMethod.Get, "/comprovante/${"x".repeat(32)}.pdf").status, "token inventado")
        assertEquals(HttpStatusCode.OK, client.req(HttpMethod.Get, l.caminho).status)
        agora += Comprovantes.VALIDADE_SEG + 1
        assertEquals(HttpStatusCode.NotFound, client.req(HttpMethod.Get, l.caminho).status, "vencido")
    }

    @Test fun cadaLinkValeSoParaOSeuPedido() = comApp {
        val p = pessoa(); val a = comprar(p); trecho = candidatas[(candidatas.indexOf(trecho) + 1) % candidatas.size]; val b = comprar(p)
        val la = linkDe(client.link(p, a.id).let { it }, client.link(p, a.id).bodyAsText())
        val s = client.req(HttpMethod.Get, la.caminho).readRawBytes().toString(Charsets.ISO_8859_1)
        assertTrue(a.codigo in s && b.codigo !in s)
    }

    @Test fun limiteDeLinksPorPessoa() = comApp {
        Limites.comprovantePorUsuario = LimiteDeTaxa(2, 3600)
        val p = pessoa(); val ped = comprar(p)
        assertEquals(HttpStatusCode.OK, client.link(p, ped.id).status); assertEquals(HttpStatusCode.OK, client.link(p, ped.id).status)
        assertEquals(HttpStatusCode.TooManyRequests, client.link(p, ped.id).status)
    }

    // ---------- o gerador de PDF ----------

    @Test fun pdfEscapaParentesesAcentosESetas() {
        val c = Comprovante("EV-1", listOf(SecaoComprovante("Ida · São Paulo → Rio", listOf(LinhaComprovante("Teste", "a (b) c \\ d ç ã")))), "ok")
        val bytes = Pdf.de(c); val s = bytes.toString(Charsets.ISO_8859_1)
        assertTrue("a \\(b\\) c \\\\ d" in s, "parênteses e barra escapados")
        assertTrue(bytes.contains('ç'.code.toByte()) && bytes.contains('ã'.code.toByte()), "acentos em Latin-1")
        assertTrue("São Paulo -> Rio" in s, "a seta vira ->")
    }

    @Test fun textoLongoViraVariasPaginas() {
        val linhas = (1..120).map { LinhaComprovante("Linha $it", "valor $it") }
        val s = Pdf.de(Comprovante("EV-2", listOf(SecaoComprovante("Muitas", linhas)), "fim")).toString(Charsets.ISO_8859_1)
        val paginas = Regex("/Type /Page /").findAll(s).count()
        assertTrue(paginas >= 2, "esperava 2+ páginas, veio $paginas")
        assertEquals(paginas, Regex("/Count (\\d+)").find(s)!!.groupValues[1].toInt())
        assertTrue("Linha 120" in s && "Linha 1" in s)
    }
}
