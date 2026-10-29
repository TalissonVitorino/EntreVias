package br.com.talisson.entrevias.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RF19 / RN13 / RN14: nota de risco da conexão (PLANO.md, seção 3.3.1). */
class RiscoConexaoTest {
    private val base = BaseFicticia.viagens

    private fun rota(id: String): Rota = MotorDeRotas.buscar("SP", "FLN", base).single { it.id == id }

    private fun fonteFixa(min: Int) = object : FonteAtraso {
        override fun atrasoTipicoMin(viagem: Viagem) = min
    }

    private fun viagem(id: Int, origem: String, destino: String, saida: Int, chegada: Int) =
        Viagem(id, "Empresa", "Convencional", origem, destino, saida, chegada, 1000)

    /** Conexão em Curitiba entre duas viagens fictícias; os minutos são desde 00:00 do dia da busca. */
    private fun conexao(
        chegada: Int,
        saida: Int,
        terminalProxima: String = "CWB",
        atraso: Int = 0,
        params: ParametrosRisco = ParametrosRisco(),
    ): ConexaoRisco {
        val a = viagem(1, "SP", "CWB", chegada - 300, chegada)
        val b = viagem(2, terminalProxima, "FLN", saida, saida + 300)
        return RiscoConexao.avaliarConexao(a, b, params, fonteFixa(atraso))
    }

    private fun ConexaoRisco.penalidade(tipo: TipoFator) = fatores.single { it.tipo == tipo }.penalidade

    // ---------------------------------------------------------------- T21

    /** T21: Rota A do protótipo, conexão de Curitiba (folga 60, chega 00:30, atraso típico 20). */
    @Test fun t21_rotaA_curitiba_nota60Atencao() {
        val c = RiscoConexao.avaliar(rota("1-2-3")).conexoes[0]
        assertEquals(60, c.folgaMin)
        assertEquals(15, c.penalidade(TipoFator.FOLGA))
        assertEquals(15, c.penalidade(TipoFator.HORARIO))
        assertEquals(0, c.penalidade(TipoFator.TERMINAL))
        assertEquals(10, c.penalidade(TipoFator.ATRASO))
        assertEquals(60, c.nota)
        assertEquals(RotuloRisco.ATENCAO, c.rotulo)
    }

    /** T21: conexão de Joinville (folga 45, chega 03:45, atraso típico 10) e a explicação obrigatória. */
    @Test fun t21_rotaA_joinville_nota55Atencao_comFraseEDoisFatos() {
        val c = RiscoConexao.avaliar(rota("1-2-3")).conexoes[1]
        assertEquals(45, c.folgaMin)
        assertEquals(55, c.nota)
        assertEquals(RotuloRisco.ATENCAO, c.rotulo)
        assertEquals(2, c.fatos.size)
        assertEquals("Folga de 45 min", c.fatos[0])             // maior penalidade primeiro
        assertEquals("Conexão de madrugada", c.fatos[1])
        assertEquals("Atenção em Joinville: Folga de 45 min.", c.frase)
    }

    /** T21: pior caso (folga 30, madrugada, terminal diferente, atraso 40) → 100 − (35+15+25+20) = 5. */
    @Test fun t21_piorCaso_nota5Arriscado() {
        val c = conexao(chegada = 1560, saida = 1590, terminalProxima = "CBX", atraso = 40)
        assertEquals(5, c.nota)
        assertEquals(RotuloRisco.ARRISCADO, c.rotulo)
        assertEquals(listOf(TipoFator.FOLGA, TipoFator.TERMINAL, TipoFator.ATRASO, TipoFator.HORARIO), c.fatores.map { it.tipo })
        assertEquals(2, c.fatos.size)
    }

    /** T21: melhor caso (folga 120, de dia, mesmo terminal, atraso 0) → 100 − 5 = 95, ainda com 2 fatos. */
    @Test fun t21_melhorCaso_nota95Seguro() {
        val c = conexao(chegada = 720, saida = 840)
        assertEquals(95, c.nota)
        assertEquals(RotuloRisco.SEGURO, c.rotulo)
        assertEquals(2, c.fatos.size)
        assertEquals(listOf("Folga de 120 min", "Sem atraso típico registrado"), c.fatos)
    }

