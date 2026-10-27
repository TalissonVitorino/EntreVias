package br.com.talisson.entrevias.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MotorDeRotasTest {
    private val base = BaseFicticia.viagens

    /** T01: busca SP → FLN lista rotas diretas e com baldeação. */
    @Test fun t01_buscaSpFln() {
        val r = MotorDeRotas.buscar("SP", "FLN", base)
        assertEquals(5, r.size)                                  // 2 diretas + 2 com 1 baldeação + 1 com 2
        assertEquals(2, r.count { it.baldeacoes == 0 })
        assertEquals(2, r.count { it.baldeacoes == 1 })
        assertEquals(1, r.count { it.baldeacoes == 2 })
        assertEquals(18970, r.first().totalCentavos)             // mais barata: 3 trechos, R$ 189,70
        assertEquals(listOf(18970, 18980, 20980, 21990, 32990), r.map { it.totalCentavos })
    }

    /** T02: conexão abaixo do mínimo é descartada. */
    @Test fun t02_conexaoAbaixoDoMinimoDescartada() {
        val r = MotorDeRotas.buscar("SP", "FLN", base, ParametrosConexao(minimaMin = 50, apertadaMin = 60))
        assertTrue(r.none { it.baldeacoes == 2 })                // esperas 60 e 45: a de 45 cai fora
        assertEquals(4, r.size)
    }

    /** T03: entre o mínimo e o limite de aviso a rota aparece, marcada como conexão apertada. */
    @Test fun t03_conexaoApertadaFicaVisivelComAviso() {
        val dois = MotorDeRotas.buscar("SP", "FLN", base).single { it.baldeacoes == 2 }
        assertEquals(listOf(60, 45), dois.esperasMin)
        assertTrue(dois.temConexaoApertada)
        val folgada = MotorDeRotas.buscar("SP", "FLN", base, ParametrosConexao(minimaMin = 30, apertadaMin = 45))
            .single { it.baldeacoes == 2 }
        assertTrue(!folgada.temConexaoApertada)
    }

    @Test fun origemIgualDestinoNaoGeraRota() {
        assertTrue(MotorDeRotas.buscar("SP", "SP", base).isEmpty())
    }

    /** Rede ampliada: toda rota respeita o mínimo e o máximo de espera (RN01). */
    @Test fun t05_redeAmpliadaRespeitaEsperas() {
        val r = MotorDeRotas.buscar("SP", "VIT", base)
        assertTrue(r.isNotEmpty())
        assertTrue(r.all { it.baldeacoes >= 1 })                  // não há corredor direto SP-VIT
        val p = ParametrosConexao()
        assertTrue(r.all { rota -> rota.esperasMin.all { it in p.minimaMin..p.maximaMin } })
        assertTrue(MotorDeRotas.buscar("SP", "BH", base).any { it.baldeacoes == 0 })
        assertTrue(BaseFicticia.terminais.size >= 12)
    }
}
