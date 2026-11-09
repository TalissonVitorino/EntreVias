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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A5 e A6 no app, contra um servidor de mentira (JDK). */
class CarteiraEstadoTest {
    private val token = "tokenDeTeste0123456789-abcdefghij_KLMN"
    private var servidor: HttpServer? = null
    private val pedidos = CopyOnWriteArrayList<String>()
    private var formasJson = "[]"
    private var statusDaForma = 201
    private var perfilStatus = 200

    private fun responde(ex: HttpExchange, status: Int, corpo: String) {
        val b = corpo.toByteArray(); ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(status, b.size.toLong()); ex.responseBody.use { it.write(b) }
    }

    private fun subir(): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fun rota(caminho: String, f: (HttpExchange, String) -> Unit) = s.createContext(caminho) { ex -> val c = ex.requestBody.readBytes().decodeToString(); pedidos += "${ex.requestMethod} ${ex.requestURI.path} $c"; f(ex, c) }
        rota("/api/cidades") { ex, _ -> responde(ex, 200, "[]") }
        for (p in listOf("/api/parametros", "/api/parametros/risco", "/api/parametros/planob")) rota(p) { ex, _ -> responde(ex, 200, "{}") }
        rota("/api/perfil") { ex, _ -> if (perfilStatus == 200) responde(ex, 200, """{"id":7,"nome":"Maria","email":"maria@email.com"}""") else responde(ex, perfilStatus, """{"mensagem":"Entre na sua conta para continuar."}""") }
        rota("/api/formas-pagamento") { ex, _ ->
            if (ex.requestMethod == "POST") responde(ex, statusDaForma, if (statusDaForma == 201) """{"id":1,"tipo":"cartao","rotulo":"Visa •••• 4242","bandeira":"Visa","ultimos4":"4242","validade":"08/29","padrao":true}""" else """{"mensagem":"Você já tem 5 formas de pagamento."}""")
            else responde(ex, 200, formasJson)
        }
        rota("/api/passageiros") { ex, _ -> responde(ex, if (ex.requestMethod == "POST") 201 else 200, if (ex.requestMethod == "POST") """{"id":1,"nome":"Davi","relacao":"Dependente","idade":8,"categoria":"crianca"}""" else "[]") }
        s.start(); servidor = s
        return "http://127.0.0.1:${s.address.port}"
    }

    @AfterTest fun parar() { servidor?.stop(0) }

    private fun esperar(cond: () -> Boolean) {
        val limite = System.currentTimeMillis() + 8_000
        while (!cond()) { check(System.currentTimeMillis() < limite) { "tempo esgotado" }; Thread.sleep(20) }
    }

    private fun logado(): Estado {
        val arm = ArmazenamentoEmMemoria().also { SessaoSalva(it).guardar(token) }
        val e = Estado(Api(subir()), CoroutineScope(Dispatchers.Default), arm).also { it.iniciar() }
        esperar { e.sessaoVerificada && e.usuario != null }
        return e
    }

    private fun chamadas(m: String, caminho: String) = pedidos.filter { it.startsWith("$m $caminho ") }

    @Test fun numeroInvalidoNaoChamaOServidor() {
        val e = logado()
        e.adicionarCartao("4242 4242 4242 4243", "08/29", false)
        assertNotNull(e.carteiraErro); assertTrue(chamadas("POST", "/api/formas-pagamento").isEmpty())
    }

    @Test fun cartaoVencidoOuValidadeRuimNaoChamaOServidor() {
        val e = logado()
        e.adicionarCartao("4242 4242 4242 4242", "01/20", false); assertTrue("vencido" in e.carteiraErro!!)
        e.limparMensagensDaCarteira(); e.adicionarCartao("4242 4242 4242 4242", "13/29", false); assertNotNull(e.carteiraErro)
        assertTrue(chamadas("POST", "/api/formas-pagamento").isEmpty())
    }

    @Test fun oNumeroCompletoNuncaSaiDoAparelho() {
        val e = logado()
        e.adicionarCartao("4242 4242 4242 4242", "08/29", true); esperar { chamadas("POST", "/api/formas-pagamento").isNotEmpty() }
        val corpo = chamadas("POST", "/api/formas-pagamento").single()
        assertTrue("\"ultimos4\":\"4242\"" in corpo && "\"bandeira\":\"Visa\"" in corpo && "\"validade\":\"08/29\"" in corpo, corpo)
        assertFalse("4242424242424242" in corpo || "4242 4242" in corpo, "o número inteiro não pode ir no corpo: $corpo")
        assertFalse("cvv" in corpo.lowercase())
    }

    @Test fun pixSoMandaOFinalDaChave() {
        val e = logado()
        e.adicionarPix("CPF", "321", false); esperar { chamadas("POST", "/api/formas-pagamento").isNotEmpty() }
        val corpo = chamadas("POST", "/api/formas-pagamento").single()
        assertTrue("\"pixFinal\":\"321\"" in corpo)
        e.limparMensagensDaCarteira(); e.adicionarPix("CPF", "123456", false); assertNotNull(e.carteiraErro, "chave inteira é recusada antes da rede")
    }

    @Test fun limiteDoServidorApareceParaAPessoa() {
        statusDaForma = 409
        val e = logado(); e.adicionarCartao("4242 4242 4242 4242", "08/29", false)
        esperar { e.carteiraErro != null }; assertTrue("5 formas" in e.carteiraErro!!)
    }

    @Test fun sessaoVencidaNaCarteiraVoltaParaOLogin() {
        val e = logado(); perfilStatus = 401
        // o servidor de mentira passa a recusar o token em tudo que for conta
        servidor!!.removeContext("/api/formas-pagamento"); servidor!!.createContext("/api/formas-pagamento") { ex -> responde(ex, 401, """{"mensagem":"Entre na sua conta para continuar."}""") }
        e.adicionarCartao("4242 4242 4242 4242", "08/29", false)
        esperar { e.tela == Tela.ENTRAR }
        assertEquals(null, e.usuario)
    }

    @Test fun carregarTrazAsFormasDoServidor() {
        formasJson = """[{"id":1,"tipo":"cartao","rotulo":"Visa •••• 4242","bandeira":"Visa","ultimos4":"4242","validade":"08/29","padrao":true}]"""
        val e = logado(); e.carregarCarteira(); esperar { e.formas.size == 1 }
        assertEquals("Visa •••• 4242", e.formas.single().rotulo)
    }

    @Test fun passageiroInvalidoNemChamaOServidorEValidoSalva() {
        val e = logado(); var msg: String? = "x"
        e.salvarPassageiro(null, br.com.talisson.entrevias.domain.PassageiroPedido("D", "Dependente", 8)) { msg = it }
        assertNotNull(msg); assertTrue(chamadas("POST", "/api/passageiros").isEmpty())
        var ok: String? = "pendente"
        e.salvarPassageiro(null, br.com.talisson.entrevias.domain.PassageiroPedido("Davi Lima", "Dependente", 8)) { ok = it }
        esperar { ok != "pendente" }; assertEquals(null, ok)
    }

    @Test fun sairLimpaAsListasDaCarteira() {
        formasJson = """[{"id":1,"tipo":"pix","rotulo":"Pix CPF final ••• 321","padrao":true}]"""
        val e = logado(); e.carregarCarteira(); esperar { e.formas.isNotEmpty() }
        e.sair(); assertTrue(e.formas.isEmpty() && e.passageiros.isEmpty(), "dados de uma pessoa não podem sobrar para a próxima")
    }
}
