package br.com.talisson.entrevias

import br.com.talisson.entrevias.domain.*
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

/** Compra, histórico e cancelamento no app, contra um servidor de mentira (JDK) que devolve objetos reais do domínio. */
class CompraEstadoTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val token = "tokenDeTeste0123456789-abcdefghij_KLMN"
    private var servidor: HttpServer? = null
    private val pedidos = CopyOnWriteArrayList<String>()
    private var armazenamento = ArmazenamentoEmMemoria()

    private val viagemA = Viagem(101, "Viação Horizonte Sul", "Convencional", "CWB", "JVL", 390, 600, 8980)
    private val viagemB = Viagem(102, "Viação Serra Azul", "Convencional", "JVL", "FLN", 755, 900, 14980)
    private val rota2 = Rota(listOf(viagemA, viagemB))
    private fun trecho(i: Int, v: Viagem, poltronas: List<Int> = emptyList()) = PedidoTrecho(i, v.id, v.empresa, v.categoria, v.origem, v.destino, 1_800_000_000L + i * 1000, 1_800_000_500L + i * 1000, v.precoCentavos, poltronas, 4)
    private val adulto = PedidoPassageiro("Ana Adulta", "adulto", 30)
    private fun carrinho(poltronas: List<Int> = emptyList(), status: String = StatusPedido.CARRINHO, bilhetes: List<Bilhete> = emptyList()) =
        Pedido(7, "EV-4807", status, listOf(trecho(1, viagemA, poltronas), trecho(2, viagemB, poltronas)), listOf(adulto), Totais(23960, 0, 0, 23960), reservaExpiraEm = 1_800_000_900L, bilhetes = bilhetes)

    private var statusPerfil = 200
    private var respostaPedido: () -> Pair<Int, String> = { 201 to json.encodeToString(carrinho()) }
    private var respostaCartao: () -> Pair<Int, String> = { 201 to json.encodeToString(Pagamento(1, 7, "cartao", StatusPagamento.APROVADO)) }
    private var respostaPagamento: () -> Pair<Int, String> = { 200 to json.encodeToString(Pagamento(1, 7, "pix", StatusPagamento.APROVADO)) }
    private var pedidoPago = carrinho(listOf(11, 12), StatusPedido.PAGO, listOf(Bilhete("EVB1", 1, "Ana Adulta", 11, 4)))

    private fun responde(ex: HttpExchange, status: Int, corpo: String) {
        val b = corpo.toByteArray(); ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(status, b.size.toLong()); ex.responseBody.use { it.write(b) }
    }

    private fun subir(): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fun rota(caminho: String, f: (HttpExchange, String) -> Unit) = s.createContext(caminho) { ex -> val c = ex.requestBody.readBytes().decodeToString(); pedidos += "${ex.requestMethod} ${ex.requestURI.path} $c"; f(ex, c) }
        rota("/api/cidades") { ex, _ -> responde(ex, 200, "[]") }
        for (p in listOf("/api/parametros", "/api/parametros/risco", "/api/parametros/planob")) rota(p) { ex, _ -> responde(ex, 200, "{}") }
        rota("/api/perfil") { ex, _ -> if (statusPerfil == 200) responde(ex, 200, """{"id":7,"nome":"Maria","email":"maria@email.com"}""") else responde(ex, statusPerfil, """{"mensagem":"x"}""") }
        rota("/api/formas-pagamento") { ex, _ -> responde(ex, if (ex.requestMethod == "POST") 201 else 200, if (ex.requestMethod == "POST") """{"id":1,"tipo":"cartao","rotulo":"Visa •••• 4242","padrao":true}""" else "[]") }
        rota("/api/passageiros") { ex, _ -> responde(ex, 200, json.encodeToString(listOf(Passageiro(1, "Ana Adulta", "Você", 30, "adulto"), Passageiro(2, "Davi", "Dependente", 8, "crianca")))) }
        rota("/api/pedidos") { ex, _ -> if (ex.requestMethod == "POST") respostaPedido().let { responde(ex, it.first, it.second) } else responde(ex, 200, json.encodeToString(listOf(pedidoPago))) }
        rota("/api/pedidos/7") { ex, _ -> responde(ex, 200, json.encodeToString(pedidoPago)) }
        rota("/api/pedidos/7/poltronas") { ex, _ -> responde(ex, 200, json.encodeToString(carrinho(listOf(11, 12)))) }
        rota("/api/pedidos/7/pagamento/cartao") { ex, _ -> respostaCartao().let { responde(ex, it.first, it.second) } }
        rota("/api/pedidos/7/pagamento/pix") { ex, _ -> responde(ex, 201, json.encodeToString(Pagamento(2, 7, "pix", StatusPagamento.PENDENTE, "EVSIM123", 1_800_000_600L))) }
        rota("/api/pagamentos/2") { ex, _ -> respostaPagamento().let { responde(ex, it.first, it.second) } }
        rota("/api/pedidos/7/cancelamento") { ex, _ -> responde(ex, 200, json.encodeToString(CancelamentoPrevia(23960, 1198, 22762, 5))) }
        rota("/api/pedidos/7/cancelar") { ex, _ -> responde(ex, 200, json.encodeToString(pedidoPago.copy(status = StatusPedido.CANCELADO, reembolsoCentavos = 22762, bilhetes = emptyList()))) }
        rota("/api/notificacoes") { ex, _ -> responde(ex, 200, json.encodeToString(listOf(Notificacao(1, "compra", "Compra confirmada", "ok", 7, 1_800_000_000L)))) }
        s.start(); servidor = s
        return "http://127.0.0.1:${s.address.port}"
    }

    @AfterTest fun parar() { servidor?.stop(0) }

    private fun esperar(cond: () -> Boolean) {
        val limite = System.currentTimeMillis() + 8_000
        while (!cond()) { check(System.currentTimeMillis() < limite) { "tempo esgotado" }; Thread.sleep(20) }
    }

    private fun estado(logado: Boolean = true, arm: ArmazenamentoEmMemoria = armazenamento): Estado {
        armazenamento = arm
        if (logado) SessaoSalva(arm).guardar(token)
        val e = Estado(Api(subir()), CoroutineScope(Dispatchers.Default), arm).also { it.iniciar() }
        esperar { e.sessaoVerificada }
        return e
    }

    private fun chamadas(m: String, caminho: String) = pedidos.filter { it.startsWith("$m $caminho ") }

    @Test fun semLoginComprarLevaAoLoginEDepoisRetomaOCarrinho() {
        val e = estado(logado = false)
        e.iniciarCompra(rota2)
        assertEquals(Tela.ENTRAR, e.tela); assertTrue(e.compraPendente); assertEquals("Entre na sua conta para comprar.", e.contaAviso)
    }

    @Test fun logadoComprarAbreOCarrinhoLimpo() {
        val e = estado(); e.paxSelecionados.add(99)
        e.iniciarCompra(rota2)
        assertEquals(Tela.CARRINHO, e.tela); assertTrue(e.paxSelecionados.isEmpty()); assertNull(e.pedido)
    }

    @Test fun semPassageiroNaoCalculaENemChamaOServidor() {
        val e = estado(); e.iniciarCompra(rota2); e.calcularPedido()
        assertNotNull(e.compraErro); assertTrue(chamadas("POST", "/api/pedidos").isEmpty())
    }

    @Test fun oAppEnviaSoOsIdsNuncaPrecos() {
        val e = estado(); e.iniciarCompra(rota2); e.alternarPassageiro(1)
        e.calcularPedido(); esperar { e.pedido != null }
        val corpo = chamadas("POST", "/api/pedidos").single()
        assertTrue("\"viagemIds\":[101,102]" in corpo && "\"passageiroIds\":[1]" in corpo, corpo)
        assertFalse("8980" in corpo || "preco" in corpo.lowercase(), "o preço é do servidor: $corpo")
        assertEquals(23960, e.pedido!!.totais.totalCentavos)
    }

    @Test fun mudarQuemViajaInvalidaOTotalELimitaSeis() {
        val e = estado(); e.iniciarCompra(rota2); e.alternarPassageiro(1); e.calcularPedido(); esperar { e.pedido != null }
        e.alternarPassageiro(2); assertNull(e.pedido, "o total muda com quem viaja")
        (3..20).forEach { e.alternarPassageiro(it) }
        assertEquals(6, e.paxSelecionados.size, "no máximo 6 passageiros")
        e.alternarPassageiro(1); assertFalse(1 in e.paxSelecionados, "desmarcar funciona")
    }

    @Test fun erroDoServidorAoCalcularApareceEMantemOCarrinhoVazio() {
        respostaPedido = { 409 to """{"mensagem":"Essa viagem já saiu."}""" }
        val e = estado(); e.iniciarCompra(rota2); e.alternarPassageiro(1); e.calcularPedido(); esperar { e.compraErro != null }
        assertEquals("Essa viagem já saiu.", e.compraErro); assertNull(e.pedido)
    }

    @Test fun poltronasPedemAQuantidadeCertaPorTrechoESoNoUltimoVaoAoServidor() {
        val e = estado(); e.iniciarCompra(rota2); e.alternarPassageiro(1); e.calcularPedido(); esperar { e.pedido != null }
        e.abrirPoltronas(); assertEquals(1, e.poltronasPorTrecho)
        e.avancarPoltronas(); assertNotNull(e.compraErro, "sem escolher não avança")
        e.alternarPoltrona(11); e.alternarPoltrona(12); assertEquals(listOf(11), e.escolhas[101], "só 1 poltrona por passageiro")
        e.avancarPoltronas(); assertEquals(1, e.trechoDePoltronas); assertTrue(chamadas("PUT", "/api/pedidos/7/poltronas").isEmpty(), "não reserva antes do último trecho")
        e.alternarPoltrona(20); e.avancarPoltronas(); esperar { e.tela == Tela.PAGAMENTO }
        val corpo = chamadas("PUT", "/api/pedidos/7/poltronas").single()
        assertTrue("\"viagemId\":101" in corpo && "\"viagemId\":102" in corpo && "11" in corpo && "20" in corpo, corpo)
    }

    @Test fun cartaoNaoMandaONumeroEAprovadoVaiParaOResultado() {
        val e = estado(); e.iniciarCompra(rota2); e.alternarPassageiro(1); e.calcularPedido(); esperar { e.pedido != null }
        e.pagarComCartao("4242", "Visa", 2, true, "08/29"); esperar { e.tela == Tela.RESULTADO }
        val corpo = chamadas("POST", "/api/pedidos/7/pagamento/cartao").single().substringAfter("/cartao ")
        assertEquals("""{"ultimos4":"4242","bandeira":"Visa","parcelas":2}""", corpo)
        assertEquals(StatusPedido.PAGO, e.pedido!!.status, "recarrega o pedido pago")
        esperar { chamadas("POST", "/api/formas-pagamento").isNotEmpty() }
        assertTrue("\"ultimos4\":\"4242\"" in chamadas("POST", "/api/formas-pagamento").single(), "salvou o cartão no perfil, só com os 4 dígitos")
    }

    @Test fun cartaoRecusadoVaiAoResultadoSemSalvarENemMarcarPago() {
        respostaCartao = { 201 to json.encodeToString(Pagamento(1, 7, "cartao", StatusPagamento.RECUSADO, motivo = "O cartão foi recusado pelo banco.")) }
        val e = estado(); e.iniciarCompra(rota2); e.alternarPassageiro(1); e.calcularPedido(); esperar { e.pedido != null }
        e.pagarComCartao("0002", "Visa", 1, true, "08/29"); esperar { e.tela == Tela.RESULTADO }
        assertEquals(StatusPagamento.RECUSADO, e.pagamento!!.status); assertEquals(StatusPedido.CARRINHO, e.pedido!!.status)
        assertTrue(chamadas("POST", "/api/formas-pagamento").isEmpty(), "cartão recusado não é salvo")
    }

    @Test fun pixPendenteDepoisAprovadoLevaAoResultado() {
        val e = estado(); e.iniciarCompra(rota2); e.alternarPassageiro(1); e.calcularPedido(); esperar { e.pedido != null }
        e.pagarComPix(); esperar { e.tela == Tela.PIX }
        assertEquals(StatusPagamento.PENDENTE, e.pagamento!!.status); assertEquals("EVSIM123", e.pagamento!!.codigoPix)
        e.consultarPagamento(); esperar { e.tela == Tela.RESULTADO }
        assertEquals(StatusPedido.PAGO, e.pedido!!.status)
    }

    @Test fun pixAindaPendenteNaoSaiDaTela() {
        respostaPagamento = { 200 to json.encodeToString(Pagamento(2, 7, "pix", StatusPagamento.PENDENTE, "EVSIM123", 1_800_000_600L)) }
        val e = estado(); e.iniciarCompra(rota2); e.alternarPassageiro(1); e.calcularPedido(); esperar { e.pedido != null }
        e.pagarComPix(); esperar { e.tela == Tela.PIX }
        e.consultarPagamento(); Thread.sleep(400)
        assertEquals(Tela.PIX, e.tela)
    }

    @Test fun sessaoVencidaNaCompraVoltaAoLoginELimpaOCarrinho() {
        val e = estado(); e.iniciarCompra(rota2); e.alternarPassageiro(1); e.calcularPedido(); esperar { e.pedido != null }
        respostaCartao = { 401 to """{"mensagem":"Entre"}""" }
        e.pagarComCartao("4242", "Visa", 1, false, "08/29"); esperar { e.tela == Tela.ENTRAR }
        assertNull(e.usuario); assertNull(e.pedido); assertNull(e.compraRota)
    }

    @Test fun guardarBilhetesPersisteESobreviveAReabrirOApp() {
        val arm = ArmazenamentoEmMemoria()
        val e = estado(arm = arm); e.guardarBilhetes(pedidoPago)
        assertTrue(e.estaGuardado(pedidoPago))
        val e2 = Estado(Api("http://127.0.0.1:1"), CoroutineScope(Dispatchers.Default), arm)
        assertEquals(listOf(7), e2.bilhetesGuardados.map { it.id }, "voltou do armazenamento")
        assertEquals("EVB1", e2.bilhetesGuardados.single().bilhetes.single().codigo)
    }

    @Test fun sairApagaCarrinhoHistoricoAvisosEBilhetesGuardados() {
        val arm = ArmazenamentoEmMemoria(); val e = estado(arm = arm)
        e.iniciarCompra(rota2); e.alternarPassageiro(1); e.calcularPedido(); esperar { e.pedido != null }
        e.carregarHistorico(); esperar { e.historico.isNotEmpty() }; e.carregarAvisos(); esperar { e.avisos.isNotEmpty() }; e.guardarBilhetes(pedidoPago)
        e.sair()
        assertTrue(e.historico.isEmpty() && e.avisos.isEmpty() && e.bilhetesGuardados.isEmpty()); assertNull(e.pedido); assertNull(e.compraRota)
        assertNull(arm.ler("bilhetes_v1"), "nada de bilhete sobra no aparelho para a próxima pessoa")
    }

    @Test fun cancelarMostraAPreviaConfirmaERemoveOBilheteGuardado() {
        val e = estado(); e.guardarBilhetes(pedidoPago)
        e.abrirCancelamento(pedidoPago); esperar { e.previa != null }
        assertEquals(22762, e.previa!!.reembolsoCentavos)
        e.confirmarCancelamento("Mudei de plano"); esperar { e.tela == Tela.COMPRA_DETALHE }
        assertEquals(StatusPedido.CANCELADO, e.pedidoAberto!!.status); assertFalse(e.estaGuardado(pedidoPago), "bilhete cancelado não fica guardado")
        assertTrue(chamadas("POST", "/api/pedidos/7/cancelar").single().contains("Mudei de plano"))
    }

    @Test fun avisosContamOsNaoLidos() {
        val e = estado(); e.carregarAvisos(); esperar { e.avisos.isNotEmpty() }
        assertEquals(1, e.avisosNaoLidos)
    }

    @Test fun historicoUsaOFiltroNoServidor() {
        val e = estado(); e.carregarHistorico("proximas"); esperar { e.historico.isNotEmpty() }
        assertEquals("proximas", e.filtroHistorico)
        assertTrue(pedidos.any { it.startsWith("GET /api/pedidos ") })
    }
}
