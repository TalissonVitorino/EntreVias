package br.com.talisson.entrevias

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessaoSalvaTest {
    private val tokenBom = "abcDEF0123456789-_abcDEF0123456789-_xyz"

    @Test fun guardaELeOToken() {
        val s = SessaoSalva(ArmazenamentoEmMemoria())
        assertNull(s.token())
        s.guardar(tokenBom)
        assertEquals(tokenBom, s.token())
    }

    @Test fun apagarRemove() {
        val s = SessaoSalva(ArmazenamentoEmMemoria()).also { it.guardar(tokenBom) }
        s.apagar()
        assertNull(s.token())
    }

    @Test fun lixoNoArmazenamentoViraSemSessao() {
        val m = ArmazenamentoEmMemoria()
        val s = SessaoSalva(m)
        listOf("", "curto", "x".repeat(129), "com espaço $tokenBom", "$tokenBom\n", "<script>alert(1)</script>-aaaaaaaaaaaa").forEach {
            m.gravar("sessao", it)
            assertNull(s.token(), "aceitou: '$it'")
        }
    }

    @Test fun armazenamentoQueFalhaNuncaDerrubaOApp() {
        val quebrado = object : ArmazenamentoLocal {
            override fun ler(chave: String): String? = error("disco cheio")
            override fun gravar(chave: String, valor: String?) = error("disco cheio")
        }
        val s = SessaoSalva(quebrado)
        s.guardar(tokenBom); s.apagar()
        assertNull(s.token())
    }
}
