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

/** Recuperar senha (RQ9) e confirmar e-mail (RQ10) no app, contra um servidor de mentira (JDK). */
class EmailEstadoTest {
    private val token = "tokenDeTeste0123456789-abcdefghij_KLMN"
    private var servidor: HttpServer? = null
    private val pedidos = CopyOnWriteArrayList<String>()     // "METODO caminho corpo"

    // O que cada rota devolve; os testes mudam antes de agir.
    private var esqueciStatus = 202
    private var redefinirStatus = 200
    private var confirmarStatus = 200
    private var estadoJson = """{"simulado":true,"disponivel":true}"""
    private var retryAfter: String? = null

    private fun responde(ex: HttpExchange, status: Int, corpo: String) {
        val b = corpo.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        retryAfter?.let { ex.responseHeaders.add("Retry-After", it) }
        ex.sendResponseHeaders(status, b.size.toLong()); ex.responseBody.use { it.write(b) }
    }

    private fun subir(): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fun rota(caminho: String, tratar: (HttpExchange, String) -> Unit) = s.createContext(caminho) { ex ->
            val corpo = ex.requestBody.readBytes().decodeToString()
            pedidos += "${ex.requestMethod} $caminho $corpo"
            tratar(ex, corpo)
        }
        for (c in listOf("/api/cidades")) rota(c) { ex, _ -> responde(ex, 200, "[]") }
        rota("/api/parametros") { ex, _ -> responde(ex, 200, "{}") }
        rota("/api/parametros/risco") { ex, _ -> responde(ex, 200, "{}") }
        rota("/api/parametros/planob") { ex, _ -> responde(ex, 200, "{}") }
        rota("/api/perfil") { ex, _ -> responde(ex, 200, """{"id":7,"nome":"Maria","email":"maria@email.com","papel":"viajante"}""") }
        rota("/api/email/estado") { ex, _ -> responde(ex, 200, estadoJson) }
        rota("/api/senha/esqueci") { ex, _ ->
            if (esqueciStatus == 202) responde(ex, 202, """{"mensagem":"Se esse e-mail tiver conta, enviamos um código."}""") else responde(ex, esqueciStatus, """{"mensagem":"Muitas tentativas."}""")
        }
        rota("/api/senha/redefinir") { ex, _ ->
            if (redefinirStatus == 200) responde(ex, 200, """{"mensagem":"Senha alterada. Entre com a nova senha."}""") else responde(ex, redefinirStatus, """{"mensagem":"Código inválido ou vencido. Peça um novo."}""")
        }
        rota("/api/verificacao/enviar") { ex, _ -> responde(ex, 202, """{"mensagem":"Enviamos um código para o seu e-mail."}""") }
        rota("/api/verificacao/confirmar") { ex, _ ->
            if (confirmarStatus == 200) responde(ex, 200, """{"id":7,"nome":"Maria","email":"maria@email.com","papel":"viajante","emailVerificado":true}""")
            else responde(ex, confirmarStatus, """{"mensagem":"Código inválido ou vencido. Peça um novo."}""")
        }
        s.start(); servidor = s
        return "http://127.0.0.1:${s.address.port}"
    }

    @AfterTest fun parar() { servidor?.stop(0) }

    private fun esperar(cond: () -> Boolean) {
        val limite = System.currentTimeMillis() + 8_000
        while (!cond()) { check(System.currentTimeMillis() < limite) { "tempo esgotado esperando o app" }; Thread.sleep(20) }
    }

    private fun abrir(logado: Boolean = false): Estado {
        val arm = ArmazenamentoEmMemoria().also { if (logado) SessaoSalva(it).guardar(token) }
        val e = Estado(Api(subir()), CoroutineScope(Dispatchers.Default), arm).also { it.iniciar() }
        esperar { e.sessaoVerificada }
        return e
    }

    private fun chamadas(caminho: String) = pedidos.filter { it.contains(" $caminho ") }

    @Test fun avisaSeOEnvioEhSimuladoOuEstaDesligado() {
        val e = abrir()
        e.carregarEstadoEmail(); esperar { e.emailEstado != null }
        assertEquals(true, e.emailEstado?.simulado)
        assertEquals(true, e.emailEstado?.disponivel)
    }

    @Test fun estadoDesligadoChegaAoApp() {
        estadoJson = """{"simulado":true,"disponivel":false}"""
        val e = abrir(); e.carregarEstadoEmail(); esperar { e.emailEstado != null }
        assertEquals(false, e.emailEstado?.disponivel)
    }

    @Test fun pedirCodigoComEmailInvalidoNemChamaOServidor() {
        val e = abrir()
        e.pedirCodigoDeSenha("isso-nao-e-email")
        assertNotNull(e.contaErro); assertTrue(chamadas("/api/senha/esqueci").isEmpty())
        assertEquals(Tela.BUSCAR, e.tela)
    }

    @Test fun pedirCodigoVaiParaATelaDoCodigoComOAvisoDoServidor() {
        val e = abrir()
        e.pedirCodigoDeSenha("maria@email.com"); esperar { e.tela == Tela.REDEFINIR_SENHA }
        esperar { e.contaAviso != null }
        assertEquals("maria@email.com", e.recEmail)
        assertTrue(chamadas("/api/senha/esqueci").single().contains("maria@email.com"))
    }

    @Test fun pedidoRecusadoPorExcessoMostraOTempoEFicaNaTela() {
        esqueciStatus = 429; retryAfter = "1800"
        val e = abrir()
        e.pedirCodigoDeSenha("maria@email.com"); esperar { e.contaErro != null }
        assertTrue(e.contaErro!!.contains("30 minutos"), e.contaErro)
        assertNotEquals(Tela.REDEFINIR_SENHA, e.tela)
    }

    @Test fun trocarASenhaEnviaEmailCodigoESenhaEVoltaParaEntrarComAviso() {
        val e = abrir(); e.recEmail = "maria@email.com"
        e.redefinirSenha(" 123456 ", "senha-nova-22"); esperar { e.tela == Tela.ENTRAR }
        val corpo = chamadas("/api/senha/redefinir").single()
        assertTrue(corpo.contains("\"codigo\":\"123456\""), "o código vai sem espaços: $corpo")
        assertTrue(corpo.contains("maria@email.com") && corpo.contains("senha-nova-22"))
        esperar { e.contaAviso != null }
        assertNull(e.contaErro); assertEquals("", e.recEmail, "o e-mail da recuperação não fica guardado depois")
    }

    @Test fun codigoCurtoOuSenhaCurtaNemChamamOServidor() {
        val e = abrir(); e.recEmail = "maria@email.com"
        e.redefinirSenha("123", "senha-nova-22"); assertNotNull(e.contaErro)
        e.limparMensagensDeConta(); e.redefinirSenha("123456", "curta"); assertNotNull(e.contaErro)
        assertTrue(chamadas("/api/senha/redefinir").isEmpty())
    }

    @Test fun codigoRecusadoMostraOErroENaoSaiDaTela() {
        redefinirStatus = 400
        val e = abrir(); e.recEmail = "maria@email.com"; e.ir(Tela.REDEFINIR_SENHA)
        e.redefinirSenha("123456", "senha-nova-22"); esperar { e.contaErro != null }
        assertEquals(Tela.REDEFINIR_SENHA, e.tela); assertEquals("maria@email.com", e.recEmail)
    }

    @Test fun confirmarEmailMarcaAPessoaComoVerificada() {
        val e = abrir(logado = true); esperar { e.usuario != null }
        assertEquals(false, e.usuario?.emailVerificado)
        e.confirmarEmail("654321"); esperar { e.usuario?.emailVerificado == true }
        assertEquals(Tela.CONTA, e.tela)
        assertTrue(chamadas("/api/verificacao/confirmar").single().contains("654321"))
    }

    @Test fun codigoDeConfirmacaoRecusadoNaoMarcaNada() {
        confirmarStatus = 400
        val e = abrir(logado = true); esperar { e.usuario != null }
        e.confirmarEmail("654321"); esperar { e.contaErro != null }
        assertEquals(false, e.usuario?.emailVerificado)
    }

    @Test fun semLoginConfirmarNaoFazNada() {
        val e = abrir()
        e.confirmarEmail("654321")
        Thread.sleep(300)
        assertTrue(chamadas("/api/verificacao/confirmar").isEmpty())
    }

    @Test fun reenviarCodigoMostraOAvisoDoServidor() {
        val e = abrir(logado = true); esperar { e.usuario != null }
        e.reenviarVerificacao(); esperar { e.contaAviso != null }
        assertEquals(1, chamadas("/api/verificacao/enviar").size)
    }

    private fun assertNotEquals(a: Any?, b: Any?) = kotlin.test.assertNotEquals(a, b)
}
