package br.com.talisson.entrevias

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArmazenamentoArquivoTest {
    private fun pasta(): File = File(createTempDirectory("entrevias-arm").toFile(), "dados")

    @Test fun gravaLeEApaga() {
        val a = armazenamentoEmPasta(pasta())
        assertNull(a.ler("sessao"))
        a.gravar("sessao", "valor-1")
        assertEquals("valor-1", a.ler("sessao"))
        a.gravar("sessao", "valor-2")
        assertEquals("valor-2", a.ler("sessao"))
        a.gravar("sessao", null)
        assertNull(a.ler("sessao"))
    }

    @Test fun sobreviveAUmaNovaInstanciaComoUmAppReaberto() {
        val p = pasta()
        armazenamentoEmPasta(p).gravar("sessao", "token-do-dia-anterior")
        assertEquals("token-do-dia-anterior", armazenamentoEmPasta(p).ler("sessao"))
    }

    @Test fun arquivoEPastaSoLegiveisPeloDono() {
        val p = pasta()
        armazenamentoEmPasta(p).gravar("sessao", "segredo")
        val perms = { f: File -> Files.getPosixFilePermissions(f.toPath()) }
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms(File(p, "sessao")))
        assertFalse(perms(p).any { it.name.startsWith("GROUP") || it.name.startsWith("OTHERS") })
    }

    @Test fun nomeDeChaveNaoEscapaDaPasta() {
        val p = pasta()
        armazenamentoEmPasta(p).gravar("../../fora", "x")
        assertTrue(File(p, "fora").isFile, "a chave é reduzida a letras e dígitos")
        assertFalse(File(p.parentFile.parentFile, "fora").exists())
    }
}
