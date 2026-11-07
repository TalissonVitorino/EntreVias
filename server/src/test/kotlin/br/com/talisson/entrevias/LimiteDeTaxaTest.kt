package br.com.talisson.entrevias

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LimiteDeTaxaTest {
    private var t = 1_000L
    private fun limite(max: Int, janela: Long = 100) = LimiteDeTaxa(max, janela) { t }

    @Test fun permiteAteOMaximoEBloqueiaOExcedente() {
        val l = limite(3)
        repeat(3) { assertNull(l.tentar("a")) }
        assertEquals(100L, l.tentar("a"), "faltam 100 s para a primeira sair da janela")
    }

    @Test fun informaQuantoFaltaParaLiberar() {
        val l = limite(1)
        assertNull(l.tentar("a")); t += 40
        assertEquals(60L, l.tentar("a"))
    }

    @Test fun liberaQuandoOPedidoMaisAntigoSaiDaJanela() {
        val l = limite(2)
        assertNull(l.tentar("a")); t += 60; assertNull(l.tentar("a"))
        assertEquals(40L, l.tentar("a"))
        t += 40; assertNull(l.tentar("a"))              // o primeiro (t=1000) saiu da janela de 100 s
    }

    @Test fun chavesSaoIndependentes() {
        val l = limite(1)
        assertNull(l.tentar("a")); assertNull(l.tentar("b"))
        assertEquals(100L, l.tentar("a"))
    }

    @Test fun pedidoBloqueadoNaoPrologaOBloqueio() {
        val l = limite(1)
        assertNull(l.tentar("a")); t += 50
        l.tentar("a"); l.tentar("a")                     // tentativas barradas não contam
        t += 50; assertNull(l.tentar("a"))
    }
}
