package br.com.talisson.entrevias.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompraTest {
    private val A = CategoriaPassageiro.ADULTO; private val I = CategoriaPassageiro.IDOSO
    private val C = CategoriaPassageiro.CRIANCA; private val B = CategoriaPassageiro.COLO

    @Test fun doisAdultosPagamTudoEmCadaTrecho() {
        val t = Cotador.cotar(listOf(4490, 7490), listOf(A, A), null)
        assertEquals(2 * (4490 + 7490), t.subtotalCentavos); assertEquals(0, t.descontoCategoriaCentavos); assertEquals(t.subtotalCentavos, t.totalCentavos)
    }

    @Test fun idosoTemGratuidadeEmTodosOsTrechos() {
        val t = Cotador.cotar(listOf(10000, 20000), listOf(A, I), null)
        assertEquals(60000, t.subtotalCentavos); assertEquals(30000, t.descontoCategoriaCentavos); assertEquals(30000, t.totalCentavos)
    }

    @Test fun criancaPagaAMetade() {
        val t = Cotador.cotar(listOf(10000), listOf(A, C), null)
        assertEquals(20000, t.subtotalCentavos); assertEquals(5000, t.descontoCategoriaCentavos); assertEquals(15000, t.totalCentavos)
    }

    @Test fun bebeDeColoNaoPagaENaoEntraNoSubtotal() {
        val com = Cotador.cotar(listOf(10000), listOf(A, B), null); val sem = Cotador.cotar(listOf(10000), listOf(A), null)
        assertEquals(sem, com)
    }

    @Test fun cupomIncideSobreABaseDepoisDoDescontoDeCategoria() {
        val t = Cotador.cotar(listOf(10000), listOf(A, I), 10)
        assertEquals(10000, t.totalCentavos + t.cupomCentavos, "base = 10000 (o idoso é grátis)")
        assertEquals(1000, t.cupomCentavos); assertEquals(9000, t.totalCentavos)
    }

    @Test fun totalNuncaFicaNegativoNemMaiorQueOSubtotal() {
        val soIdoso = Cotador.cotar(listOf(5000), listOf(I), 50)
        assertEquals(0, soIdoso.totalCentavos); assertTrue(soIdoso.totalCentavos >= 0)
        val t = Cotador.cotar(listOf(3333, 1111), listOf(A, C, I), 33)
        assertTrue(t.totalCentavos <= t.subtotalCentavos && t.totalCentavos >= 0)
        assertEquals(t.subtotalCentavos - t.descontoCategoriaCentavos - t.cupomCentavos, t.totalCentavos, "as linhas fecham com o total")
    }

    @Test fun reembolsoDescontaATaxaArredondadaParaCima() {
        val (taxa, reembolso) = Cotador.reembolso(14823)
        assertEquals(742, taxa, "5% de 148,23 = 7,4115 → 7,42"); assertEquals(14081, reembolso); assertEquals(14823, taxa + reembolso)
        assertEquals(0 to 0, Cotador.reembolso(0))
        assertEquals(100 to 0, Cotador.reembolso(100, 100))
    }

    @Test fun menorDeIdadePrecisaDeResponsavel() {
        assertFalse(RegrasDePreco.temResponsavel(listOf(C, B))); assertFalse(RegrasDePreco.temResponsavel(listOf(C)))
        assertTrue(RegrasDePreco.temResponsavel(listOf(C, A))); assertTrue(RegrasDePreco.temResponsavel(listOf(C, I)))
    }

    @Test fun colonaoOcupaPoltrona() = assertEquals(2, RegrasDePreco.poltronasNecessarias(listOf(A, C, B)))

    @Test fun poltronasPcDSaoAsDoDesign() = assertEquals(setOf(1, 5), RegrasDePreco.POLTRONAS_PCD)
}
