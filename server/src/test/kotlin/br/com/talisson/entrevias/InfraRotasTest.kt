package br.com.talisson.entrevias

import br.com.talisson.entrevias.domain.InfraTerminal
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.*

/** B4: infraestrutura dos terminais (dados fictícios, públicos). */
class InfraRotasTest {
    private val json = Json { ignoreUnknownKeys = true }
    @BeforeTest fun preparar() { BancoDeTeste.pronto }

    private fun infra() = Banco.infraDosTerminais()

    @Test fun todoTerminalDoBancoTemInfraEVice_versa() {
        val terminais = Banco.terminais().map { it.id }.toSet(); val infra = infra().map { it.terminal }.toSet()
        assertEquals(terminais, infra, "um terminal sem infra faria o filtro de acessibilidade errar sem aviso")
    }

    @Test fun endpointEhPublicoEDevolveOsMesmosDados() = testApplication {
        application { module() }
        val r = client.get("/api/terminais/infra")
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(infra(), json.decodeFromString(ListSerializer(InfraTerminal.serializer()), r.bodyAsText()))
    }

    @Test fun valoresDeExemploFazemSentido() {
        val m = infra().associateBy { it.terminal }
        assertTrue(m.getValue("CWB").acessivel); assertFalse(m.getValue("JVL").acessivel, "Joinville não tem rampa/elevador"); assertFalse(m.getValue("VIT").acessivel, "Vitória não tem banheiro acessível")
        assertTrue(infra().all { it.caminhadaM in 0..500 })
    }
}
