package br.com.talisson.entrevias.domain

import kotlinx.serialization.Serializable

/** RN13: rótulo da nota de risco de uma conexão. */
enum class RotuloRisco(val texto: String) {
    SEGURO("Seguro"), ATENCAO("Atenção"), ARRISCADO("Arriscado")
}

/** Os quatro fatores que pesam na nota. A ordem do enum desempata fatores com a mesma penalidade. */
enum class TipoFator { FOLGA, ATRASO, HORARIO, TERMINAL }

/** Um fator da nota: quanto tirou (0 se não pesou) e o texto que explica. */
data class FatorRisco(val tipo: TipoFator, val penalidade: Int, val texto: String)

/**
 * RN13: valores iniciais da fórmula (PLANO.md, seção 3.3.1; decisão provisória, a validar com dados reais).
 * No servidor ficam na tabela `parametro`; use [deMapa] para lê-los.
 * O dia de pico (−10) fica de fora até o calendário de feriados existir (RF27).
 * Serializável: o servidor entrega estes valores ao app em `/api/parametros/risco`.
 */
@Serializable
data class ParametrosRisco(
    val folgaMenor30: Int = 60,
    val folga30a44: Int = 35,
    val folga45a59: Int = 25,
    val folga60a89: Int = 15,
    val folga90a179: Int = 5,
    val madrugada: Int = 15,
    val terminalDiferente: Int = 25,
    val atrasoMax: Int = 30,
    val madrugadaInicioMin: Int = 22 * 60,
    val madrugadaFimMin: Int = 5 * 60 + 59,
    val corteSeguro: Int = 70,
    val corteAtencao: Int = 40,
) {
    init {
        require(corteAtencao in 0..corteSeguro && corteSeguro <= 100) { "cortes inválidos" }
        require(listOf(folgaMenor30, folga30a44, folga45a59, folga60a89, folga90a179, madrugada, terminalDiferente, atrasoMax).all { it >= 0 }) {
            "penalidade não pode ser negativa"
        }
    }

    fun rotulo(nota: Int): RotuloRisco = when {
        nota >= corteSeguro -> RotuloRisco.SEGURO
        nota >= corteAtencao -> RotuloRisco.ATENCAO
        else -> RotuloRisco.ARRISCADO
    }

    /** As chaves da tabela `parametro` (inverso de [deMapa]); o servidor usa isto para semear a tabela. */
    fun paraMapa(): Map<String, Int> = mapOf(
        "risco_folga_menor_30" to folgaMenor30,
        "risco_folga_30_44" to folga30a44,
        "risco_folga_45_59" to folga45a59,
        "risco_folga_60_89" to folga60a89,
        "risco_folga_90_179" to folga90a179,
        "risco_madrugada" to madrugada,
        "risco_terminal_diferente" to terminalDiferente,
        "risco_atraso_max" to atrasoMax,
        "madrugada_inicio_min" to madrugadaInicioMin,
        "madrugada_fim_min" to madrugadaFimMin,
        "risco_corte_seguro" to corteSeguro,
        "risco_corte_atencao" to corteAtencao,
    )

    companion object {
        /** Lê as chaves da tabela `parametro`; chave ausente mantém o valor inicial. */
        fun deMapa(m: Map<String, Int>): ParametrosRisco {
            val d = ParametrosRisco()
            return ParametrosRisco(
                folgaMenor30 = m["risco_folga_menor_30"] ?: d.folgaMenor30,
                folga30a44 = m["risco_folga_30_44"] ?: d.folga30a44,
                folga45a59 = m["risco_folga_45_59"] ?: d.folga45a59,
                folga60a89 = m["risco_folga_60_89"] ?: d.folga60a89,
                folga90a179 = m["risco_folga_90_179"] ?: d.folga90a179,
                madrugada = m["risco_madrugada"] ?: d.madrugada,
                terminalDiferente = m["risco_terminal_diferente"] ?: d.terminalDiferente,
                atrasoMax = m["risco_atraso_max"] ?: d.atrasoMax,
                madrugadaInicioMin = m["madrugada_inicio_min"] ?: d.madrugadaInicioMin,
                madrugadaFimMin = m["madrugada_fim_min"] ?: d.madrugadaFimMin,
                corteSeguro = m["risco_corte_seguro"] ?: d.corteSeguro,
                corteAtencao = m["risco_corte_atencao"] ?: d.corteAtencao,
            )
        }
    }
}

/** De onde vem o atraso típico de uma linha. Hoje só existe a fonte fictícia (RN14). */
interface FonteAtraso {
    fun atrasoTipicoMin(viagem: Viagem): Int
    /** Dado fictício ou estimado: a tela precisa dizer "simulado" (RN14, RNF14). */
    val simulado: Boolean get() = true
}

/**
 * Atraso típico inventado e determinístico, para a demonstração e os testes.
 * Depende da **linha e do horário** (origem, destino e hora de saída no dia), não do id da viagem:
 * o servidor cria viagens novas a cada dia, mas "essa linha costuma atrasar" vale para todas.
 */
object AtrasoFicticio : FonteAtraso {
    /** As 7 viagens do protótipo, pelo padrão "origem>destino@minuto-do-dia". */
    private val doPrototipo = mapOf(
        "SP>CWB@1080" to 20, "CWB>JVL@90" to 10, "JVL>FLN@270" to 5, "SP>CWB@1140" to 20,
        "CWB>FLN@180" to 15, "SP>FLN@1260" to 0, "SP>FLN@1320" to 0,
    )

