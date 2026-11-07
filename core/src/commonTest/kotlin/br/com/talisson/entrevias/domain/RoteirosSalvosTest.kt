package br.com.talisson.entrevias.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RoteirosSalvosTest {
    private fun v(id: Int, o: String, d: String, s: Int = 360, c: Int = 600) =
        Viagem(id, "Empresa", "Convencional", o, d, s, c, 5990, 10, listOf("Ar-condicionado"))

    private fun roteiro(id: Int = 1, pax: Int = 2) =
        RoteiroSalvo(listOf(v(id, "CWB", "JVL"), v(id + 1, "JVL", "FLN", 700, 900)), ParametrosConexao(30, 60, 480), 2026, 11, 20, pax)

    @Test fun ida_e_volta_do_armazenamento_nao_perde_nada() {
        val lista = listOf(roteiro(1, 1), roteiro(10, 3))
        assertEquals(lista, RoteirosSalvos.decodificar(RoteirosSalvos.codificar(lista)))
    }

    @Test fun nada_guardado_ou_texto_estragado_vira_lista_vazia() {
        listOf(null, "", "   ", "isso nao e json", "[]", "{}", """{"roteiros": 3}""", """{"versao":1,"roteiros":"x"}""").forEach {
            assertEquals(emptyList(), RoteirosSalvos.decodificar(it), "'$it'")
        }
    }

    @Test fun item_invalido_e_descartado_e_os_outros_sobrevivem() {
        val bom = RoteirosSalvos.codificar(listOf(roteiro(1), roteiro(10)))
        // um item com chegada antes da saída (o init de Viagem recusa) no meio dos bons
        val ruim = """{"viagens":[{"id":99,"empresa":"X","categoria":"Leito","origem":"A","destino":"B","saidaMin":500,"chegadaMin":100,"precoCentavos":1}],"ano":2026,"mes":11,"dia":20,"pax":1}"""
        val misturado = bom.replaceFirst("\"roteiros\":[", "\"roteiros\":[$ruim,")
        assertNotEquals(bom, misturado, "a substituição não aconteceu: o teste não provaria nada")
        assertTrue("chegadaMin\":100" in misturado)
        assertEquals(listOf(roteiro(1), roteiro(10)), RoteirosSalvos.decodificar(misturado))
    }

    @Test fun roteiro_que_o_app_nao_saberia_abrir_nao_entra() {
        listOf(
            roteiro().copy(viagens = emptyList()),
            roteiro().copy(viagens = listOf(v(1, "CWB", "JVL"), v(2, "XXX", "FLN", 700, 900))),   // não encadeia
            roteiro().copy(pax = 0), roteiro().copy(pax = 7),
            roteiro().copy(mes = 13), roteiro().copy(dia = 0), roteiro().copy(ano = 1),
        ).forEach {
            assertTrue(!RoteirosSalvos.valido(it), "aceitou $it")
            assertEquals(emptyList(), RoteirosSalvos.decodificar(RoteirosSalvos.codificar(listOf(it))))
        }
    }

    @Test fun nunca_passa_do_limite_nem_repete() {
        val doze = (1..12).map { roteiro(it * 10) }
        assertEquals(10, RoteirosSalvos.decodificar(RoteirosSalvos.codificar(doze)).size)
        assertEquals(1, RoteirosSalvos.decodificar(RoteirosSalvos.codificar(listOf(roteiro(), roteiro()))).size)
    }

    @Test fun campo_novo_de_versao_futura_nao_quebra_a_leitura() {
        val futuro = RoteirosSalvos.codificar(listOf(roteiro())).replaceFirst("\"pax\":", "\"campoDoFuturo\":true,\"pax\":")
        assertTrue("campoDoFuturo" in futuro, "a substituição não aconteceu")
        assertEquals(listOf(roteiro()), RoteirosSalvos.decodificar(futuro))
    }
}
