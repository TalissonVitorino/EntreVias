package br.com.talisson.entrevias

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.*

/**
 * Um banco criado ANTES da Sprint 1 (esquema em `schema-antes-da-sprint1.sql`, copiado da main) com dados dentro
 * precisa chegar ao esquema atual sem perder nada. Usa um arquivo próprio: não depende do banco único dos outros testes.
 */
class MigracaoTest {
    private lateinit var arquivo: File
    private lateinit var c: Connection

    @BeforeTest fun criarBancoAntigo() {
        arquivo = File.createTempFile("entrevias-antigo", ".db")
        c = DriverManager.getConnection("jdbc:sqlite:${arquivo.absolutePath}")
        c.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
        val sql = javaClass.getResourceAsStream("/schema-antes-da-sprint1.sql")!!.bufferedReader().readText()
        sql.lines().map { it.substringBefore("--") }.joinToString("\n").split(";").map { it.trim() }.filter { it.isNotEmpty() }
            .forEach { cmd -> c.createStatement().use { it.execute(cmd) } }
        c.createStatement().use {
            it.execute("insert into empresa (id, nome) values (1, 'Empresa Antiga')")
            it.execute("insert into usuario (id, email, senha_hash, nome, criado_em) values (1, 'antigo@teste.com', 'hash-antigo', 'Pessoa Antiga', 100)")
        }
    }

    @AfterTest fun limpar() { c.close(); arquivo.delete() }

    private fun colunas(t: String) = c.createStatement().use { s -> s.executeQuery("pragma table_info($t)").use { rs -> buildSet { while (rs.next()) add(rs.getString("name")) } } }
    private fun tabelas() = c.createStatement().use { s -> s.executeQuery("select name from sqlite_master where type = 'table'").use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } } }
    private fun um(sql: String) = c.createStatement().use { s -> s.executeQuery(sql).use { it.next(); it.getString(1) } }

    @Test fun oBancoAntigoNaoTemNadaDaSprint1() {
        // garante que o ponto de partida do teste é de fato o esquema antigo
        assertFalse("papel" in colunas("usuario")); assertFalse("celular" in colunas("usuario"))
        assertFalse("sigla" in colunas("empresa")); assertFalse("cnpj" in colunas("empresa"))
        assertFalse("sessao" in tabelas()); assertFalse("tentativa_login" in tabelas())
        assertFalse("email_verificado_em" in colunas("usuario")); assertFalse("codigo_verificacao" in tabelas())
    }

    @Test fun migraAcrescentandoColunasETabelasSemPerderDados() {
        Banco.migrar(c)

        assertTrue(setOf("celular", "papel", "aceitou_termos_em", "email_verificado_em").all { it in colunas("usuario") })
        assertTrue(setOf("sigla", "cnpj").all { it in colunas("empresa") })
        assertTrue(setOf("sessao", "tentativa_login", "codigo_verificacao").all { it in tabelas() })

        assertEquals("antigo@teste.com", um("select email from usuario where id = 1"))
        assertEquals("hash-antigo", um("select senha_hash from usuario where id = 1"))
        assertEquals("viajante", um("select papel from usuario where id = 1"), "usuário antigo vira viajante, nunca gestor")
        assertNull(c.createStatement().use { s -> s.executeQuery("select email_verificado_em from usuario where id = 1").use { it.next(); it.getString(1) } }, "conta antiga começa sem e-mail verificado")
        assertEquals("Empresa Antiga", um("select nome from empresa where id = 1"))
        assertNull(c.createStatement().use { s -> s.executeQuery("select cnpj from empresa where id = 1").use { it.next(); it.getString(1) } })
    }

    @Test fun migrarDuasVezesNaoFazNadaDeNovo() {
        Banco.migrar(c)
        val antes = tabelas() to colunas("usuario")
        Banco.migrar(c)
        assertEquals(antes, tabelas() to colunas("usuario"))
        assertEquals("1", um("select count(*) from usuario"))
    }

    @Test fun regrasDoEsquemaNovoValemNoBancoMigrado() {
        Banco.migrar(c)
        // papel inválido é recusado pelo CHECK mesmo numa coluna acrescentada com ALTER TABLE
        assertFailsWith<SQLException> { c.createStatement().use { it.execute("update usuario set papel = 'dono' where id = 1") } }
        // CNPJ único, mas várias empresas sem CNPJ são permitidas
        c.createStatement().use {
            it.execute("insert into empresa (nome, cnpj) values ('A', '11222333000181')")
            it.execute("insert into empresa (nome) values ('B')"); it.execute("insert into empresa (nome) values ('C')")
        }
        assertFailsWith<SQLException> { c.createStatement().use { it.execute("insert into empresa (nome, cnpj) values ('D', '11222333000181')") } }
        // sessão acompanha a exclusão do usuário
        c.createStatement().use {
            it.execute("insert into sessao (token_hash, usuario_id, criada_em, expira_em) values ('x', 1, 1, 2)")
            it.execute("delete from usuario where id = 1")
        }
        assertEquals("0", um("select count(*) from sessao"))
    }
}
