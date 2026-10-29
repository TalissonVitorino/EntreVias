package br.com.talisson.entrevias

import java.io.File

/**
 * Um banco só para todos os testes do servidor: o `Banco` lê o caminho uma única vez, então este objeto o fixa
 * antes do primeiro uso e semeia a base. Não mexe no entrevias.db de desenvolvimento.
 * Teste que altera um parâmetro deve devolver o valor original no fim.
 */
object BancoDeTeste {
    val pronto: Unit by lazy {
        val arquivo = File("build/tmp/teste-servidor.db").also { it.parentFile.mkdirs(); it.delete() }
        System.setProperty("entrevias.db", arquivo.absolutePath)
        Banco.iniciar()
    }

    fun definirParametro(chave: String, valor: Int) {
        Banco.conexao().use { c ->
            c.prepareStatement("update parametro set valor = ? where chave = ?").use { st -> st.setInt(1, valor); st.setString(2, chave); st.executeUpdate() }
        }
    }
}
