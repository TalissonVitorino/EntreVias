package br.com.talisson.entrevias

import br.com.talisson.entrevias.domain.*
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Comprovante no app: tela, texto e link do PDF, contra um servidor de mentira (JDK). */
class ComprovanteEstadoTest {
    private val token = "tokenDeTeste0123456789-abcdefghij_KLMN"
    private var servidor: HttpServer? = null
    private var base = ""
    private val pedidos = CopyOnWriteArrayList<String>()

    private var comprovanteStatus = 200
    private var linkStatus = 200

    private val doc = """{"codigo":"EV-4801","secoes":[{"titulo":"Compra","linhas":[{"rotulo":"Código","valor":"EV-4801"}]},{"titulo":"Valores","linhas":[{"rotulo":"Total pago","valor":"R${'$'} 98,90"}]}],"aviso":"Comprovante demonstrativo, sem valor fiscal."}"""
    private val pedido = Pedido(5, "EV-4801", StatusPedido.PAGO, emptyList(), emptyList(), Totais(9890, 0, 0, 9890))

    private fun responde(ex: HttpExchange, status: Int, corpo: String) {
        val b = corpo.toByteArray(); ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(status, b.size.toLong()); ex.responseBody.use { it.write(b) }
    }

    private fun subir(): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fun rota(caminho: String, tratar: (HttpExchange) -> Unit) = s.createContext(caminho) { ex -> pedidos += "${ex.requestMethod} ${ex.requestURI}"; ex.requestBody.readBytes(); tratar(ex) }
        rota("/api/cidades") { responde(it, 200, "[]") }
        rota("/api/parametros") { responde(it, 200, "{}") }
        rota("/api/parametros/risco") { responde(it, 200, "{}") }
        rota("/api/parametros/planob") { responde(it, 200, "{}") }
        rota("/api/perfil") { responde(it, 200, """{"id":1,"nome":"Davi","email":"d@email.com","papel":"viajante"}""") }
        rota("/api/pedidos/5/comprovante") { ex ->
            if (ex.requestURI.path.endsWith("/link")) {
                if (linkStatus == 200) responde(ex, 200, """{"caminho":"/comprovante/abc123.pdf","validoPorSeg":300}""") else responde(ex, linkStatus, """{"mensagem":"Muitas tentativas."}""")
            } else if (comprovanteStatus == 200) responde(ex, 200, doc) else responde(ex, comprovanteStatus, """{"mensagem":"Sessão inválida."}""")
        }
        s.start(); servidor = s; base = "http://127.0.0.1:${s.address.port}"
        return base
    }

    @AfterTest fun parar() { servidor?.stop(0) }

    private fun esperar(cond: () -> Boolean) {
        val limite = System.currentTimeMillis() + 8_000
        while (!cond()) { check(System.currentTimeMillis() < limite) { "tempo esgotado esperando o app" }; Thread.sleep(20) }
    }

    private fun abrir(): Estado {
        val arm = ArmazenamentoEmMemoria().also { SessaoSalva(it).guardar(token) }
        val e = Estado(Api(subir()), CoroutineScope(Dispatchers.Default), arm).also { it.iniciar() }
        esperar { e.sessaoVerificada && e.usuario != null }
        return e
    }

    @Test fun abrirCarregaOComprovanteEVaiParaATela() {
        val e = abrir(); e.abrirComprovante(pedido)
        assertEquals(Tela.COMPROVANTE, e.tela)
        esperar { e.comprovanteCarga == Carga.Pronto && e.comprovante != null }
        assertEquals("EV-4801", e.comprovante!!.codigo)
        val texto = e.comprovante!!.texto()
        assertTrue("Comprovante de compra EV-4801" in texto && "Total pago: R$ 98,90" in texto && "COMPRA" in texto, texto)
    }

    @Test fun erroDoServidorApareceENaoTravaATela() {
        comprovanteStatus = 409
        val e = abrir(); e.abrirComprovante(pedido)
        esperar { e.comprovanteCarga is Carga.Erro }
        assertNull(e.comprovante); assertEquals(Tela.COMPROVANTE, e.tela)
    }

    @Test fun sessaoRecusadaDeslogaEVaiParaEntrar() {
        comprovanteStatus = 401
        val e = abrir(); e.abrirComprovante(pedido)
        esperar { e.usuario == null }
        assertEquals(Tela.ENTRAR, e.tela); assertNull(e.comprovante)
    }

    @Test fun pdfPedeOLinkEMontaOEnderecoCompleto() {
        val e = abrir(); e.abrirComprovante(pedido); esperar { e.comprovante != null }
        e.pedirPdfDoComprovante(); esperar { e.comprovanteLink != null }
        assertEquals("$base/comprovante/abc123.pdf", e.comprovanteLink)
        assertEquals("$base/comprovante/abc123.pdf", e.consumirLinkDoComprovante(), "entrega o link uma vez")
        assertNull(e.consumirLinkDoComprovante(), "e só uma: não abre o navegador de novo sozinho")
    }

    @Test fun excessoDeLinksMostraAvisoSemLink() {
        linkStatus = 429
        val e = abrir(); e.abrirComprovante(pedido); esperar { e.comprovante != null }
        e.pedirPdfDoComprovante(); esperar { e.comprovanteErro != null }
        assertTrue(e.comprovanteErro!!.contains("Muitos pedidos")); assertNull(e.comprovanteLink); assertEquals(false, e.comprovanteOcupado)
    }

    @Test fun semLoginNaoChamaNada() {
        val e = Estado(Api(subir()), CoroutineScope(Dispatchers.Default), ArmazenamentoEmMemoria()).also { it.iniciar() }
        esperar { e.sessaoVerificada }
        e.abrirComprovante(pedido); e.pedirPdfDoComprovante(); Thread.sleep(300)
        assertTrue(pedidos.none { it.contains("comprovante") })
    }
}
