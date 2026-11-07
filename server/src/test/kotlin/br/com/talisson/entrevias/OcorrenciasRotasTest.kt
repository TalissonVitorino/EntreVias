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

/** D4 e D5: o gestor registra atraso/horário/cancelamento, quem comprou é avisado; viagem em andamento. */
class OcorrenciasRotasTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val relogioReal = Contas.agora
    private val iteracoesReais = Contas.iteracoes
    private val limitesReais = listOf(Limites.cadastro, Limites.cadastroGlobal, Limites.pedidoPorUsuario)
    private val producaoReal = Email.emProducao
    private val hoje = LocalDate.of(2026, 11, 10).atTime(LocalTime.NOON).toEpochSecond(ZoneOffset.ofHours(-3))

    @BeforeTest fun preparar() {
        BancoDeTeste.pronto; Contas.iteracoes = 1_000; Contas.agora = { hoje }; Email.emProducao = false
        Limites.cadastro = LimiteDeTaxa(1_000_000, 3600); Limites.cadastroGlobal = LimiteDeTaxa(1_000_000, 3600); Limites.pedidoPorUsuario = LimiteDeTaxa(1_000_000, 3600)
        trecho = candidatas[proxima.getAndIncrement() % candidatas.size]
        conexao = conexoes[proximaConexao.getAndIncrement() % conexoes.size]
    }
    @AfterTest fun restaurar() {
        Contas.agora = relogioReal; Contas.iteracoes = iteracoesReais; Email.emProducao = producaoReal
        Limites.cadastro = limitesReais[0]; Limites.cadastroGlobal = limitesReais[1]; Limites.pedidoPorUsuario = limitesReais[2]
    }

    private companion object { val n = AtomicInteger(); val proxima = AtomicInteger(); val proximaConexao = AtomicInteger() }

    private class Pessoa(val token: String, val id: Int, val adulto: Int)

    private fun pessoa(papel: String = PAPEL_VIAJANTE): Pessoa {
        val s = (Contas.cadastrar(CadastroPedido("Maria Souza", "ocorr${n.incrementAndGet()}@teste.com", null, "senha-segura-1", true), papel) as ResultadoCadastro.Ok).sessao
        val a = (Carteira.adicionarPassageiro(s.usuario.id, PassageiroPedido("Ana Adulta", "Você", 30)) as ResultadoCarteira.Ok).valor.id
        return Pessoa(s.token, s.usuario.id, a)
    }

    private val viagens = run { BancoDeTeste.pronto; Banco.viagens(LocalDate.of(2026, 11, 20), 2) }
    private val conexoes by lazy { viagens.flatMap { a -> viagens.filter { b -> b.origem == a.destino && b.destino != a.origem && b.saidaMin - a.chegadaMin in 60..480 }.map { b -> a to b } }.filter { (a, b) -> a.id != b.id } }
    private val candidatas by lazy { viagens.filter { it.precoCentavos > 0 }.filter { v -> conexoes.none { it.first.id == v.id || it.second.id == v.id } } }
    private lateinit var trecho: Viagem
    private lateinit var conexao: Pair<Viagem, Viagem>

    private fun comApp(bloco: suspend ApplicationTestBuilder.() -> Unit) = testApplication { application { module() }; bloco() }
    private suspend fun HttpClient.req(m: HttpMethod, url: String, token: String?, corpo: String? = null) = request(url) {
        method = m; token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        if (corpo != null) { contentType(ContentType.Application.Json); setBody(corpo) }
    }
    /** Primeira poltrona livre AGORA: nem simulada como ocupada, nem PcD, nem reservada por outro teste (o banco é um só na execução). */
    private fun livre(v: Int, i: Int = 0): Int {
        val reservadas = Banco.conexao().use { c -> c.prepareStatement("select numero from reserva_assento where viagem_id = ?").use { it.setInt(1, v); it.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getInt(1)) } } } }
        return (1..40).filter { it !in Compras.ocupadasSimuladas(v) && it !in listOf(1, 5) && it !in reservadas }[i]
    }

    /** Compra paga (cartão aprovado) de 1 adulto nos trechos dados. Direto na camada de regras: o fluxo HTTP já tem a suíte dele. */
    private fun comprar(p: Pessoa, vararg ids: Int): Pedido {
        val ped = Compras.criar(p.id, NovoPedido(ids.toList(), listOf(p.adulto)))
        Compras.escolher(p.id, ped.id, ped.trechos.map { EscolhaDePoltronas(it.viagemId, listOf(livre(it.viagemId))) })
        Compras.pagarComCartao(p.id, ped.id, PagamentoCartaoPedido("4242", "Visa"))
        return Compras.pedido(p.id, ped.id)!!
    }

    private suspend fun HttpClient.ocorrencia(g: Pessoa?, viagem: Int, tipo: String, minutos: Int = 0) =
        req(HttpMethod.Post, "/api/gestor/viagens/$viagem/ocorrencia", g?.token, json.encodeToString(OcorrenciaPedido(tipo, minutos)))
    private fun avisos(p: Pessoa) = Notificacoes.lista(p.id)

    // ---------- acesso e validação ----------

    @Test fun soGestorRegistraOcorrencia() = comApp {
        val g = pessoa(PAPEL_GESTOR); val v = pessoa()
        assertEquals(HttpStatusCode.Unauthorized, client.ocorrencia(null, trecho.id, "atraso", 10).status)
        assertEquals(HttpStatusCode.Forbidden, client.ocorrencia(v, trecho.id, "atraso", 10).status, "viajante não mexe em viagem")
        assertEquals(HttpStatusCode.OK, client.ocorrencia(g, trecho.id, "atraso", 10).status)
    }

    @Test fun ocorrenciaInvalidaDa400E404() = comApp {
        val g = pessoa(PAPEL_GESTOR)
        assertEquals(HttpStatusCode.BadRequest, client.ocorrencia(g, trecho.id, "explodiu", 10).status)
        assertEquals(HttpStatusCode.BadRequest, client.ocorrencia(g, trecho.id, "atraso", 0).status)
        assertEquals(HttpStatusCode.BadRequest, client.ocorrencia(g, trecho.id, "atraso", 601).status)
        assertEquals(HttpStatusCode.BadRequest, client.ocorrencia(g, trecho.id, "horario", 0).status)
        assertEquals(HttpStatusCode.NotFound, client.ocorrencia(g, 99_999_999, "atraso", 10).status)
        assertEquals(HttpStatusCode.BadRequest, client.req(HttpMethod.Post, "/api/gestor/viagens/${trecho.id}/ocorrencia", g.token, "{quebrado").status)
    }

    // ---------- avisos ----------

    @Test fun atrasoAvisaSoQuemComprouAquelaViagemEMostraONovoHorario() = comApp {
        val g = pessoa(PAPEL_GESTOR); val comprou = pessoa(); val outro = pessoa(); val soCarrinho = pessoa()
        val ped = comprar(comprou, trecho.id)
        Compras.criar(soCarrinho.id, NovoPedido(listOf(trecho.id), listOf(soCarrinho.adulto)))
        val r = json.decodeFromString<OcorrenciaResultado>(client.ocorrencia(g, trecho.id, "atraso", 25).bodyAsText())
        assertEquals(OcorrenciaResultado(1, 0), r)
        val a = avisos(comprou).single { it.tipo == TipoNotificacao.ATRASO }
        assertTrue("25 min" in a.texto && "atrasado" in a.texto); assertEquals(ped.id, a.pedidoId)
        assertTrue(avisos(outro).isEmpty() && avisos(soCarrinho).isEmpty(), "quem não comprou não é avisado")
        assertEquals(25, Compras.pedido(comprou.id, ped.id)!!.trechos.single().atrasoMin)
    }

    @Test fun atrasoQueApertaAConexaoAvisaDoRiscoComAFolga() = comApp {
        val g = pessoa(PAPEL_GESTOR); val p = pessoa(); val (a, b) = conexao
        val ped = comprar(p, a.id, b.id)
        val folga = b.saidaMin - a.chegadaMin
        val atraso = folga - 10   // sobra 10 min: abaixo do mínimo de 30
        client.ocorrencia(g, a.id, "atraso", atraso)
        val risco = avisos(p).single { it.tipo == TipoNotificacao.CONEXAO_RISCO }
        assertTrue("Folga de 10 min" in risco.texto && "abaixo do mínimo" in risco.texto, risco.texto); assertEquals(ped.id, risco.pedidoId)
    }

    @Test fun atrasoPequenoComFolgaGrandeNaoGeraAvisoDeRisco() = comApp {
        val g = pessoa(PAPEL_GESTOR); val p = pessoa()
        val (a, b) = conexoes.first { (x, y) -> y.saidaMin - x.chegadaMin >= 150 && x.id !in listOf(trecho.id) }
        comprar(p, a.id, b.id)
        client.ocorrencia(g, a.id, "atraso", 5)
        assertTrue(avisos(p).none { it.tipo == TipoNotificacao.CONEXAO_RISCO }); assertTrue(avisos(p).any { it.tipo == TipoNotificacao.ATRASO })
    }

    @Test fun horarioAlteradoDizDeOndePara() = comApp {
        val g = pessoa(PAPEL_GESTOR); val p = pessoa(); comprar(p, trecho.id)
        client.ocorrencia(g, trecho.id, "horario", 15)
        val a = avisos(p).single { it.tipo == TipoNotificacao.HORARIO }
        assertTrue("passou de" in a.texto && "para" in a.texto, a.texto)
        client.ocorrencia(g, trecho.id, "horario", -30)   // mais cedo também vale
        assertEquals(-30, Compras.pedidos(p.id).single().trechos.single().atrasoMin, "vale o último registro")
    }

    @Test fun viagemCanceladaReembolsaTudoSemTaxaESozinho() = comApp {
        val g = pessoa(PAPEL_GESTOR); val comprou = pessoa(); val outro = pessoa(); val soCarrinho = pessoa()
        val ped = comprar(comprou, trecho.id)
        val demais = viagens.first { it.id != trecho.id && it.id !in conexoes.flatMap { c -> listOf(c.first.id, c.second.id) } && it.precoCentavos > 0 }
        val doOutro = comprar(outro, demais.id)
        val carrinho = Compras.criar(soCarrinho.id, NovoPedido(listOf(trecho.id), listOf(soCarrinho.adulto)))
        val r = json.decodeFromString<OcorrenciaResultado>(client.ocorrencia(g, trecho.id, "cancelada").bodyAsText())
        assertEquals(OcorrenciaResultado(1, 1), r)
        val c = Compras.pedido(comprou.id, ped.id)!!
        assertEquals(StatusPedido.CANCELADO, c.status); assertEquals(ped.totais.totalCentavos, c.reembolsoCentavos, "100%, sem taxa")
        assertTrue(c.bilhetes.isEmpty() && c.trechos.single().poltronas.isEmpty())
        assertTrue(avisos(comprou).any { it.tipo == TipoNotificacao.VIAGEM_CANCELADA && "sem taxa" in it.texto })
        assertEquals(StatusPedido.PAGO, Compras.pedido(outro.id, doOutro.id)!!.status, "outra viagem não é tocada")
        assertEquals(StatusPedido.CARRINHO, Compras.pedido(soCarrinho.id, carrinho.id)!!.status, "carrinho não é tocado")
        val novo = pessoa()
        val p2 = Compras.criar(novo.id, NovoPedido(listOf(trecho.id), listOf(novo.adulto)))
        Compras.escolher(novo.id, p2.id, listOf(EscolhaDePoltronas(trecho.id, listOf(livre(trecho.id)))))   // a poltrona voltou à venda
    }

    @Test fun viagemCanceladaComDoisTrechosCancelaOPedidoTodo() = comApp {
        val g = pessoa(PAPEL_GESTOR); val p = pessoa(); val (a, b) = conexao
        val ped = comprar(p, a.id, b.id)
        client.ocorrencia(g, b.id, "cancelada")
        val c = Compras.pedido(p.id, ped.id)!!
        assertEquals(StatusPedido.CANCELADO, c.status); assertEquals(ped.totais.totalCentavos, c.reembolsoCentavos)
        assertTrue(c.trechos.all { it.poltronas.isEmpty() }, "as poltronas dos dois trechos voltaram")
    }

    // ---------- cancelar depois de atraso ----------

    @Test fun atrasoAdiaOLimiteParaCancelarPorContaPropria() = comApp {
        val g = pessoa(PAPEL_GESTOR); val p = pessoa(); val ped = comprar(p, trecho.id)
        client.ocorrencia(g, trecho.id, "atraso", 120)
        Contas.agora = { ped.trechos.single().saidaEm + 60 * 60 }   // 1 h depois da saída original, mas 1 h antes da saída real
        assertEquals(HttpStatusCode.OK, client.req(HttpMethod.Get, "/api/pedidos/${ped.id}/cancelamento", p.token).status)
        Contas.agora = { ped.trechos.single().saidaEm + 3 * 3600 }
        assertEquals(HttpStatusCode.Conflict, client.req(HttpMethod.Get, "/api/pedidos/${ped.id}/cancelamento", p.token).status)
    }

    // ---------- D5: viagem em andamento ----------

    private suspend fun HttpClient.ativas(p: Pessoa) = json.decodeFromString(ListSerializer(ViagemAtiva.serializer()), req(HttpMethod.Get, "/api/viagens-ativas", p.token).bodyAsText())

    @Test fun viagensAtivasMostramAEtapaDeCadaTrechoPeloRelogioDoServidor() = comApp {
        val p = pessoa(); val (a, b) = conexao; val ped = comprar(p, a.id, b.id)
        assertEquals(listOf(listOf("proximo", "proximo")), client.ativas(p).map { it.etapas })
        val t1 = ped.trechos[0]; val t2 = ped.trechos[1]
        Contas.agora = { t1.saidaEm + 60 }
        assertEquals(listOf("em_andamento", "proximo"), client.ativas(p).single().etapas)
        Contas.agora = { t1.chegadaEm + 60 }
        assertEquals(listOf("concluido", "proximo"), client.ativas(p).single().etapas)
        Contas.agora = { t2.chegadaEm + 60 }
        assertTrue(client.ativas(p).isEmpty(), "terminou: sai da lista")
    }

    @Test fun atrasoDeslocaAEtapaEmAndamento() = comApp {
        val g = pessoa(PAPEL_GESTOR); val p = pessoa(); val ped = comprar(p, trecho.id); val t = ped.trechos.single()
        client.ocorrencia(g, trecho.id, "atraso", 60)
        Contas.agora = { t.saidaEm + 30 * 60 }   // já passou da saída original, mas o ônibus só sai daqui a 30 min
        assertEquals(listOf("proximo"), client.ativas(p).single().etapas)
    }

    @Test fun viagensAtivasNaoMostramCarrinhoCanceladaNemDeOutraPessoa() = comApp {
        val p = pessoa(); val o = pessoa()
        Compras.criar(p.id, NovoPedido(listOf(trecho.id), listOf(p.adulto)))
        assertTrue(client.ativas(p).isEmpty(), "carrinho não é viagem")
        val ped = comprar(o, trecho.id)
        assertTrue(client.ativas(p).isEmpty(), "viagem de outra pessoa não aparece")
        Compras.cancelar(o.id, ped.id, null)
        assertTrue(client.ativas(o).isEmpty(), "cancelada não é viagem")
        assertEquals(HttpStatusCode.Unauthorized, client.req(HttpMethod.Get, "/api/viagens-ativas", null).status)
    }
}