    @Test fun t21_faixasDeFolga() {
        val esperado = mapOf(29 to 60, 30 to 35, 44 to 35, 45 to 25, 59 to 25, 60 to 15, 89 to 15, 90 to 5, 179 to 5, 180 to 0)
        for ((folga, penalidade) in esperado) {
            val c = conexao(chegada = 720, saida = 720 + folga)   // 12:00: de dia, mesmo terminal, atraso 0
            assertEquals(penalidade, c.penalidade(TipoFator.FOLGA), "folga $folga")
            assertEquals(100 - penalidade, c.nota, "nota com folga $folga")
        }
    }

    @Test fun t21_madrugadaVai22h00Ate05h59() {
        assertEquals(0, conexao(chegada = 1200, saida = 1319).penalidade(TipoFator.HORARIO))   // 20:00 → 21:59
        assertEquals(15, conexao(chegada = 1200, saida = 1320).penalidade(TipoFator.HORARIO))  // saída 22:00
        assertEquals(15, conexao(chegada = 359, saida = 480).penalidade(TipoFator.HORARIO))    // chegada 05:59
        assertEquals(0, conexao(chegada = 360, saida = 480).penalidade(TipoFator.HORARIO))     // chegada 06:00
    }

    @Test fun t21_atrasoTipicoPesaMetadeAteOMaximo() {
        val folga120 = { atraso: Int -> conexao(chegada = 720, saida = 840, atraso = atraso).penalidade(TipoFator.ATRASO) }
        assertEquals(0, folga120(0))
        assertEquals(10, folga120(20))
        assertEquals(12, folga120(25))      // metade, arredondada para baixo
        assertEquals(20, folga120(40))
        assertEquals(30, folga120(100))     // teto de 30
    }

    @Test fun t21_cortesDosRotulosSaoInclusivos() {
        val p = ParametrosRisco()
        assertEquals(RotuloRisco.SEGURO, p.rotulo(100))
        assertEquals(RotuloRisco.SEGURO, p.rotulo(70))
        assertEquals(RotuloRisco.ATENCAO, p.rotulo(69))
        assertEquals(RotuloRisco.ATENCAO, p.rotulo(40))
        assertEquals(RotuloRisco.ARRISCADO, p.rotulo(39))
        assertEquals(RotuloRisco.ARRISCADO, p.rotulo(0))
    }

    @Test fun t21_parametrosVemDaTabelaParametro() {
        val p = ParametrosRisco.deMapa(mapOf("risco_madrugada" to 20, "risco_corte_seguro" to 80, "risco_folga_60_89" to 10))
        assertEquals(20, p.madrugada)
        assertEquals(80, p.corteSeguro)
        assertEquals(10, p.folga60a89)
        assertEquals(35, p.folga30a44)                          // chave ausente mantém o valor inicial
        assertEquals(40, p.corteAtencao)
    }

    @Test fun t21_paraMapaEDeMapaSaoInversos() {
        val p = ParametrosRisco(folga30a44 = 40, madrugada = 10, madrugadaInicioMin = 23 * 60, corteSeguro = 75)
        assertEquals(p, ParametrosRisco.deMapa(p.paraMapa()))
        assertEquals(12, ParametrosRisco().paraMapa().size)
        assertEquals(1320, ParametrosRisco().paraMapa()["madrugada_inicio_min"])
    }

