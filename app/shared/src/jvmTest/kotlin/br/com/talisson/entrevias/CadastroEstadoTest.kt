package br.com.talisson.entrevias

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Estado do cadastro, sem rede: porta fechada de propósito, nenhuma chamada deve sair daqui. */
class CadastroEstadoTest {
    private fun estado() = Estado(Api("http://127.0.0.1:1"), CoroutineScope(Dispatchers.Unconfined))

    @Test fun oAceiteNuncaNasceMarcado() = assertFalse(estado().cadAceita)

    @Test fun lerOsTermosNaoApagaOQueFoiDigitado() {
        val e = estado().apply { cadNome = "Maria"; cadEmail = "maria@email.com"; cadCelular = "41999990000"; cadSenha = "senha-forte-1"; cadAceita = true }
        e.abrirDocumento(Documento.PRIVACIDADE)
        assertEquals(Tela.TERMOS, e.tela); assertEquals(Documento.PRIVACIDADE, e.documento)
        e.ir(Tela.CADASTRO)
        assertEquals(listOf("Maria", "maria@email.com", "41999990000", "senha-forte-1"), listOf(e.cadNome, e.cadEmail, e.cadCelular, e.cadSenha))
        assertTrue(e.cadAceita)
    }

    @Test fun sairLimpaORascunhoInclusiveASenha() {
        val e = estado().apply { cadNome = "Maria"; cadSenha = "senha-forte-1"; cadAceita = true }
        e.sair()
        assertEquals("", e.cadNome); assertEquals("", e.cadSenha); assertFalse(e.cadAceita)
    }

    @Test fun semAceiteNaoChamaOServidor() {
        val e = estado().apply { cadNome = "Maria"; cadEmail = "maria@email.com"; cadSenha = "senha-forte-1" }
        e.cadastrar(br.com.talisson.entrevias.domain.CadastroPedido(e.cadNome, e.cadEmail, null, e.cadSenha, e.cadAceita))
        assertNotNull(e.contaErro); assertTrue("aceite" in e.contaErro!!)
        assertFalse(e.contaOcupada, "validou antes de ir à rede")
    }
}
