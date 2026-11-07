package br.com.talisson.entrevias.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ValidacaoTest {
    private val ok = CadastroPedido("Maria Souza", "maria@email.com", "(41) 99999-0000", "senha-forte-1", aceitaTermos = true)

    @Test fun cadastroCompletoEValido() = assertNull(Validacao.cadastro(ok))

    @Test fun semAceiteDosTermosNaoCadastra() {
        // RQ11: o aceite é obrigatório e nunca vem marcado
        assertNotNull(Validacao.cadastro(ok.copy(aceitaTermos = false)))
        assertFalse(CadastroPedido("Maria", "m@e.com", senha = "12345678").aceitaTermos)
    }

    @Test fun senhaCurtaOuGrandeDemaisEhRecusada() {
        assertNotNull(Validacao.senha("1234567"))
        assertNull(Validacao.senha("12345678"))
        assertNull(Validacao.senha("x".repeat(128)))
        assertNotNull(Validacao.senha("x".repeat(129)))
    }

    @Test fun emailPrecisaTerFormaDeEmail() {
        listOf("", "maria", "maria@", "maria@email", "@email.com", "ma ria@email.com").forEach { assertNotNull(Validacao.email(it), "'$it'") }
        assertNull(Validacao.email("  Maria@Email.COM "))
        assertEquals("maria@email.com", Validacao.normalizarEmail("  Maria@Email.COM "))
    }

    @Test fun nomeEntreDoisECemLetras() {
        assertNotNull(Validacao.nome("A")); assertNotNull(Validacao.nome(" ")); assertNotNull(Validacao.nome("x".repeat(101)))
        assertNull(Validacao.nome("Jo"))
    }

    @Test fun celularEhOpcionalMasSeVierPrecisaDeDdd() {
        assertNull(Validacao.celular(null)); assertNull(Validacao.celular(""))
        assertNull(Validacao.celular("(41) 99999-0000")); assertNull(Validacao.celular("4133334444"))
        assertNotNull(Validacao.celular("99999-0000")); assertNotNull(Validacao.celular("123"))
    }

    @Test fun cnpjConfereOsDoisDigitosVerificadores() {
        assertTrue(Validacao.cnpjValido("11.222.333/0001-81"))
        assertTrue(Validacao.cnpjValido("11222333000181"))
        assertFalse(Validacao.cnpjValido("11.222.333/0001-82"))   // último dígito errado
        assertFalse(Validacao.cnpjValido("11.222.333/0001-8"))    // curto
        assertFalse(Validacao.cnpjValido("00000000000000"))       // todos iguais
    }

    @Test fun empresaExigeNomeSiglaCurtaECnpjValidoSeInformado() {
        assertNull(Validacao.empresa(EmpresaPedido("Viação Horizonte Sul", "HS", "11.222.333/0001-81")))
        assertNull(Validacao.empresa(EmpresaPedido("Serra Azul")))                       // sigla e CNPJ opcionais
        assertNotNull(Validacao.empresa(EmpresaPedido("X")))
        assertNotNull(Validacao.empresa(EmpresaPedido("Serra Azul", "SERRAA")))
        assertNotNull(Validacao.empresa(EmpresaPedido("Serra Azul", cnpj = "123")))
    }
}