    @Test fun t21_parametrosViajamEmJsonEChaveAusenteUsaOValorInicial() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        assertEquals(ParametrosRisco(), json.decodeFromString<ParametrosRisco>("{}"))
        val p = json.decodeFromString<ParametrosRisco>("""{"madrugada":20,"campoNovo":1}""")
        assertEquals(20, p.madrugada)
        assertEquals(70, p.corteSeguro)
        assertEquals(p, json.decodeFromString<ParametrosRisco>(json.encodeToString(ParametrosRisco.serializer(), p)))
    }

    @Test fun t21_parametrosInvalidosSaoRecusados() {
        assertFailsWith<IllegalArgumentException> { ParametrosRisco(corteSeguro = 30, corteAtencao = 40) }
        assertFailsWith<IllegalArgumentException> { ParametrosRisco(corteSeguro = 101) }
        assertFailsWith<IllegalArgumentException> { ParametrosRisco(madrugada = -1) }
    }

    // ---------------------------------------------------------------- T22

    /** T22: o risco da rota é o da pior conexão (RN14). */
    @Test fun t22_riscoDaRotaEhODaPiorConexao() {
        val a = RiscoConexao.avaliar(rota("1-2-3"))
        assertEquals(listOf(60, 55), a.conexoes.map { it.nota })
        assertEquals(55, a.nota)
        assertEquals(RotuloRisco.ATENCAO, a.rotulo)

        val b = RiscoConexao.avaliar(rota("4-5"))               // folga 90, madrugada, atraso típico 20
        assertEquals(70, b.nota)
        assertEquals(RotuloRisco.SEGURO, b.rotulo)
    }

    /** T22 / RN14: rota direta não tem conexão: sem nota; para ordenar e filtrar conta como 100. */
    @Test fun t22_rotaDiretaNaoTemNota() {
        val direta = RiscoConexao.avaliar(rota("6"))
        assertTrue(direta.conexoes.isEmpty())
        assertNull(direta.nota)
        assertNull(direta.rotulo)
        assertEquals(100, direta.notaParaOrdenacao)
    }

    /** A mesma linha no mesmo horário tem o mesmo atraso típico em qualquer dia (a viagem do dia seguinte tem outro id). */
    @Test fun t22_atrasoFicticioDependeDaLinhaENaoDoIdNemDoDia() {
        val v2 = base.single { it.id == 2 }                      // Curitiba → Joinville, 01:30
        assertEquals(10, AtrasoFicticio.atrasoTipicoMin(v2))
        val amanha = v2.copy(id = 9999, saidaMin = v2.saidaMin + 1440, chegadaMin = v2.chegadaMin + 1440)
        assertEquals(10, AtrasoFicticio.atrasoTipicoMin(amanha))
        for (v in base) {                                        // sempre um dos cinco valores e sempre o mesmo
            val a = AtrasoFicticio.atrasoTipicoMin(v)
            assertTrue(a in listOf(0, 5, 10, 15, 20), "atraso ${v.id}")
            assertEquals(a, AtrasoFicticio.atrasoTipicoMin(v.copy(id = v.id + 5000)))
        }
    }

    /** RN14: enquanto o histórico de atraso for fictício a tela diz "simulado". */
    @Test fun t22_riscoComAtrasoFicticioFicaMarcadoComoSimulado() {
        assertTrue(RiscoConexao.avaliar(rota("1-2-3")).simulado)
    }

    // ---------------------------------------------------------------- T23

    /** T23 / RN14: rota Arriscada continua na lista do motor; o risco só a rotula, nunca a esconde. */
    @Test fun t23_rotaArriscadaContinuaNaLista() {
        val a = viagem(11, "SP", "CWB", 1200, 1500)             // 20:00 → 01:00 do dia seguinte
        val b = viagem(12, "CWB", "FLN", 1530, 1830)            // sai 01:30: folga de exatamente 30 min
        val rotas = MotorDeRotas.buscar("SP", "FLN", listOf(a, b))
        assertEquals(1, rotas.size)                             // o motor não filtra por risco

        val risco = RiscoConexao.avaliar(rotas.single(), fonte = fonteFixa(40))   // 100 − 35 − 15 − 20
        assertEquals(30, risco.nota)
        assertEquals(RotuloRisco.ARRISCADO, risco.rotulo)
    }
}
