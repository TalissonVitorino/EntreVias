package br.com.talisson.entrevias

import br.com.talisson.entrevias.domain.Rota
import br.com.talisson.entrevias.domain.Viagem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** US22 e RF07: roteiros salvos sobrevivem a fechar e abrir o app, e remover apaga de verdade. Sem rede. */
class RoteirosSalvosEstadoTest {
    private fun viagem(id: Int, o: String, d: String, s: Int, c: Int) = Viagem(id, "Empresa", "Convencional", o, d, s, c, 5990)
    private fun rota(id: Int) = Rota(listOf(viagem(id, "CWB", "JVL", 360, 600), viagem(id + 1, "JVL", "FLN", 700, 900)))

    /** "Abrir o app": um Estado novo sobre o MESMO armazenamento. */
    private fun abrir(arm: ArmazenamentoLocal) = Estado(Api("http://127.0.0.1:1"), CoroutineScope(Dispatchers.Unconfined), arm)

    private fun Estado.salvarRota(r: Rota, d: Data = Data(2026, 11, 20), pax: Int = 2) { rData = d; rPax = pax; salvar(r) }

    @Test fun salvarGuardaNoAparelhoEReabrirRecupera() {
        val arm = ArmazenamentoEmMemoria()
        abrir(arm).salvarRota(rota(1), Data(2026, 12, 5), pax = 3)

        val reaberto = abrir(arm)
        assertEquals(1, reaberto.planos.size)
        val p = reaberto.planos.single()
        assertEquals(rota(1), p.rota); assertEquals(Data(2026, 12, 5), p.data); assertEquals(3, p.pax)
    }

    @Test fun removerApagaDaListaEDoAparelho() {
        val arm = ArmazenamentoEmMemoria()
        val e = abrir(arm)
        e.salvarRota(rota(1)); e.salvarRota(rota(10))
        e.removerPlano(e.planos.first())

        assertEquals(listOf(rota(10)), e.planos.map { it.rota })
        assertEquals(listOf(rota(10)), abrir(arm).planos.map { it.rota }, "o aparelho também esqueceu")
        e.removerPlano(e.planos.single())
        assertTrue(abrir(arm).planos.isEmpty())
    }

    @Test fun removerAbreEspacoNoLimiteDeDez() {
        val arm = ArmazenamentoEmMemoria()
        val e = abrir(arm)
        (1..10).forEach { e.salvarRota(rota(it * 10)) }
        e.salvarRota(rota(500)); assertTrue(e.limiteAtingido); assertEquals(10, e.planos.size)
        e.removerPlano(e.planos.first())
        e.salvarRota(rota(500))
        assertEquals(10, abrir(arm).planos.size); assertTrue(abrir(arm).planos.any { it.rota == rota(500) })
    }

    @Test fun repetidoNaoDuplicaNemNoAparelho() {
        val arm = ArmazenamentoEmMemoria()
        val e = abrir(arm)
        e.salvarRota(rota(1)); e.salvarRota(rota(1))
        assertEquals(1, abrir(arm).planos.size)
    }

    @Test fun sairDaContaNaoLevaOsRoteirosJunto() {
        val arm = ArmazenamentoEmMemoria()
        val e = abrir(arm)
        e.salvarRota(rota(1)); e.sair()
        assertEquals(1, e.planos.size); assertEquals(1, abrir(arm).planos.size)
    }

    @Test fun armazenamentoEstragadoOuQuebradoNaoDerrubaOApp() {
        val lixo = ArmazenamentoEmMemoria().also { it.gravar("roteiros", "{{{ nao eh json") }
        assertTrue(abrir(lixo).planos.isEmpty())

        val quebrado = object : ArmazenamentoLocal {
            override fun ler(chave: String): String? = error("keystore indisponível")
            override fun gravar(chave: String, valor: String?) = error("disco cheio")
        }
        val e = abrir(quebrado)
        assertTrue(e.planos.isEmpty())
        e.salvarRota(rota(1))
        assertEquals(1, e.planos.size, "mesmo sem conseguir gravar, vale na tela até fechar o app")
        assertFalse(e.limiteAtingido)
    }
}
