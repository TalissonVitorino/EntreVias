package br.com.talisson.entrevias.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RF20 / RN15: plano B de uma conexão (PLANO.md, seção 3.3.2). Minutos desde 00:00 do dia da busca. */
class PlanoBTest {
    private fun v(id: Int, origem: String, destino: String, saida: Int, chegada: Int, preco: Int = 1000) =
        Viagem(id, "Empresa", "Convencional", origem, destino, saida, chegada, preco)

    private fun PlanoB.ids() = opcoes.map { o -> o.novas.map { it.id } }

    // ---------------------------------------------------------------- T24

    /** T24: até 3 saídas do mesmo terminal para a mesma cidade, a partir de 15 min e em até 12 h, por menor chegada final. */
    @Test fun t24_ate3SaidasDoMesmoTerminalParaAMesmaCidadeOrdenadasPelaChegadaFinal() {
        val a = v(1, "SP", "CWB", 1200, 1500)                       // chega 01:00
        val perdida = v(2, "CWB", "JVL", 1530, 1665, preco = 5000)   // a que se perde: 01:30
        val x1 = v(11, "CWB", "JVL", 1540, 1675, 5000)
        val x2 = v(12, "CWB", "JVL", 1520, 1655, 6000)               // 20 min depois da chegada e chega antes da perdida
        val x3 = v(13, "CWB", "JVL", 1505, 1640, 100)                // só 5 min depois da chegada: fora (mínimo 15)
        val x4 = v(14, "CWB", "JVL", 1800, 1935, 4000)
        val x5 = v(15, "CWB", "JVL", 2000, 2135, 4500)               // 4ª opção: o teto é 3
        val x6 = v(16, "CWB", "JVL", 2300, 2435, 100)                // mais de 12 h depois da chegada: fora
        val outroTerminal = v(17, "CWB2", "JVL", 1600, 1735)
        val outraCidade = v(18, "CWB", "FLN", 1600, 1900)
        val todas = listOf(a, perdida, x1, x2, x3, x4, x5, x6, outroTerminal, outraCidade)

        val plano = PlanoBConexao.avaliar(Rota(listOf(a, perdida)), 0, todas)

        assertEquals(listOf(listOf(12), listOf(11), listOf(14)), plano.ids())   // a perdida (id 2) nunca entra
        assertTrue(plano.opcoes.all { it.tipo == TipoPlanoB.MESMA_CIDADE })
        assertEquals(listOf(1655, 1675, 1935), plano.opcoes.map { it.chegadaFinalMin })
        assertEquals(listOf(-10, 10, 270), plano.opcoes.map { it.atrasoMin })   // chegada final nova − 1665
        assertEquals(6000, plano.opcoes[0].novosBilhetesCentavos)
        assertEquals(5000, plano.opcoes[0].bilhetesNaoUsadosCentavos)           // o bilhete da viagem perdida
        assertTrue(!plano.semOpcao)
    }

    @Test fun t24_osCortesVemDosParametros() {
        val a = v(1, "SP", "CWB", 1200, 1500)
        val perdida = v(2, "CWB", "JVL", 1530, 1665)
        val x1 = v(11, "CWB", "JVL", 1540, 1675, 5000)
        val x2 = v(12, "CWB", "JVL", 1520, 1655, 6000)
        val x4 = v(14, "CWB", "JVL", 1800, 1935, 4000)
        val rota = Rota(listOf(a, perdida))
        val todas = listOf(a, perdida, x1, x2, x4)

        assertEquals(listOf(listOf(12)), PlanoBConexao.avaliar(rota, 0, todas, ParametrosPlanoB(maxOpcoes = 1)).ids())
        assertEquals(listOf(listOf(12), listOf(11)), PlanoBConexao.avaliar(rota, 0, todas, ParametrosPlanoB(janelaH = 1)).ids())   // até 02:00
        assertEquals(listOf(listOf(14)), PlanoBConexao.avaliar(rota, 0, todas, ParametrosPlanoB(minMin = 45)).ids())              // a partir de 01:45
    }

