package br.com.talisson.entrevias.domain

enum class Ordem { MAIS_BARATA, MAIS_RAPIDA, MAIS_CONFORTO, MENOS_BALDEACOES }

/** RF01 + RF02: busca rotas diretas e com baldeação, aplicando a RN01. */
object MotorDeRotas {
    fun buscar(
        origem: String,
        destino: String,
        viagens: List<Viagem>,
        params: ParametrosConexao = ParametrosConexao(),
        maxBaldeacoes: Int = 2,
        ordem: Ordem = Ordem.MAIS_BARATA,
        limite: Int = 26,
        partida: IntRange? = null,          // janela de saída da 1ª viagem (ex.: o dia escolhido)
    ): List<Rota> {
        if (origem == destino) return emptyList()
        val rotas = mutableListOf<Rota>()
        fun avancar(atual: List<Viagem>, visitados: Set<String>) {
            val ultima = atual.last()
            if (ultima.destino == destino) { rotas += Rota(atual, params); return }
            if (atual.size - 1 >= maxBaldeacoes) return
            for (prox in viagens) {
                if (prox.origem != ultima.destino) continue
                if (prox.destino in visitados) continue            // não volta a uma cidade já visitada
                val espera = prox.saidaMin - ultima.chegadaMin
                if (espera < params.minimaMin) continue   // RN01: mínimo
                if (espera > params.maximaMin) continue   // espera longa demais [hipótese]
                avancar(atual + prox, visitados + prox.destino)
            }
        }
        for (v in viagens) if (v.origem == origem && (partida == null || v.saidaMin in partida)) avancar(listOf(v), setOf(origem, v.destino))
        return ordenar(rotas, ordem).take(limite)
    }

    fun ordenar(rotas: List<Rota>, ordem: Ordem): List<Rota> = when (ordem) {
        Ordem.MAIS_BARATA -> rotas.sortedWith(compareBy({ it.totalCentavos }, { it.duracaoMin }))
        Ordem.MAIS_RAPIDA -> rotas.sortedWith(compareBy({ it.duracaoMin }, { it.totalCentavos }))
        Ordem.MAIS_CONFORTO -> rotas.sortedWith(compareBy({ -it.confortoIndice }, { it.totalCentavos }))
        Ordem.MENOS_BALDEACOES -> rotas.sortedWith(compareBy({ it.baldeacoes }, { it.totalCentavos }))
    }
}
