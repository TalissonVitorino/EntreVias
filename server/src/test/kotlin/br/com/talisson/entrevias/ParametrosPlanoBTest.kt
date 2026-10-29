package br.com.talisson.entrevias

import br.com.talisson.entrevias.domain.ParametrosPlanoB
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** RF20 / RN15: as regras do plano B vivem na tabela `parametro` e chegam ao app por `/api/parametros/planob`. */
class ParametrosPlanoBTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parametrosDoPlanoBVemDaTabelaParametro() = testApplication {
        BancoDeTeste.pronto
        application { module() }

        val padrao = client.get("/api/parametros/planob")
        assertEquals(HttpStatusCode.OK, padrao.status)
        assertEquals(ParametrosPlanoB(), json.decodeFromString<ParametrosPlanoB>(padrao.bodyAsText()))

        try {
            BancoDeTeste.definirParametro("planob_max_opcoes", 2)
            val alterado = json.decodeFromString<ParametrosPlanoB>(client.get("/api/parametros/planob").bodyAsText())
            assertEquals(ParametrosPlanoB(maxOpcoes = 2, minMin = 15, janelaH = 12), alterado)
        } finally {
            BancoDeTeste.definirParametro("planob_max_opcoes", ParametrosPlanoB().maxOpcoes)
        }
    }
}