    @Test fun t24_empateNaChegadaDesempataPeloPreco() {
        val a = v(1, "SP", "CWB", 1200, 1500)
        val perdida = v(2, "CWB", "JVL", 1530, 1665)
        val caro = v(21, "CWB", "JVL", 1540, 1675, 5000)
        val barato = v(22, "CWB", "JVL", 1560, 1675, 4000)
        val plano = PlanoBConexao.avaliar(Rota(listOf(a, perdida)), 0, listOf(a, perdida, caro, barato))
        assertEquals(listOf(listOf(22), listOf(21)), plano.ids())
    }

    /** Há trecho depois da perdida: se ainda dá tempo (RN01) aproveita o bilhete; se não, replaneja o resto; se nada, sem chegada final. */
    @Test fun t24_comTrechosRestantesAproveitaOuReplanejaOResto() {
        val a = v(1, "SP", "CWB", 1200, 1500)
        val perdida = v(2, "CWB", "JVL", 1530, 1665, 5000)
        val d = v(3, "JVL", "FLN", 1800, 1980, 3000)                 // trecho que sobra; chegada final original 1980
        val c1 = v(11, "CWB", "JVL", 1540, 1675, 4000)               // chega 125 min antes de d: o bilhete de d continua valendo
        val c2 = v(12, "CWB", "JVL", 1700, 1835, 3500)               // chega depois da saída de d: precisa de outro trecho
        val c3 = v(13, "CWB", "JVL", 2000, 2135, 3000)               // nenhum trecho para FLN depois disso
        val e = v(21, "JVL", "FLN", 1900, 2080, 2000)
        val plano = PlanoBConexao.avaliar(Rota(listOf(a, perdida, d)), 0, listOf(a, perdida, d, c1, c2, c3, e))

        assertEquals(listOf(listOf(11), listOf(12, 21), listOf(13)), plano.ids())
        val (o1, o2, o3) = plano.opcoes

        assertEquals(listOf(3), o1.aproveitadas.map { it.id })       // reaproveita o bilhete já comprado
        assertEquals(1980, o1.chegadaFinalMin); assertEquals(0, o1.atrasoMin)
        assertEquals(4000, o1.novosBilhetesCentavos); assertEquals(5000, o1.bilhetesNaoUsadosCentavos)

        assertTrue(o2.aproveitadas.isEmpty())                        // o resto foi replanejado com um bilhete novo
        assertEquals(2080, o2.chegadaFinalMin); assertEquals(100, o2.atrasoMin)
        assertEquals(5500, o2.novosBilhetesCentavos)                 // c2 + e
        assertEquals(8000, o2.bilhetesNaoUsadosCentavos)             // perdida + d

        assertNull(o3.chegadaFinalMin); assertNull(o3.atrasoMin)     // não há como completar a viagem
        assertEquals(8000, o3.bilhetesNaoUsadosCentavos)
    }

    @Test fun t24_indiceDeConexaoInvalidoEhRecusado() {
        val rota = Rota(listOf(v(1, "SP", "CWB", 1200, 1500), v(2, "CWB", "JVL", 1530, 1665)))
        assertFailsWith<IllegalArgumentException> { PlanoBConexao.avaliar(rota, -1, rota.viagens) }
        assertFailsWith<IllegalArgumentException> { PlanoBConexao.avaliar(rota, 1, rota.viagens) }
        assertFailsWith<IllegalArgumentException> { PlanoBConexao.avaliar(Rota(listOf(v(1, "SP", "CWB", 1200, 1500))), 0, emptyList()) }
    }

    // ---------------------------------------------------------------- T43

