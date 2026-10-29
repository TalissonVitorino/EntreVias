package br.com.talisson.entrevias

import kotlin.test.Test
import kotlin.test.assertEquals

class FormatadoresTest {
    /** RF20: o atraso na chegada final do plano B, em português simples. */
    @Test fun atrasoTxtDizSeChegaAntesOuDepois() {
        assertEquals("no horário previsto", atrasoTxt(0))
        assertEquals("30min antes do previsto", atrasoTxt(-30))
        assertEquals("1h antes do previsto", atrasoTxt(-60))
        assertEquals("1h40 depois do previsto", atrasoTxt(100))
        assertEquals("10min depois do previsto", atrasoTxt(10))
    }
}
