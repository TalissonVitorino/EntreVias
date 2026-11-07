package br.com.talisson.entrevias

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * Desktop: um arquivo na pasta do usuário, legível só por ele (permissão 600; pasta 700) onde o sistema suporta.
 * NÃO é cifrado: quem tem acesso à conta do usuário no computador lê o token.
 */
private class ArmazenamentoArquivo(private val pasta: File) : ArmazenamentoLocal {
    private fun arquivo(chave: String) = File(pasta, chave.filter { it.isLetterOrDigit() })

    override fun ler(chave: String): String? = runCatching { arquivo(chave).takeIf { it.isFile }?.readText() }.getOrNull()

    override fun gravar(chave: String, valor: String?) {
        runCatching {
            val f = arquivo(chave)
            if (valor == null) { f.delete(); return }
            if (!pasta.exists()) {
                pasta.mkdirs()
                runCatching { Files.setPosixFilePermissions(pasta.toPath(), PosixFilePermissions.fromString("rwx------")) }
            }
            if (!f.exists()) {
                runCatching { Files.createFile(f.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))) }
            }
            f.writeText(valor)
        }
    }
}

actual fun criarArmazenamento(): ArmazenamentoLocal = ArmazenamentoArquivo(File(System.getProperty("user.home"), ".entrevias"))

internal fun armazenamentoEmPasta(pasta: File): ArmazenamentoLocal = ArmazenamentoArquivo(pasta)
