package br.com.talisson.entrevias.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** RF34 / RN21: limite de 10 roteiros salvos (T62). */
class LimiteRoteirosTest {
    private val dez = (1..10).toList()

    @Test fun t62_noveCabemEODecimoEhGuardado() {
        val r = LimiteRoteiros.salvar((1..9).toList(), 10)
        assertEquals((1..10).toList(), assertIs<Salvamento.Salvo<Int>>(r).lista)
    }

    @Test fun t62_oDecimoPrimeiroEhRecusadoSemApagarNada() {
        val r = LimiteRoteiros.salvar(dez, 11)
        assertIs<Salvamento.Cheio>(r)
        assertEquals((1..10).toList(), dez)
    }

    @Test fun t62_roteiroJaGuardadoNaoContaDeNovoNemDaErroMesmoComALotado() {
        assertIs<Salvamento.Repetido>(LimiteRoteiros.salvar(dez, 4))
        assertIs<Salvamento.Repetido>(LimiteRoteiros.salvar(listOf(4), 4))
    }

    @Test fun t62_oLimiteVemDeFora() {
        assertIs<Salvamento.Cheio>(LimiteRoteiros.salvar(listOf(1, 2), 3, limite = 2))
        assertEquals(10, LimiteRoteiros.GRATUITO)
    }
}
