package br.com.talisson.entrevias

import br.com.talisson.entrevias.domain.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.*

/** B1: ida e volta no estado do app, sem rede. */
class IdaEVoltaEstadoTest {
    private fun v(id: Int, o: String, d: String, saida: Int, dur: Int = 120) = Viagem(id, "E", "Convencional", o, d, saida, saida + dur, 5000)
    private fun estado() = Estado(Api("http://127.0.0.1:1"), CoroutineScope(Dispatchers.Unconfined))

    @Test fun voltaPrecisaSerDepoisDaIda() {
        val e = estado(); e.idaEVolta = true; e.origem = "CWB"; e.destino = "FLN"
        e.data = Data(2026, 11, 20); e.dataVolta = Data(2026, 11, 20); assertFalse(e.voltaValida); assertFalse(e.podeBuscar, "volta no mesmo dia não busca")
        e.dataVolta = Data(2026, 11, 19); assertFalse(e.podeBuscar)
        e.dataVolta = Data(2026, 11, 21); assertTrue(e.voltaValida); assertTrue(e.podeBuscar)
        e.idaEVolta = false; e.dataVolta = Data(2026, 11, 1); assertTrue(e.podeBuscar, "só ida ignora a data da volta")
    }

    @Test fun soAparecemVoltasQueSaemDepoisDeAIdaChegarMaisAConexaoMinima() {
        val e = estado(); e.rData = Data(2026, 11, 20); e.rDataVolta = Data(2026, 11, 21)
        val ida = Rota(listOf(v(1, "CWB", "FLN", saida = 1400, dur = 300)))            // chega 1700 min (04:20 do dia 21)
        val cedo = Rota(listOf(v(2, "FLN", "CWB", saida = 100)))                         // 01:40 de 21/11 = 1540: antes da chegada
        val quaseLa = Rota(listOf(v(3, "FLN", "CWB", saida = 1700 - 1440 + 29)))        // chega 1700, sai 1729: só 29 min (< mínimo de 30)
        val ok = Rota(listOf(v(4, "FLN", "CWB", saida = 1700 - 1440 + 30)))             // exatamente o mínimo
        val tarde = Rota(listOf(v(5, "FLN", "CWB", saida = 900)))
        e.todasVolta = listOf(cedo, quaseLa, ok, tarde)
        assertEquals(setOf(4, 5), e.opcoesDeVolta(ida).flatMap { it.viagens.map { x -> x.id } }.toSet())
    }

    @Test fun semDataDeVoltaNaoHaOpcoes() { val e = estado(); e.todasVolta = listOf(Rota(listOf(v(9, "FLN", "CWB", 600)))); assertTrue(e.opcoesDeVolta(Rota(listOf(v(1, "CWB", "FLN", 100)))).isEmpty()) }

    @Test fun trocarDeRotaZeraAVoltaEscolhida() {
        val e = estado(); val r = Rota(listOf(v(1, "CWB", "FLN", 100)))
        e.voltaEscolhida = Rota(listOf(v(2, "FLN", "CWB", 600))); e.abrirRota(r)
        assertNull(e.voltaEscolhida, "a volta escolhida era para outra ida")
    }

    @Test fun comIdaEVoltaSemEscolherAVoltaNaoCalculaNemChamaOServidor() {
        val e = estado(); e.rDataVolta = Data(2026, 11, 21); e.compraRota = Rota(listOf(v(1, "CWB", "FLN", 100))); e.paxSelecionados.add(1)
        e.calcularPedido()
        assertNull(e.pedido); assertTrue("volta" in e.compraErro.orEmpty())
    }
}
