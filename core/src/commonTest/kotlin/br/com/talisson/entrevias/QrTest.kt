package br.com.talisson.entrevias

import br.com.talisson.entrevias.domain.Qr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Os valores de referência vêm do gerador `qrcode` do Python (nível M, modo de bytes, mesma máscara): a matriz inteira
 * tem de ser igual, módulo a módulo. Também foram lidos pelo OpenCV nas versões 1 a 20.
 */
class QrTest {
    private fun texto(n: Int) = (0 until n).map { ((it * 7 + n) % 94 + 33).toChar() }.joinToString("")

    /** FNV-1a de 32 bits sobre a matriz lida linha a linha ('1' = escuro). */
    private fun hash(q: Qr): Long {
        var h = 0x811C9DC5L
        for (y in 0 until q.tamanho) for (x in 0 until q.tamanho) { h = h xor (if (q.escuro(x, y)) '1' else '0').code.toLong(); h = (h * 0x01000193L) and 0xFFFFFFFFL }
        return h
    }

    private fun confere(t: String, mascara: Int, versao: Int, tamanho: Int, esperado: Long) {
        val q = Qr.gerar(t, mascara)
        assertEquals(versao, q.versao); assertEquals(tamanho, q.tamanho); assertEquals(esperado, hash(q), "matriz diferente da referência (${t.length} bytes, máscara $mascara)")
    }

    @Test fun matrizIgualAReferenciaDaVersao1() = confere("HELLO WORLD", 0, 1, 21, 2183362399)
    @Test fun matrizIgualAReferenciaDeUmCodigoPix() = confere("00020126580014br.gov.bcb.pix0136EVSIM1234", 2, 3, 29, 3848256232)
    @Test fun matrizIgualAReferenciaDaVersao4() = confere(texto(60), 3, 4, 33, 835275278)
    @Test fun matrizIgualAReferenciaComVariosBlocosEBitsDeVersao() = confere(texto(134), 2, 8, 49, 1657011985)
    @Test fun matrizIgualAReferenciaDaVersao13() = confere(texto(330), 5, 13, 69, 2397611263)
    @Test fun matrizIgualAReferenciaNoLimiteDaVersao20() = confere(texto(666), 1, 20, 97, 3816064388)

    @Test fun cadaVersaoCabeNoTamanhoCerto() {
        assertEquals(14, Qr.capacidade(1)); assertEquals(666, Qr.capacidade(20))
        assertEquals(1, Qr.gerar("a".repeat(14)).versao); assertEquals(2, Qr.gerar("a".repeat(15)).versao)
        assertEquals(20, Qr.gerar("a".repeat(666)).versao)
    }

    @Test fun textoGrandeDemaisNaoGeraQrTorto() {
        assertTrue(Qr.cabe("a".repeat(666))); assertFalse(Qr.cabe("a".repeat(667)))
        assertFailsWith<IllegalArgumentException> { Qr.gerar("a".repeat(667)) }
    }

    @Test fun temOsTresLocalizadoresENadaForaDaGrade() {
        val q = Qr.gerar("00020126580014br.gov.bcb.pix0136EVSIM1234")
        val n = q.tamanho
        for ((cx, cy) in listOf(0 to 0, n - 7 to 0, 0 to n - 7)) {
            assertTrue((0..6).all { q.escuro(cx + it, cy) && q.escuro(cx + it, cy + 6) && q.escuro(cx, cy + it) && q.escuro(cx + 6, cy + it) }, "moldura escura do localizador")
            assertTrue((2..4).all { dx -> (2..4).all { dy -> q.escuro(cx + dx, cy + dy) } }, "miolo escuro 3x3")
            assertTrue((1..5).all { !q.escuro(cx + it, cy + 1) && !q.escuro(cx + 1, cy + it) }, "anel claro")
        }
        assertFalse(q.escuro(-1, 0)); assertFalse(q.escuro(0, n)); assertTrue(q.escuro(8, n - 8), "o módulo escuro fixo")
    }

    @Test fun mesmoTextoDaOMesmoQr() {
        val a = Qr.gerar("00020126580014br.gov.bcb.pix0136EVSIM1234"); val b = Qr.gerar("00020126580014br.gov.bcb.pix0136EVSIM1234")
        assertEquals(hash(a), hash(b))
    }

    @Test fun acentosViramUtf8() { assertEquals(Qr.gerar("São Paulo").versao, 1); assertTrue(Qr.gerar("ç".repeat(7)).versao == 1 && Qr.gerar("ç".repeat(8)).versao == 2, "ç ocupa 2 bytes") }
}
