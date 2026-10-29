package br.com.talisson.entrevias.domain

import kotlinx.serialization.Serializable

/**
 * RN15: regras do plano B (PLANO.md, seção 3.3.2; decisão provisória). No servidor ficam na tabela `parametro`.
 * [minMin] é quanto depois da chegada prevista a saída precisa ser; [janelaH] é até quantas horas depois.
 */
@Serializable
data class ParametrosPlanoB(val maxOpcoes: Int = 3, val minMin: Int = 15, val janelaH: Int = 12) {
    init {
        require(maxOpcoes >= 1) { "maxOpcoes deve ser pelo menos 1" }
        require(minMin >= 0) { "minMin não pode ser negativo" }
        require(janelaH >= 1) { "janelaH deve ser pelo menos 1" }
    }

    fun paraMapa(): Map<String, Int> = mapOf(
        "planob_max_opcoes" to maxOpcoes,
        "planob_min_min" to minMin,
        "planob_janela_h" to janelaH,
    )

    companion object {
        /** Lê as chaves da tabela `parametro`; chave ausente mantém o valor inicial. */
        fun deMapa(m: Map<String, Int>): ParametrosPlanoB {
            val d = ParametrosPlanoB()
            return ParametrosPlanoB(
                maxOpcoes = m["planob_max_opcoes"] ?: d.maxOpcoes,
                minMin = m["planob_min_min"] ?: d.minMin,
                janelaH = m["planob_janela_h"] ?: d.janelaH,
            )
        }
    }
}

enum class TipoPlanoB {
    /** Outra saída do mesmo terminal para a mesma cidade da viagem perdida. */
    MESMA_CIDADE,
    /** Rota nova do terminal até o destino final, quando não há saída para a mesma cidade. */
    REPLANEJADA,
}

/** Uma alternativa se a conexão for perdida. Valores em centavos, por pessoa. */
data class OpcaoPlanoB(
    val tipo: TipoPlanoB,
    /** Bilhetes que o usuário precisa comprar. */
    val novas: List<Viagem>,
    /** Bilhetes já comprados que continuam valendo. */
    val aproveitadas: List<Viagem>,
    /** Chegada ao destino final, em minutos desde 00:00 do dia da busca; `null` se não há como completar a viagem. */
    val chegadaFinalMin: Int?,
    /** Chegada final nova menos a original (negativo = chega antes). */
    val atrasoMin: Int?,
    val novosBilhetesCentavos: Int,
    /** Bilhetes comprados que deixam de ser usados e talvez não sejam reembolsados (RN09, DM03). */
    val bilhetesNaoUsadosCentavos: Int,
)

/** Plano B de uma conexão. Sem opções, a tela diz "sem outra saída nas próximas 12 h". */
data class PlanoB(val opcoes: List<OpcaoPlanoB>) {
    val semOpcao: Boolean get() = opcoes.isEmpty()
}

/** RF20: o que fazer se a conexão for perdida. Sempre uma alternativa, nunca uma proteção (RN07). */
object PlanoBConexao {
    /**
     * @param conexao índice da conexão na rota (0 = entre a 1ª e a 2ª viagem); a viagem perdida é a seguinte.
     * @param viagens as viagens disponíveis (a base do dia); a viagem perdida nunca é sugerida.
     */
    fun avaliar(
        rota: Rota,
        conexao: Int,
        viagens: List<Viagem>,
        params: ParametrosPlanoB = ParametrosPlanoB(),
        conexaoParams: ParametrosConexao = rota.params,
        cidade: (String) -> String = { cidadeDoTerminal(it) },
    ): PlanoB {
        require(conexao in 0 until rota.viagens.size - 1) { "conexão $conexao não existe nesta rota" }
        val de = rota.viagens[conexao]
        val perdida = rota.viagens[conexao + 1]
        val restantes = rota.viagens.drop(conexao + 2)
        val disponiveis = viagens.filter { it.id != perdida.id }
        val chegada = de.chegadaMin
        val saidas = (chegada + params.minMin)..(chegada + params.janelaH * 60)

        // A: outras saídas do mesmo terminal para a mesma cidade da viagem perdida, de qualquer empresa.
        val mesmaCidade = disponiveis
            .filter { it.origem == de.destino && cidade(it.destino) == cidade(perdida.destino) && it.saidaMin in saidas }
            .map { v -> opcaoMesmaCidade(v, rota, perdida, restantes, disponiveis, params, conexaoParams) }
            .sortedWith(compareBy({ it.chegadaFinalMin ?: Int.MAX_VALUE }, { it.novosBilhetesCentavos }))
            .take(params.maxOpcoes)
        if (mesmaCidade.isNotEmpty()) return PlanoB(mesmaCidade)

        // B: sem saída para a mesma cidade, replaneja do terminal até o destino final.
        val nova = melhorRota(de.destino, rota.destino, disponiveis, saidas, conexaoParams) ?: return PlanoB(emptyList())
        val opcao = montar(TipoPlanoB.REPLANEJADA, emptyList(), nova.viagens, nova.chegadaMin, rota, perdida, restantes)
        return PlanoB(listOf(opcao))
    }

