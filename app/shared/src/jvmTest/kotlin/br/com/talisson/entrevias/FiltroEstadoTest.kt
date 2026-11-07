package br.com.talisson.entrevias

import br.com.talisson.entrevias.domain.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.*

/** B2 e B4 no estado do app, sem rede (porta fechada de propósito). */
class FiltroEstadoTest {
    private fun v(id: Int, emp: String, cat: String, o: String, d: String, saida: Int, preco: Int) = Viagem(id, emp, cat, o, d, saida, saida + 120, preco)
    private val manha = Rota(listOf(v(1, "Horizonte", "Convencional", "CWB", "JVL", 7 * 60, 5000)))
    private val noite = Rota(listOf(v(2, "Serra", "Leito", "CWB", "JVL", 22 * 60, 12000)))
    private fun estado() = Estado(Api("http://127.0.0.1:1"), CoroutineScope(Dispatchers.Unconfined)).apply { todas = listOf(manha, noite) }

    @Test fun semFiltroMostraTudoNaOrdemEscolhida() { val e = estado(); assertEquals(listOf(manha, noite), e.resultado) }

    @Test fun cadaFiltroRestringeEOutroDesfaz() {
        val e = estado()
        e.alternarHorario(FaixaHorario.NOITE); assertEquals(listOf(noite), e.resultado)
        e.alternarHorario(FaixaHorario.NOITE); assertEquals(listOf(manha, noite), e.resultado, "desmarcar volta ao que era")
        e.alternarEmpresa("Horizonte"); assertEquals(listOf(manha), e.resultado)
        e.limparFiltro(); e.alternarCategoria("Leito"); assertEquals(listOf(noite), e.resultado)
        e.limparFiltro(); e.definirPrecoMax(6000); assertEquals(listOf(manha), e.resultado)
        e.definirPrecoMax(6000); assertNull(e.filtro.precoMaxCentavos, "tocar de novo no mesmo degrau tira o limite")
    }

    @Test fun filtroSemResultadoNaoApagaAsRotasDaBusca() {
        val e = estado(); e.alternarEmpresa("Inexistente")
        assertEquals(emptyList(), e.resultado); assertEquals(2, e.resultadoSemFiltro!!.size, "dá para contar o que há sem filtro e limpar")
        e.limparFiltro(); assertEquals(2, e.resultado!!.size)
    }

    @Test fun acessibilidadeUsaAInfraDoServidor() {
        val e = estado()
        e.infra = mapOf("CWB" to InfraTerminal("CWB", true, true, true, false, false, 90), "JVL" to InfraTerminal("JVL", true, false, true, false, false, 150))
        e.alternarAcessiveis(); assertEquals(emptyList(), e.resultado, "Joinville não tem rampa: nenhuma rota passa")
        e.infra = e.infra + ("JVL" to InfraTerminal("JVL", true, true, true, false, false, 150)); assertEquals(listOf(manha, noite), e.resultado)
    }

    @Test fun semInfraNenhumaRotaPassaNoFiltroDeAcessibilidade() {
        val e = estado(); e.alternarAcessiveis()
        assertTrue(e.infra.isEmpty()); assertEquals(emptyList(), e.resultado, "sem dado não afirmamos acessibilidade")
    }

    @Test fun ordemContinuaValendoDentroDoFiltro() {
        val e = estado(); e.ordem = Ordem.MAIS_BARATA; e.alternarCategoria("Convencional"); e.alternarCategoria("Leito")
        assertEquals(listOf(manha, noite), e.resultado)
    }
}
