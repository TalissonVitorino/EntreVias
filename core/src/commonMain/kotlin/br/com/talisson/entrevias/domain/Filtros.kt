package br.com.talisson.entrevias.domain

import kotlinx.serialization.Serializable

/** B4: o que cada terminal oferece e a caminhada entre desembarque e embarque. Dados fictícios, servidos pelo servidor. */
@Serializable
data class InfraTerminal(
    val terminal: String,
    val banheiroAcessivel: Boolean,
    val rampaElevador: Boolean,
    val lanchonete: Boolean,
    val guardaVolumes: Boolean,
    val aberto24h: Boolean,
    val caminhadaM: Int,
) {
    /** "Terminal acessível" = banheiro acessível E rampa ou elevador. */
    val acessivel get() = banheiroAcessivel && rampaElevador
}

/** Faixas de partida (B2). Minutos desde 00:00; o fim é exclusivo. */
enum class FaixaHorario(val rotulo: String, val deMin: Int, val ateMin: Int) {
    MADRUGADA("Madrugada", 0, 360), MANHA("Manhã", 360, 720), TARDE("Tarde", 720, 1080), NOITE("Noite", 1080, 1440)
}

/**
 * B2 (US1, RQ20). Cada grupo marcado restringe; grupo vazio não restringe. Dentro de um grupo vale "qualquer um"
 * (empresa e tipo de serviço: a rota passa se algum trecho usa o marcado). Preço é o total POR PESSOA.
 */
data class FiltroDeRotas(
    val horarios: Set<FaixaHorario> = emptySet(),
    val empresas: Set<String> = emptySet(),
    val categorias: Set<String> = emptySet(),
    val precoMaxCentavos: Int? = null,
    val soTerminaisAcessiveis: Boolean = false,
) {
    val ativo get() = horarios.isNotEmpty() || empresas.isNotEmpty() || categorias.isNotEmpty() || precoMaxCentavos != null || soTerminaisAcessiveis
    val quantosGrupos get() = listOf(horarios.isNotEmpty(), empresas.isNotEmpty(), categorias.isNotEmpty(), precoMaxCentavos != null, soTerminaisAcessiveis).count { it }

    /** @param infra o que se sabe de cada terminal; terminal sem informação NÃO conta como acessível (cautela). */
    fun aceita(r: Rota, infra: Map<String, InfraTerminal> = emptyMap()): Boolean {
        if (horarios.isNotEmpty()) {
            val min = r.saidaMin.mod(1440)
            if (horarios.none { min >= it.deMin && min < it.ateMin }) return false
        }
        if (empresas.isNotEmpty() && r.viagens.none { it.empresa in empresas }) return false
        if (categorias.isNotEmpty() && r.viagens.none { it.categoria in categorias }) return false
        if (precoMaxCentavos != null && r.totalCentavos > precoMaxCentavos) return false
        if (soTerminaisAcessiveis) {
            val terminais = r.viagens.flatMap { listOf(it.origem, it.destino) }.distinct()
            if (terminais.any { infra[it]?.acessivel != true }) return false
        }
        return true
    }

    fun aplicar(rotas: List<Rota>, infra: Map<String, InfraTerminal> = emptyMap()) = if (!ativo) rotas else rotas.filter { aceita(it, infra) }
}
