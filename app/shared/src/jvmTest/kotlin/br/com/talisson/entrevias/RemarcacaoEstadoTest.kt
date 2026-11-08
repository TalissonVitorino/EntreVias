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
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Remarcação no app, contra um servidor de mentira (JDK). */
class RemarcacaoEstadoTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val token = "tokenDeTeste0123456789-abcdefghij_KLMN"
    private var servidor: HttpServer? = null
    private val pedidos = CopyOnWriteArrayList<String>()   // "METODO caminho corpo"

    private var remarcarStatus = 200
    private var alternativasStatus = 200
    private var alternativasJson = """[{"viagemId":9,"empresa":"Serra","categoria":"Leito","saidaEm":${AGORA + 86400},"chegadaEm":${AGORA + 86400 + 7200},"precoCentavos":4000}]"""

    private companion object { const val AGORA = 1_000_000L }

    private fun trecho(ordem: Int, saida: Long, cancelada: Boolean = false) =
        PedidoTrecho(ordem, 100 + ordem, "Costa", "Executivo", "CWB", "JVL", saida, saida + 7200, 5000, listOf(12), 3, cancelada = cancelada)
    private fun pedido(status: String = StatusPedido.PAGO, remarcadaEm: Long? = null, vararg trechos: PedidoTrecho = arrayOf(trecho(1, AGORA + 3 * 3600))) =
        Pedido(5, "EV-4801", status, trechos.toList(), listOf(PedidoPassageiro("Davi", "adulto", 30)), Totais(5000, 0, 0, 5000), agora = AGORA, remarcadaEm = remarcadaEm)

    private fun responde(ex: HttpExchange, status: Int, corpo: String) {
        val b = corpo.toByteArray(); ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(status, b.size.toLong()); ex.responseBody.use { it.write(b) }
    }

    private fun subir(): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fun rota(caminho: String, tratar: (HttpExchange, String) -> Unit) = s.createContext(caminho) { ex -> val corpo = ex.requestBody.readBytes().decodeToString(); pedidos += "${ex.requestMethod} ${ex.requestURI} $corpo"; tratar(ex, corpo) }
        rota("/api/cidades") { ex, _ -> responde(ex, 200, "[]") }
        rota("/api/parametros") { ex, _ -> responde(ex, 200, "{}") }
        rota("/api/parametros/risco") { ex, _ -> responde(ex, 200, "{}") }
        rota("/api/parametros/planob") { ex, _ -> responde(ex, 200, "{}") }
        rota("/api/perfil") { ex, _ -> responde(ex, 200, """{"id":1,"nome":"Davi","email":"d@email.com","papel":"viajante"}""") }
        rota("/api/pedidos") { ex, _ -> responde(ex, 200, "[]") }
        rota("/api/notificacoes") { ex, _ -> responde(ex, 200, "[]") }
        rota("/api/pedidos/5/trechos") { ex, _ ->
            if (ex.requestURI.path.endsWith("/poltronas")) responde(ex, 200, """{"viagemId":9,"total":40,"ocupadas":[2,3],"pcd":[1,5],"minhas":[]}""")
            else if (alternativasStatus == 200) responde(ex, 200, alternativasJson) else responde(ex, alternativasStatus, """{"mensagem":"Sessão inválida."}""")
        }
        rota("/api/pedidos/5/remarcar") { ex, _ ->
            if (remarcarStatus == 200) responde(ex, 200, json.encodeToString(pedido(remarcadaEm = AGORA, trechos = arrayOf(trecho(1, AGORA + 86400).copy(viagemId = 9, poltronas = listOf(12))))))
            else responde(ex, remarcarStatus, """{"mensagem":"Uma das poltronas acabou de ser ocupada. Escolha outra."}""")
        }
        rota("/api/pedidos/5/cancelar") { ex, _ -> responde(ex, 200, json.encodeToString(pedido(StatusPedido.CANCELADO))) }
        s.start(); servidor = s
        return "http://127.0.0.1:${s.address.port}"
    }

    @AfterTest fun parar() { servidor?.stop(0) }

    private fun esperar(cond: () -> Boolean) {
        val limite = System.currentTimeMillis() + 8_000
        while (!cond()) { check(System.currentTimeMillis() < limite) { "tempo esgotado esperando o app" }; Thread.sleep(20) }
    }

    private fun abrir(arm: ArmazenamentoLocal = ArmazenamentoEmMemoria()): Estado {
        SessaoSalva(arm).guardar(token)
        val e = Estado(Api(subir()), CoroutineScope(Dispatchers.Default), arm).also { it.iniciar() }
        esperar { e.sessaoVerificada && e.usuario != null }
        return e
    }

    private fun chamadas(trecho: String) = pedidos.filter { it.contains(trecho) }

    // ---------- quando dá para remarcar (conta pura, pelo relógio do servidor) ----------

    @Test fun regraDeQuandoPodeRemarcar() {
        val e = Estado(Api("http://127.0.0.1:1"), CoroutineScope(Dispatchers.Unconfined))
        assertTrue(e.podeRemarcar(pedido()))
        assertFalse(e.podeRemarcar(pedido(remarcadaEm = 1)), "só uma vez")
        assertFalse(e.podeRemarcar(pedido(StatusPedido.CANCELADO)), "só compra paga")
        assertFalse(e.podeRemarcar(pedido(StatusPedido.CARRINHO)))
        assertFalse(e.podeRemarcar(pedido(trechos = arrayOf(trecho(1, AGORA + 2 * 3600 - 1)))), "menos de 2 h")
        assertTrue(e.podeRemarcar(pedido(trechos = arrayOf(trecho(1, AGORA + 2 * 3600)))), "exatamente 2 h ainda vale")
        assertFalse(e.podeRemarcar(pedido(trechos = arrayOf(trecho(1, AGORA + 3 * 3600, cancelada = true)))), "trecho cancelado pela empresa")
        val dois = pedido(trechos = arrayOf(trecho(1, AGORA - 3600), trecho(2, AGORA + 5 * 3600)))
        assertEquals(listOf(2), e.trechosRemarcaveis(dois).map { it.ordem }, "o trecho que já saiu não entra")
    }

    // ---------- fluxo ----------

    @Test fun abrirCarregaAlternativasDoPrimeiroTrechoRemarcavel() {
        val e = abrir(); e.abrirRemarcacao(pedido())
        assertEquals(Tela.REMARCAR, e.tela); assertEquals(1, e.remarcarTrecho)
        esperar { e.alternativasCarga == Carga.Pronto && e.alternativas.isNotEmpty() }
        assertEquals(9, e.alternativas.single().viagemId)
        assertTrue(chamadas("GET /api/pedidos/5/trechos/1/alternativas").isNotEmpty())
    }

    @Test fun escolherAlternativaCarregaOMapaEPoltronasRespeitamOLimite() {
        val e = abrir(); e.abrirRemarcacao(pedido()); esperar { e.alternativas.isNotEmpty() }
        e.escolherAlternativa(e.alternativas.single()); esperar { e.remarcarMapa != null }
        assertEquals(listOf(2, 3), e.remarcarMapa!!.ocupadas)
        e.alternarPoltronaDaRemarcacao(12); e.alternarPoltronaDaRemarcacao(13)
        assertEquals(listOf(12), e.remarcarPoltronas, "uma só poltrona: a segunda é ignorada")
        e.alternarPoltronaDaRemarcacao(12); assertEquals(emptyList(), e.remarcarPoltronas, "tocar de novo desmarca")
    }

    @Test fun confirmarEnviaTrechoViagemEPoltronasETrocaOPedidoAberto() {
        val e = abrir(); val p = pedido(); e.abrirRemarcacao(p); esperar { e.alternativas.isNotEmpty() }
        e.escolherAlternativa(e.alternativas.single()); esperar { e.remarcarMapa != null }
        e.alternarPoltronaDaRemarcacao(12); e.confirmarRemarcacao()
        esperar { e.tela == Tela.COMPRA_DETALHE }
        val corpo = chamadas("POST /api/pedidos/5/remarcar").single()
        assertTrue("\"trecho\":1" in corpo && "\"viagemId\":9" in corpo && "12" in corpo, corpo)
        assertEquals(9, e.pedidoAberto!!.trechos.single().viagemId); assertEquals(AGORA, e.pedidoAberto!!.remarcadaEm)
        assertTrue(e.compraAviso!!.contains("remarcada")); assertFalse(e.podeRemarcar(e.pedidoAberto!!), "depois de remarcar não se remarca de novo")
    }

    @Test fun semAsPoltronasCertasNemChamaOServidor() {
        val e = abrir(); e.abrirRemarcacao(pedido()); esperar { e.alternativas.isNotEmpty() }
        e.escolherAlternativa(e.alternativas.single()); esperar { e.remarcarMapa != null }
        e.confirmarRemarcacao(); Thread.sleep(250)
        assertNotNull(e.compraErro); assertTrue(chamadas("/remarcar").isEmpty()); assertEquals(Tela.REMARCAR, e.tela)
    }

    @Test fun poltronaOcupadaMostraOErroLimpaAEscolhaERecarregaOMapa() {
        remarcarStatus = 409
        val e = abrir(); e.abrirRemarcacao(pedido()); esperar { e.alternativas.isNotEmpty() }
        e.escolherAlternativa(e.alternativas.single()); esperar { e.remarcarMapa != null }
        e.alternarPoltronaDaRemarcacao(12); e.confirmarRemarcacao(); esperar { e.compraErro != null }
        assertTrue(e.compraErro!!.contains("poltronas acabou")); assertEquals(Tela.REMARCAR, e.tela); assertEquals(emptyList(), e.remarcarPoltronas)
        esperar { chamadas("alternativas/9/poltronas").size >= 2 }
    }

    @Test fun sessaoRecusadaNasAlternativasDeslogaEVaiParaEntrar() {
        alternativasStatus = 401
        val e = abrir(); e.abrirRemarcacao(pedido()); esperar { e.usuario == null }
        assertEquals(Tela.ENTRAR, e.tela)
    }

    @Test fun compraSemTrechoRemarcavelNaoPedeAlternativas() {
        val e = abrir(); e.abrirRemarcacao(pedido(trechos = arrayOf(trecho(1, AGORA + 3600))))
        Thread.sleep(250)
        assertNull(e.remarcarTrecho); assertTrue(chamadas("alternativas").isEmpty())
    }

    // ---------- bilhetes guardados no aparelho ----------

    @Test fun remarcarTrocaOBilheteGuardadoPeloNovo() {
        val arm = ArmazenamentoEmMemoria(); val e = abrir(arm); val p = pedido()
        e.guardarBilhetes(p); e.abrirRemarcacao(p); esperar { e.alternativas.isNotEmpty() }
        e.escolherAlternativa(e.alternativas.single()); esperar { e.remarcarMapa != null }
        e.alternarPoltronaDaRemarcacao(12); e.confirmarRemarcacao(); esperar { e.tela == Tela.COMPRA_DETALHE }
        assertEquals(9, e.bilhetesGuardados.single().trechos.single().viagemId, "o guardado é o novo, não o antigo")
        val reaberto = Estado(Api("http://127.0.0.1:1"), CoroutineScope(Dispatchers.Unconfined), arm)
        assertEquals(9, reaberto.bilhetesGuardados.single().trechos.single().viagemId, "e continua assim depois de reabrir o app")
    }

    @Test fun cancelarApagaOBilheteGuardadoTambemDoArmazenamento() {
        val arm = ArmazenamentoEmMemoria(); val e = abrir(arm); val p = pedido()
        e.guardarBilhetes(p); e.abrirCancelamento(p); e.confirmarCancelamento(null); esperar { e.pedidoAberto?.status == StatusPedido.CANCELADO }
        assertTrue(e.bilhetesGuardados.isEmpty())
        val reaberto = Estado(Api("http://127.0.0.1:1"), CoroutineScope(Dispatchers.Unconfined), arm)
        assertTrue(reaberto.bilhetesGuardados.isEmpty(), "reabrir o app não ressuscita o bilhete de uma compra cancelada")
    }
}
