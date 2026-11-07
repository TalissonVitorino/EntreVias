package br.com.talisson.entrevias

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Abrir o app com uma sessão guardada, contra um servidor de mentira (JDK) que responde o que cada caso precisa. */
class SessaoRestauradaTest {
    private val token = "tokenDeTeste0123456789-abcdefghij_KLMN"
    private var perfilStatus = 200
    private var servidor: HttpServer? = null
    private val chamadasPerfil = mutableListOf<String?>()

    private fun subir(): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fun responde(caminho: String, status: Int = 200, corpo: () -> String) = s.createContext(caminho) { ex ->
            val b = corpo().toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(status, if (status == 204) -1 else b.size.toLong())
            if (status != 204) ex.responseBody.use { it.write(b) } else ex.close()
        }
        responde("/api/cidades") { "[]" }; responde("/api/parametros") { "{}" }
        responde("/api/parametros/risco") { "{}" }; responde("/api/parametros/planob") { "{}" }
        s.createContext("/api/perfil") { ex ->
            chamadasPerfil += ex.requestHeaders.getFirst("Authorization")
            val b = if (perfilStatus == 200) """{"id":7,"nome":"Maria","email":"maria@email.com","papel":"viajante"}""" else """{"mensagem":"Entre na sua conta para continuar."}"""
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(perfilStatus, b.toByteArray().size.toLong()); ex.responseBody.use { it.write(b.toByteArray()) }
        }
        s.createContext("/api/login") { ex ->
            val b = """{"token":"$token","usuario":{"id":7,"nome":"Maria","email":"maria@email.com"}}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json"); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        s.createContext("/api/logout") { ex -> ex.sendResponseHeaders(204, -1); ex.close() }
        s.start(); servidor = s
        return "http://127.0.0.1:${s.address.port}"
    }

    @AfterTest fun parar() { servidor?.stop(0) }

    private fun abrirApp(arm: ArmazenamentoLocal, base: String = subir()): Estado =
        Estado(Api(base), CoroutineScope(Dispatchers.Default), arm).also { it.iniciar() }

    private fun esperar(cond: () -> Boolean) {
        val limite = System.currentTimeMillis() + 8_000
        while (!cond()) { check(System.currentTimeMillis() < limite) { "tempo esgotado esperando o app" }; Thread.sleep(20) }
    }

    @Test fun tokenGuardadoEValidoRestauraAContaAoAbrir() {
        val arm = ArmazenamentoEmMemoria().also { SessaoSalva(it).guardar(token) }
        val e = abrirApp(arm)
        esperar { e.sessaoVerificada }
        assertEquals("Maria", e.usuario?.nome)
        assertEquals<List<String?>>(listOf("Bearer $token"), chamadasPerfil.toList())
    }

    @Test fun tokenRecusadoEApagadoDoAparelho() {
        perfilStatus = 401
        val arm = ArmazenamentoEmMemoria().also { SessaoSalva(it).guardar(token) }
        val e = abrirApp(arm)
        esperar { e.sessaoVerificada }
        assertNull(e.usuario)
        assertNull(SessaoSalva(arm).token(), "token inválido não pode ficar guardado")
    }

    @Test fun servidorFora_doArMantemOTokenParaDepois() {
        val arm = ArmazenamentoEmMemoria().also { SessaoSalva(it).guardar(token) }
        val e = Estado(Api("http://127.0.0.1:1"), CoroutineScope(Dispatchers.Default), arm).also { it.iniciar() }
        Thread.sleep(600)
        assertNull(e.usuario)
        assertNotNull(SessaoSalva(arm).token(), "uma queda de rede não pode apagar a sessão")
    }

    @Test fun erroDoServidorNaVerificacaoNaoApagaOTokenSoOs401Apagam() {
        perfilStatus = 500
        val arm = ArmazenamentoEmMemoria().also { SessaoSalva(it).guardar(token) }
        val e = abrirApp(arm)
        esperar { e.sessaoVerificada }
        assertNull(e.usuario, "sem confirmação do servidor, segue como visitante")
        assertEquals(token, SessaoSalva(arm).token(), "um 500 não prova que a sessão acabou")
    }

    @Test fun semTokenGuardadoNaoPerguntaNada() {
        val e = abrirApp(ArmazenamentoEmMemoria())
        esperar { e.sessaoVerificada }
        assertNull(e.usuario); assertTrue(chamadasPerfil.isEmpty())
    }

    @Test fun entrarGuardaOTokenESairApaga() {
        val arm = ArmazenamentoEmMemoria()
        val e = abrirApp(arm)
        esperar { e.sessaoVerificada }
        e.entrar("maria@email.com", "senha-forte-1")
        esperar { e.usuario != null }
        assertEquals(token, SessaoSalva(arm).token())
        e.sair()
        assertNull(SessaoSalva(arm).token()); assertNull(e.usuario)
    }
}
