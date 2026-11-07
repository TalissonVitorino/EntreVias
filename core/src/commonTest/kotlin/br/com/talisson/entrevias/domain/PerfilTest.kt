package br.com.talisson.entrevias.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PerfilTest {
    @Test fun luhnAceitaCartaoDeTesteEPegaErroDeDigitacao() {
        assertTrue(ValidacaoPerfil.luhn("4242 4242 4242 4242"))
        assertTrue(ValidacaoPerfil.luhn("5555555555554444"))
        assertFalse(ValidacaoPerfil.luhn("4242 4242 4242 4243"), "um dígito trocado")
        assertFalse(ValidacaoPerfil.luhn("1234"), "curto demais")
        assertFalse(ValidacaoPerfil.luhn(""))
    }

    @Test fun bandeiraSaiDoPrefixo() {
        assertEquals("Visa", ValidacaoPerfil.bandeiraDe("4242424242424242"))
        assertEquals("Mastercard", ValidacaoPerfil.bandeiraDe("5555555555554444"))
        assertEquals("Mastercard", ValidacaoPerfil.bandeiraDe("2223003122003222"))
        assertEquals("Amex", ValidacaoPerfil.bandeiraDe("378282246310005"))
        assertEquals("Outro", ValidacaoPerfil.bandeiraDe("9999999999999995"))
    }

    @Test fun validadeTemFormatoEMesValido() {
        assertNull(ValidacaoPerfil.validade("08/29"))
        assertNotNull(ValidacaoPerfil.validade("8/29")); assertNotNull(ValidacaoPerfil.validade("13/29")); assertNotNull(ValidacaoPerfil.validade("00/29")); assertNotNull(ValidacaoPerfil.validade(null))
    }

    @Test fun cartaoVenceNoFimDoMes() {
        assertFalse(ValidacaoPerfil.vencido("11/26", 2026, 11), "vale até o último dia do mês")
        assertTrue(ValidacaoPerfil.vencido("10/26", 2026, 11))
        assertTrue(ValidacaoPerfil.vencido("12/25", 2026, 1))
        assertFalse(ValidacaoPerfil.vencido("01/27", 2026, 12))
    }

    @Test fun categoriaSaiDaIdadeNosLimites() {
        assertEquals("colo", CategoriaPassageiro.daIdade(0)); assertEquals("colo", CategoriaPassageiro.daIdade(1))
        assertEquals("crianca", CategoriaPassageiro.daIdade(2)); assertEquals("crianca", CategoriaPassageiro.daIdade(11))
        assertEquals("adulto", CategoriaPassageiro.daIdade(12)); assertEquals("adulto", CategoriaPassageiro.daIdade(59))
        assertEquals("idoso", CategoriaPassageiro.daIdade(60)); assertEquals("idoso", CategoriaPassageiro.daIdade(95))
    }

    @Test fun colonaoOcupaPoltronaEIdosoETemGratuidade() {
        val colo = Passageiro(1, "Ana", "Dependente", 1, CategoriaPassageiro.COLO)
        val idoso = Passageiro(2, "Ivan", "Você", 70, CategoriaPassageiro.IDOSO)
        val adulto = Passageiro(3, "Maria", "Você", 30, CategoriaPassageiro.ADULTO)
        assertFalse(colo.ocupaPoltrona); assertTrue(idoso.ocupaPoltrona); assertTrue(adulto.ocupaPoltrona)
        assertTrue(colo.gratuidade); assertTrue(idoso.gratuidade); assertFalse(adulto.gratuidade)
    }

    @Test fun formaDePagamentoValidaPorTipo() {
        val cartao = FormaPagamentoPedido(TIPO_CARTAO, "Visa", "4242", "08/29")
        assertNull(ValidacaoPerfil.forma(cartao))
        assertNotNull(ValidacaoPerfil.forma(cartao.copy(bandeira = "Inventada")))
        assertNotNull(ValidacaoPerfil.forma(cartao.copy(ultimos4 = "42a2")))
        assertNotNull(ValidacaoPerfil.forma(cartao.copy(ultimos4 = "424242")), "número comprido demais: só 4 dígitos entram")
        assertNotNull(ValidacaoPerfil.forma(cartao.copy(validade = "2029-08")))
        val pix = FormaPagamentoPedido(TIPO_PIX, pixTipo = "CPF", pixFinal = "321")
        assertNull(ValidacaoPerfil.forma(pix))
        assertNotNull(ValidacaoPerfil.forma(pix.copy(pixFinal = "12345")), "chave inteira não entra")
        assertNotNull(ValidacaoPerfil.forma(pix.copy(pixTipo = "Outro")))
        assertNotNull(ValidacaoPerfil.forma(FormaPagamentoPedido("boleto")))
    }

    @Test fun passageiroValidaNomeIdadeDocumentoEAcessibilidade() {
        val p = PassageiroPedido("Davi Lima", "Dependente", 8, "411", "Cadeira de rodas")
        assertNull(ValidacaoPerfil.passageiro(p))
        assertNotNull(ValidacaoPerfil.passageiro(p.copy(nome = "D")))
        assertNotNull(ValidacaoPerfil.passageiro(p.copy(idade = -1))); assertNotNull(ValidacaoPerfil.passageiro(p.copy(idade = 121)))
        assertNotNull(ValidacaoPerfil.passageiro(p.copy(documentoFinal = "41234567890")), "documento completo não entra")
        assertNotNull(ValidacaoPerfil.passageiro(p.copy(relacao = "Primo")))
        assertNotNull(ValidacaoPerfil.passageiro(p.copy(acessibilidade = "x".repeat(81))))
        assertNull(ValidacaoPerfil.passageiro(p.copy(documentoFinal = null, acessibilidade = null)))
    }
}
