package br.com.talisson.entrevias

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

/** Área de gestão no app (D4, F1, F2), contra um servidor de mentira (JDK). */
class GestaoEstadoTest {
    private val token = "tokenDeTeste0123456789-abcdefghij_KLMN"
    private var servidor: HttpServer? = null
    private val pedidos = CopyOnWriteArrayList<String>()   // "METODO caminho?consulta corpo"

    private var papel = "gestor"
    private var ocorrenciaStatus = 200
    private var viagensStatus = 200

    private val viagem = """{"viagem":{"id":7,"empresa":"Serra","categoria":"Leito","origem":"CWB","destino":"JVL","saidaMin":480,"chegadaMin":600,"precoCentavos":5000},"atrasoMin":0,"cancelada":false,"comprasPagas":2}"""
    private val chamado = """{"id":3,"protocolo":"AJ-10003","assunto":"Reembolso","mensagem":"Cadê?","status":"aberto","criadoEm":1}"""

    private fun responde(ex: HttpExchange, status: Int, corpo: String) {
        val b = corpo.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(status, b.size.toLong()); ex.responseBody.use { it.write(b) }
    }

    private fun subir(): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fun rota(caminho: String, tratar: (HttpExchange, String) -> Unit) = s.createContext(caminho) { ex ->
            val corpo = ex.requestBody.readBytes().decodeToString()
            pedidos += "${ex.requestMethod} ${ex.requestURI} $corpo"
            tratar(ex, corpo)
        }
        rota("/api/cidades") { ex, _ -> responde(ex, 200, "[]") }
        rota("/api/parametros") { ex, _ -> responde(ex, 200, "{}") }
        rota("/api/parametros/risco") { ex, _ -> responde(ex, 200, "{}") }
        rota("/api/parametros/planob") { ex, _ -> responde(ex, 200, "{}") }
        rota("/api/perfil") { ex, _ -> responde(ex, 200, """{"id":1,"nome":"Gil","email":"gil@email.com","papel":"$papel"}""") }
        rota("/api/gestor/viagens") { ex, _ ->
            if (ex.requestURI.path.endsWith("/ocorrencia")) {
                if (ocorrenciaStatus == 200) responde(ex, 200, """{"pedidosAfetados":2,"reembolsados":0}""") else responde(ex, ocorrenciaStatus, """{"mensagem":"Informe o atraso em minutos (de 1 a 600)."}""")
            } else if (viagensStatus == 200) responde(ex, 200, "[$viagem]") else responde(ex, viagensStatus, """{"mensagem":"Sessão inválida."}""")
        }
        rota("/api/gestor/chamados") { ex, _ ->
            if (ex.requestURI.path.endsWith("/resposta")) responde(ex, 200, """{"id":3,"protocolo":"AJ-10003","assunto":"Reembolso","status":"respondido","resposta":"Feito.","criadoEm":1}""")
            else responde(ex, 200, "[$chamado]")
        }
        rota("/api/gestor/reclamacoes") { ex, _ -> responde(ex, 200, """[{"id":1,"pedidoId":9,"trecho":1,"estrelas":1,"tags":["Atraso"],"reclamacao":true,"criadaEm":1}]""") }
        s.start(); servidor = s
        return "http://127.0.0.1:${s.address.port}"
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

    private fun chamadas(trecho: String) = pedidos.filter { it.contains(trecho) }

    @Test fun gestorCarregaViagensChamadosEReclamacoes() {
        val e = abrir(); e.gestorData = Data(2026, 11, 20)
        e.carregarGestao(); esperar { e.gestorCarga == Carga.Pronto && e.gestorReclamacoes.isNotEmpty() }
        assertEquals(1, e.gestorViagens.size); assertEquals(2, e.gestorViagens.single().comprasPagas)
        assertEquals("AJ-10003", e.gestorChamados.single().protocolo)
        assertTrue(chamadas("/api/gestor/viagens?data=2026-11-20").isNotEmpty(), "pede o dia escolhido")
    }

    @Test fun viajanteNaoChamaNadaDeGestao() {
        papel = "viajante"
        val e = abrir(); e.carregarGestao()
        Thread.sleep(300)
        assertTrue(chamadas("/api/gestor").isEmpty())
    }

    @Test fun mudarODiaPedeOutroDia() {
        val e = abrir(); e.gestorData = Data(2026, 11, 20)
        e.mudarDiaDaGestao(1); esperar { chamadas("data=2026-11-21").isNotEmpty() }
        assertEquals(Data(2026, 11, 21), e.gestorData)
    }

    @Test fun ocorrenciaEnviaTipoEMinutosEMostraQuantosForamAvisados() {
        val e = abrir(); var erro: String? = "pendente"
        e.registrarOcorrencia(7, "atraso", 25) { erro = it }; esperar { erro != "pendente" }
        assertNull(erro)
        val corpo = chamadas("POST /api/gestor/viagens/7/ocorrencia").single()
        assertTrue(corpo.contains("\"tipo\":\"atraso\"") && corpo.contains("25"), corpo)
        assertEquals("Registrado. 2 compras avisadas.", e.gestorAviso)
    }

    @Test fun ocorrenciaRecusadaDevolveOErroDoServidorENaoAvisa() {
        ocorrenciaStatus = 400
        val e = abrir(); var erro: String? = null
        e.registrarOcorrencia(7, "atraso", 0) { erro = it }; esperar { erro != null }
        assertEquals("Informe o atraso em minutos (de 1 a 600).", erro); assertNull(e.gestorAviso)
    }

    @Test fun respostaCurtaNemChamaOServidor() {
        val e = abrir(); var erro: String? = null
        e.responderChamado(3, " a ") { erro = it }
        assertNotNull(erro); Thread.sleep(200)
        assertTrue(chamadas("/resposta").isEmpty())
    }

    @Test fun respostaAtualizaOChamadoNaLista() {
        val e = abrir(); e.carregarGestao(); esperar { e.gestorChamados.isNotEmpty() }
        var pronto = false
        e.responderChamado(3, " Feito. ") { pronto = it == null }; esperar { pronto }
        assertEquals("respondido", e.gestorChamados.single().status); assertEquals("Feito.", e.gestorChamados.single().resposta)
        assertTrue(chamadas("/resposta").single().contains("\"texto\":\"Feito.\""), "o texto vai sem espaços nas pontas")
    }

    @Test fun sessaoRecusadaNaGestaoDeslogaEVaiParaEntrar() {
        viagensStatus = 401
        val e = abrir(); e.carregarGestao(); esperar { e.usuario == null }
        assertEquals(Tela.ENTRAR, e.tela); assertTrue(e.gestorViagens.isEmpty())
    }
}