    override fun atrasoTipicoMin(viagem: Viagem): Int {
        val chave = "${viagem.origem}>${viagem.destino}@${viagem.saidaMin % 1440}"
        return doPrototipo[chave] ?: (((chave.hashCode() and 0x7fffffff) % 5) * 5)   // 0, 5, 10, 15 ou 20
    }
}

/** Risco de uma conexão entre duas viagens: nota 0–100, rótulo e a explicação obrigatória (RN13). */
data class ConexaoRisco(
    val de: Viagem,
    val para: Viagem,
    val folgaMin: Int,
    val nota: Int,
    val rotulo: RotuloRisco,
    /** Sempre os quatro fatores, do que mais pesou para o que menos pesou. */
    val fatores: List<FatorRisco>,
    /** Uma frase com o fator de maior penalidade. */
    val frase: String,
) {
    /** Os dois fatores que mais pesaram, em texto. */
    val fatos: List<String> get() = fatores.take(2).map { it.texto }
}

/** Risco da rota inteira: o da pior conexão (RN14). Rota direta não tem conexão e, portanto, não tem nota. */
data class RiscoRota(val conexoes: List<ConexaoRisco>, val simulado: Boolean) {
    /** A conexão de menor nota; `null` em rota direta. */
    val pior: ConexaoRisco? get() = conexoes.minByOrNull { it.nota }
    val nota: Int? get() = pior?.nota
    val rotulo: RotuloRisco? get() = pior?.rotulo
    /** Para ordenar e filtrar, rota direta conta como 100 (sem conexão). */
    val notaParaOrdenacao: Int get() = nota ?: 100
}

/** RF19: calcula a nota de risco de cada conexão e da rota. Função pura: nunca esconde uma rota (RN14). */
object RiscoConexao {
    private const val DIA = 1440

    fun avaliar(
        rota: Rota,
        params: ParametrosRisco = ParametrosRisco(),
        fonte: FonteAtraso = AtrasoFicticio,
        nomeCidade: (String) -> String = { cidadeDoTerminal(it) },
    ): RiscoRota = RiscoRota(
        conexoes = rota.viagens.zipWithNext { a, b -> avaliarConexao(a, b, params, fonte, nomeCidade) },
        simulado = fonte.simulado,
    )

    fun avaliarConexao(
        de: Viagem,
        para: Viagem,
        params: ParametrosRisco = ParametrosRisco(),
        fonte: FonteAtraso = AtrasoFicticio,
        nomeCidade: (String) -> String = { cidadeDoTerminal(it) },
    ): ConexaoRisco {
        val folga = para.saidaMin - de.chegadaMin
        val atrasoTipico = fonte.atrasoTipicoMin(de)
        val ehMadrugada = emMadrugada(de.chegadaMin, params) || emMadrugada(para.saidaMin, params)
        val terminalDiferente = de.destino != para.origem

        val fatores = listOf(
            FatorRisco(TipoFator.FOLGA, penalidadeDaFolga(folga, params), "Folga de $folga min"),
            FatorRisco(
                TipoFator.ATRASO,
                minOf(params.atrasoMax, atrasoTipico / 2),            // metade do atraso típico, arredondada para baixo
                if (atrasoTipico > 0) "Essa linha costuma atrasar cerca de $atrasoTipico min" else "Sem atraso típico registrado",
            ),
            FatorRisco(
                TipoFator.HORARIO,
                if (ehMadrugada) params.madrugada else 0,
                if (ehMadrugada) "Conexão de madrugada" else "Fora da madrugada",
            ),
            FatorRisco(
                TipoFator.TERMINAL,
                if (terminalDiferente) params.terminalDiferente else 0,
                if (terminalDiferente) "Terminais diferentes: exige deslocamento" else "Mesmo terminal",
            ),
        ).sortedWith(compareByDescending<FatorRisco> { it.penalidade }.thenBy { it.tipo.ordinal })

        val nota = (100 - fatores.sumOf { it.penalidade }).coerceAtLeast(0)
        val rotulo = params.rotulo(nota)
        return ConexaoRisco(
            de = de,
            para = para,
            folgaMin = folga,
            nota = nota,
            rotulo = rotulo,
            fatores = fatores,
            frase = "${rotulo.texto} em ${nomeCidade(de.destino)}: ${fatores.first().texto}.",
        )
    }

    private fun penalidadeDaFolga(folga: Int, p: ParametrosRisco): Int = when {
        folga < 30 -> p.folgaMenor30
        folga < 45 -> p.folga30a44
        folga < 60 -> p.folga45a59
        folga < 90 -> p.folga60a89
        folga < 180 -> p.folga90a179
        else -> 0                                                       // espera longa custa tempo, não risco
    }

    /** Madrugada vale das 22:00 às 05:59, olhando a hora do dia (os minutos podem passar de 1440). */
    private fun emMadrugada(minuto: Int, p: ParametrosRisco): Boolean {
        val hora = ((minuto % DIA) + DIA) % DIA
        return hora >= p.madrugadaInicioMin || hora <= p.madrugadaFimMin
    }

    private fun cidadeDoTerminal(id: String): String = BaseFicticia.terminais.firstOrNull { it.id == id }?.cidade ?: id
}