    /** T43: Rota A, perdendo a viagem 2 em Curitiba → sem outra saída para Joinville → replaneja até Florianópolis. */
    @Test fun t43_semSaidaParaAMesmaCidadeReplanejaAteODestinoFinal() {
        val base = BaseFicticia.viagens
        val rotaA = MotorDeRotas.buscar("SP", "FLN", base).single { it.id == "1-2-3" }

        val plano = PlanoBConexao.avaliar(rotaA, 0, base)

        val opcao = plano.opcoes.single()
        assertEquals(TipoPlanoB.REPLANEJADA, opcao.tipo)
        assertEquals(listOf(5), opcao.novas.map { it.id })           // Curitiba → Florianópolis, 03:00 → 07:00
        assertTrue(opcao.aproveitadas.isEmpty())
        assertEquals(1860, opcao.chegadaFinalMin)                    // 07:00 do dia seguinte
        assertEquals(-30, opcao.atrasoMin)                           // 30 min antes dos 07:30 originais
        assertEquals(8990, opcao.novosBilhetesCentavos)              // R$ 89,90
        assertEquals(8980, opcao.bilhetesNaoUsadosCentavos)          // viagem 2 (R$ 49,90) + viagem 3 (R$ 39,90)
    }

    /** T43: sem nenhuma opção o plano diz isso (a tela mostra "sem outra saída nas próximas 12 h"). */
    @Test fun t43_semNenhumaOpcaoNaJanela() {
        val base = BaseFicticia.viagens
        val rotaA = MotorDeRotas.buscar("SP", "FLN", base).single { it.id == "1-2-3" }
        val plano = PlanoBConexao.avaliar(rotaA, 1, base)            // perdendo a viagem 3 em Joinville
        assertTrue(plano.semOpcao)
        assertTrue(plano.opcoes.isEmpty())
    }

    // ---------------------------------------------------------------- T44

    /** T44: o replanejamento reaproveita bilhetes já comprados que voltam a ser usados e só cobra os novos. */
    @Test fun t44_reaproveitaBilheteJaCompradoNoReplanejamento() {
        val a = v(1, "SP", "CWB", 1200, 1500)
        val perdida = v(2, "CWB", "JVL", 1530, 1665, 5000)
        val d1 = v(3, "JVL", "POA", 1800, 1900, 3000)
        val d2 = v(4, "POA", "FLN", 2000, 2100, 2500)                // chegada final original 2100
        val c = v(11, "CWB", "JVL", 1700, 1835, 4000)                // chega depois da saída de d1
        val e1 = v(12, "JVL", "POA", 1900, 1960, 3500)               // novo trecho que ainda alcança d2
        val plano = PlanoBConexao.avaliar(Rota(listOf(a, perdida, d1, d2)), 0, listOf(a, perdida, d1, d2, c, e1))

        val o = plano.opcoes.single()
        assertEquals(listOf(11, 12), o.novas.map { it.id })
        assertEquals(listOf(4), o.aproveitadas.map { it.id })        // d2 já foi comprado
        assertEquals(2100, o.chegadaFinalMin); assertEquals(0, o.atrasoMin)
        assertEquals(7500, o.novosBilhetesCentavos)                  // c + e1
        assertEquals(8000, o.bilhetesNaoUsadosCentavos)              // perdida + d1
    }

    @Test fun t44_parametrosVemDaTabelaParametro() {
        val p = ParametrosPlanoB.deMapa(mapOf("planob_max_opcoes" to 2, "planob_janela_h" to 6))
        assertEquals(ParametrosPlanoB(maxOpcoes = 2, minMin = 15, janelaH = 6), p)
        assertEquals(p, ParametrosPlanoB.deMapa(p.paraMapa()))
        assertEquals(setOf("planob_max_opcoes", "planob_min_min", "planob_janela_h"), ParametrosPlanoB().paraMapa().keys)
        assertFailsWith<IllegalArgumentException> { ParametrosPlanoB(maxOpcoes = 0) }
        assertFailsWith<IllegalArgumentException> { ParametrosPlanoB(minMin = -1) }
        assertFailsWith<IllegalArgumentException> { ParametrosPlanoB(janelaH = 0) }
    }
}
