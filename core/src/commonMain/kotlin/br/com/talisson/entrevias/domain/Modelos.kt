package br.com.talisson.entrevias.domain

import kotlinx.serialization.Serializable

/** Cidade com terminal rodoviário. Dados fictícios (RNF02). */
@Serializable
data class Terminal(val id: String, val cidade: String, val uf: String, val nome: String)

/** Um trecho vendido por uma empresa. Horários em minutos desde 00:00 do dia da busca (RN01/RF03). */
@Serializable
data class Viagem(
    val id: Int,
    val empresa: String,
    val categoria: String,
    val origem: String,          // id do Terminal
    val destino: String,         // id do Terminal
    val saidaMin: Int,
    val chegadaMin: Int,
    val precoCentavos: Int,      // dinheiro sempre em centavos
    val atualizadoEmMin: Int = 0,
    val comodidades: List<String> = emptyList(),
) {
    init { require(chegadaMin > saidaMin) { "chegada deve ser depois da saída" } }
}

/** Parâmetros da RN01. Valores são hipóteses a validar com o grupo. */
@Serializable
data class ParametrosConexao(val minimaMin: Int = 30, val apertadaMin: Int = 60, val maximaMin: Int = 480) {
    init { require(minimaMin in 0..apertadaMin && apertadaMin <= maximaMin) }
}

/** Uma rota = sequência de viagens ligadas pelos terminais (RF01, RF02). */
data class Rota(val viagens: List<Viagem>, val params: ParametrosConexao = ParametrosConexao()) {
    val origem get() = viagens.first().origem
    val destino get() = viagens.last().destino
    val baldeacoes get() = viagens.size - 1
    val totalCentavos get() = viagens.sumOf { it.precoCentavos }
    val saidaMin get() = viagens.first().saidaMin
    val chegadaMin get() = viagens.last().chegadaMin
    val duracaoMin get() = chegadaMin - saidaMin
    /** Identificador estável da rota (ids das viagens). */
    val id get() = viagens.joinToString("-") { it.id.toString() }
    /** Conforto médio das categorias (Convencional 1 .. Leito 4), vezes 100. */
    val confortoIndice get() = viagens.sumOf { Categorias.rank(it.categoria) * 100 } / viagens.size
    /** Espera em cada baldeação, em minutos. */
    val esperasMin: List<Int> get() = viagens.zipWithNext { a, b -> b.saidaMin - a.chegadaMin }
    /** RN01: conexão abaixo do limite de aviso fica visível, mas marcada como apertada. */
    val temConexaoApertada get() = esperasMin.any { it < params.apertadaMin }
}

object Categorias {
    val todas = listOf("Convencional", "Executivo", "Semi-leito", "Leito")
    fun rank(c: String) = todas.indexOf(c) + 1
}