    private fun opcaoMesmaCidade(
        v: Viagem, rota: Rota, perdida: Viagem, restantes: List<Viagem>, disponiveis: List<Viagem>,
        params: ParametrosPlanoB, conexaoParams: ParametrosConexao,
    ): OpcaoPlanoB {
        val seguinte = restantes.firstOrNull()
        val primeiras = listOf(v)
        return when {
            // a perdida era a última: chegar à cidade dela é chegar ao fim
            seguinte == null -> montar(TipoPlanoB.MESMA_CIDADE, primeiras, emptyList(), v.chegadaMin, rota, perdida, restantes)
            // ainda dá tempo (RN01) de pegar o trecho que já estava comprado
            v.destino == seguinte.origem && seguinte.saidaMin - v.chegadaMin >= conexaoParams.minimaMin ->
                montar(TipoPlanoB.MESMA_CIDADE, primeiras, restantes, rota.chegadaMin, rota, perdida, restantes)
            v.destino == rota.destino ->
                montar(TipoPlanoB.MESMA_CIDADE, primeiras, emptyList(), v.chegadaMin, rota, perdida, restantes)
            // não dá tempo: replaneja o resto a partir de onde esta saída chega
            else -> {
                val saidas = (v.chegadaMin + conexaoParams.minimaMin)..(v.chegadaMin + params.janelaH * 60)
                val resto = melhorRota(v.destino, rota.destino, disponiveis, saidas, conexaoParams)
                montar(TipoPlanoB.MESMA_CIDADE, primeiras, resto?.viagens ?: emptyList(), resto?.chegadaMin, rota, perdida, restantes)
            }
        }
    }

    /** A rota que chega primeiro (empate: a mais barata); `null` se não há. */
    private fun melhorRota(origem: String, destino: String, viagens: List<Viagem>, saidas: IntRange, params: ParametrosConexao): Rota? =
        if (origem == destino) null
        else MotorDeRotas.buscar(origem, destino, viagens, params, limite = 200, partida = saidas)
            .minWithOrNull(compareBy<Rota>({ it.chegadaMin }, { it.totalCentavos }))

    private fun montar(
        tipo: TipoPlanoB, primeiras: List<Viagem>, resto: List<Viagem>, chegadaFinal: Int?,
        rota: Rota, perdida: Viagem, restantes: List<Viagem>,
    ): OpcaoPlanoB {
        val jaComprados = restantes.map { it.id }.toSet()
        val novas = primeiras + resto.filter { it.id !in jaComprados }
        val aproveitadas = resto.filter { it.id in jaComprados }
        val usados = resto.map { it.id }.toSet()
        val naoUsados = perdida.precoCentavos + restantes.filter { it.id !in usados }.sumOf { it.precoCentavos }
        return OpcaoPlanoB(
            tipo = tipo,
            novas = novas,
            aproveitadas = aproveitadas,
            chegadaFinalMin = chegadaFinal,
            atrasoMin = chegadaFinal?.let { it - rota.chegadaMin },
            novosBilhetesCentavos = novas.sumOf { it.precoCentavos },
            bilhetesNaoUsadosCentavos = naoUsados,
        )
    }

    private fun cidadeDoTerminal(id: String): String = BaseFicticia.terminais.firstOrNull { it.id == id }?.cidade ?: id
}
