package br.com.talisson.entrevias

import br.com.talisson.entrevias.domain.ParametrosConexao
import br.com.talisson.entrevias.domain.ParametrosRisco
import br.com.talisson.entrevias.domain.Terminal
import br.com.talisson.entrevias.domain.Viagem
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

expect fun urlBase(): String

/** Fala com o servidor EntreVias. Nada de horário ou preço fica escrito no app. */
class Api(private val base: String = urlBase()) {
    private val http = HttpClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

    suspend fun terminais(): List<Terminal> = http.get("$base/api/cidades").body()
    suspend fun parametros(): ParametrosConexao = http.get("$base/api/parametros").body()
    suspend fun parametrosRisco(): ParametrosRisco = http.get("$base/api/parametros/risco").body()
    suspend fun viagens(data: Data, dias: Int = 3): List<Viagem> =
        http.get("$base/api/viagens") { parameter("data", data.iso()); parameter("dias", dias) }.body()
}
