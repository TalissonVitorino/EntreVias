package br.com.talisson.entrevias.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.encodeToJsonElement

/** Um roteiro guardado no aparelho: as viagens como estavam, o dia da busca e quantas pessoas (RF07, US22). */
@Serializable
data class RoteiroSalvo(
    val viagens: List<Viagem>,
    val params: ParametrosConexao = ParametrosConexao(),
    val ano: Int, val mes: Int, val dia: Int,
    val pax: Int,
)

/**
 * Texto que vai para o armazenamento do aparelho. Quem lê é tolerante: texto estragado, versão desconhecida
 * ou um item inválido nunca derrubam o app nem os outros itens; o que não presta é descartado.
 */
object RoteirosSalvos {
    const val VERSAO = 1
    const val MAX_PAX = 6
    private val json = Json { ignoreUnknownKeys = true }

    fun codificar(roteiros: List<RoteiroSalvo>): String = buildJsonObject {
        put("versao", VERSAO)
        put("roteiros", json.encodeToJsonElement(roteiros))
    }.toString()

    fun decodificar(texto: String?): List<RoteiroSalvo> {
        if (texto.isNullOrBlank()) return emptyList()
        val itens = runCatching { (json.parseToJsonElement(texto) as? JsonObject)?.get("roteiros") as? JsonArray }.getOrNull() ?: return emptyList()
        return itens.mapNotNull { runCatching { json.decodeFromJsonElement<RoteiroSalvo>(it) }.getOrNull()?.takeIf(::valido) }
            .distinct().take(LimiteRoteiros.GRATUITO)
    }

    /** Roteiro que o app consegue abrir sem quebrar: viagens encadeadas, pessoas e data plausíveis. */
    fun valido(r: RoteiroSalvo): Boolean =
        r.viagens.isNotEmpty() && r.viagens.size <= 5 &&
            r.viagens.zipWithNext().all { (a, b) -> a.destino == b.origem } &&
            r.pax in 1..MAX_PAX && r.ano in 2000..2100 && r.mes in 1..12 && r.dia in 1..31
}
