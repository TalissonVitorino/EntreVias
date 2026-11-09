package br.com.talisson.entrevias.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FiltrosTest {
    private fun v(id: Int, emp: String, cat: String, o: String, d: String, saida: Int, dur: Int = 120, preco: Int = 5000) = Viagem(id, emp, cat, o, d, saida, saida + dur, preco)
    private val manha = Rota(listOf(v(1, "Horizonte", "Convencional", "A", "B", 7 * 60)))                              // 07:00, R$ 50
    private val noite = Rota(listOf(v(2, "Serra", "Leito", "A", "B", 22 * 60, preco = 12000)))                        // 22:00, R$ 120
    private val madrugada = Rota(listOf(v(3, "Costa", "Executivo", "A", "B", 2 * 60, preco = 8000)))                  // 02:00, R$ 80
    private val mista = Rota(listOf(v(4, "Horizonte", "Convencional", "A", "C", 9 * 60), v(5, "Serra", "Leito", "C", "B", 13 * 60, preco = 9000)))   // 2 trechos: R$ 140
    private val todas = listOf(manha, noite, madrugada, mista)

    @Test fun semFiltroNadaMuda() { assertFalse(FiltroDeRotas().ativo); assertEquals(todas, FiltroDeRotas().aplicar(todas)) }

    @Test fun faixasDeHorarioTemLimitesExatosComFimExclusivo() {
        fun na(minuto: Int) = Rota(listOf(v(9, "E", "Convencional", "A", "B", minuto)))
        val f = { fx: FaixaHorario -> FiltroDeRotas(horarios = setOf(fx)) }
        assertTrue(f(FaixaHorario.MADRUGADA).aceita(na(0))); assertTrue(f(FaixaHorario.MADRUGADA).aceita(na(359))); assertFalse(f(FaixaHorario.MADRUGADA).aceita(na(360)))
        assertTrue(f(FaixaHorario.MANHA).aceita(na(360))); assertTrue(f(FaixaHorario.MANHA).aceita(na(719))); assertFalse(f(FaixaHorario.MANHA).aceita(na(720)))
        assertTrue(f(FaixaHorario.TARDE).aceita(na(720))); assertFalse(f(FaixaHorario.TARDE).aceita(na(1080)))
        assertTrue(f(FaixaHorario.NOITE).aceita(na(1080))); assertTrue(f(FaixaHorario.NOITE).aceita(na(1439)))
    }

    @Test fun saidaNoDiaSeguinteCaiNaFaixaDoRelogio() {
        val amanha = Rota(listOf(v(8, "E", "Convencional", "A", "B", 1440 + 7 * 60)))   // 07:00 do dia seguinte
        assertTrue(FiltroDeRotas(horarios = setOf(FaixaHorario.MANHA)).aceita(amanha))
    }

    @Test fun maisDeUmaFaixaSomaOpcoes() = assertEquals(listOf(manha, mista, madrugada).toSet(), FiltroDeRotas(horarios = setOf(FaixaHorario.MADRUGADA, FaixaHorario.MANHA)).aplicar(todas).toSet())

    @Test fun empresaECategoriaAceitamSeAlgumTrechoUsa() {
        assertEquals(setOf(manha, mista), FiltroDeRotas(empresas = setOf("Horizonte")).aplicar(todas).toSet())
        assertEquals(setOf(noite, mista), FiltroDeRotas(categorias = setOf("Leito")).aplicar(todas).toSet(), "a rota mista tem um trecho Leito")
    }

    @Test fun precoMaximoEhPorPessoaETotalDaRota() {
        assertEquals(setOf(manha, madrugada), FiltroDeRotas(precoMaxCentavos = 8000).aplicar(todas).toSet(), "o limite é inclusivo")
        assertEquals(setOf(manha), FiltroDeRotas(precoMaxCentavos = 7999).aplicar(todas).toSet())
        assertEquals(todas.toSet(), FiltroDeRotas(precoMaxCentavos = 14000).aplicar(todas).toSet(), "a rota de 2 trechos soma R$ 140")
    }

    @Test fun gruposSeCombinamComE() {
        val f = FiltroDeRotas(horarios = setOf(FaixaHorario.MANHA), empresas = setOf("Horizonte"), precoMaxCentavos = 6000)
        assertEquals(listOf(manha), f.aplicar(todas)); assertEquals(3, f.quantosGrupos)
    }

    @Test fun terminalSemInformacaoNaoContaComoAcessivel() {
        val infra = mapOf("A" to InfraTerminal("A", true, true, false, false, false, 100), "B" to InfraTerminal("B", true, true, false, false, false, 100))
        val f = FiltroDeRotas(soTerminaisAcessiveis = true)
        assertTrue(f.aceita(manha, infra)); assertFalse(f.aceita(manha, emptyMap()), "sem dado, por cautela, não passa")
        assertFalse(f.aceita(mista, infra), "o terminal C não tem informação")
    }

    @Test fun acessivelExigeBanheiroERampa() {
        assertTrue(InfraTerminal("X", true, true, false, false, false, 0).acessivel)
        assertFalse(InfraTerminal("X", true, false, true, true, true, 0).acessivel); assertFalse(InfraTerminal("X", false, true, true, true, true, 0).acessivel)
    }

    @Test fun nenhumaRotaPassaDevolveVazioSemErro() = assertEquals(emptyList(), FiltroDeRotas(empresas = setOf("Inexistente")).aplicar(todas))
}
