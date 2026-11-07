package br.com.talisson.entrevias

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextosLegaisTest {
    @Test fun cadaDocumentoTemSecoesComTextoDeVerdade() {
        Documento.entries.forEach { d ->
            val secoes = TextosLegais.secoes(d)
            assertTrue(secoes.size >= 5, "${d.titulo} tem poucas seções")
            secoes.forEach { s ->
                assertTrue(s.titulo.isNotBlank(), "seção sem título em ${d.titulo}")
                assertTrue(s.paragrafos.isNotEmpty() && s.paragrafos.all { it.length > 20 }, "seção '${s.titulo}' vazia ou curta")
            }
        }
    }

    @Test fun politicaCobreOsPontosDaLgpd() {
        val texto = TextosLegais.privacidade.joinToString(" ") { it.titulo + " " + it.paragrafos.joinToString(" ") }
        listOf("art. 18", "consentimento", "hash", "Compartilhamento", "Quanto tempo").forEach { assertTrue(it in texto, "falta: $it") }
    }

    @Test fun ehRascunhoEOsCamposAbertosEstaoEntreColchetes() {
        assertTrue(TextosLegais.VERSAO.startsWith("Rascunho"))
        assertTrue("colchetes" in TextosLegais.AVISO)
        val todos = Documento.entries.flatMap { TextosLegais.secoes(it) }.flatMap { it.paragrafos }
        val abertos = todos.filter { "[" in it }
        assertTrue(abertos.isNotEmpty(), "se não há pendência, o aviso de rascunho já não faz sentido")
        abertos.forEach { assertEquals(it.count { c -> c == '[' }, it.count { c -> c == ']' }, "colchete sem fechar: $it") }
    }

    @Test fun naoPrometeOQueNaoExiste() {
        val texto = Documento.entries.flatMap { TextosLegais.secoes(it) }.flatMap { it.paragrafos }.joinToString(" ")
        assertTrue("ainda não está disponível" in texto, "a exclusão de conta não existe e o texto precisa dizer isso")
        assertTrue("Não existe compra real" in texto)
    }
}
