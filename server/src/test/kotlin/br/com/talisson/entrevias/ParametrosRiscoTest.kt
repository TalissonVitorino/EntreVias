package br.com.talisson.entrevias

import br.com.talisson.entrevias.domain.ParametrosRisco
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** RF19 / RN13: os parâmetros do risco vivem na tabela `parametro` e chegam ao app por `/api/parametros/risco`. */
class ParametrosRiscoTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parametrosDeRiscoVemDaTabelaParametro() = testApplication {
        // banco próprio do teste: não mexe no entrevias.db de desenvolvimento
        val arquivo = File("build/tmp/teste-risco.db").also { it.parentFile.mkdirs(); it.delete() }
        System.setProperty("entrevias.db", arquivo.absolutePath)
        Banco.iniciar()
        application { module() }

        val padrao = client.get("/api/parametros/risco")
        assertEquals(HttpStatusCode.OK, padrao.status)
        assertEquals(ParametrosRisco(), json.decodeFromString<ParametrosRisco>(padrao.bodyAsText()))

        Banco.conexao().use { c ->
            c.createStatement().use { it.execute("update parametro set valor = 80 where chave = 'risco_corte_seguro'") }
        }
        val alterado = json.decodeFromString<ParametrosRisco>(client.get("/api/parametros/risco").bodyAsText())
        assertEquals(80, alterado.corteSeguro)
        assertEquals(40, alterado.corteAtencao)                 // as outras chaves seguem valendo
    }
}
