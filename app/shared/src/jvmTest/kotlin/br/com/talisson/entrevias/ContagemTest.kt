package br.com.talisson.entrevias

import kotlin.test.Test
import kotlin.test.assertEquals

/** O prazo é do servidor: a conta parte do relógio DELE e só mede, no aparelho, o tempo que passou desde a resposta. */
class ContagemTest {
    @Test fun faltaOQueOServidorDiz() = assertEquals(900, restanteSeg(expiraEm = 1_000_900, agoraDoServidor = 1_000_000, decorridoNoAparelho = 0))

    @Test fun otempoDecorridoNoAparelhoAbate() = assertEquals(800, restanteSeg(1_000_900, 1_000_000, 100))

    @Test fun passouDoPrazoFicaNegativoEATelaMostraVencido() { assertEquals(-5, restanteSeg(1_000_900, 1_000_000, 905)); assertEquals("00:00", Tempo.contagem(restanteSeg(1_000_900, 1_000_000, 905))) }

    @Test fun oRelogioDoAparelhoNaoEntraNaConta() {
        // o mesmo prazo, com o aparelho 10 dias atrasado ou adiantado: a resposta é a mesma, porque o aparelho nunca é consultado
        val resultado = restanteSeg(1_000_900, 1_000_000, 0)
        assertEquals(900, resultado); assertEquals("15:00", Tempo.contagem(resultado))
    }
}
