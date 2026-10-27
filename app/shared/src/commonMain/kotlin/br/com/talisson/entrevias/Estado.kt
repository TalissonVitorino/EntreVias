package br.com.talisson.entrevias

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import br.com.talisson.entrevias.domain.MotorDeRotas
import br.com.talisson.entrevias.domain.Ordem
import br.com.talisson.entrevias.domain.ParametrosConexao
import br.com.talisson.entrevias.domain.Rota
import br.com.talisson.entrevias.domain.Terminal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

enum class Tela { BUSCAR, RESULTADOS, COMPARAR, ROTA, SIMULACAO, PLANEJAMENTOS }

sealed interface Carga {
    data object Carregando : Carga
    data object Pronto : Carga
    data class Erro(val mensagem: String) : Carga
}

data class Plano(val rota: Rota, val data: Data, val pax: Int)

const val COTACAO = "Cotação demonstrativa de 01/10/2026 às 10:00"
const val VENDEDOR = "Vendedor fictício: Bilheteria de demonstração"
const val SEPARADAS = "Passagens separadas: atrasos podem causar a perda da conexão. Não há proteção de conexão confirmada."
const val TAXAS = "Taxas de compra não consultadas. O valor final pode variar fora da demonstração."

class Estado(private val api: Api, private val escopo: CoroutineScope) {
    var carga by mutableStateOf<Carga>(Carga.Carregando)
    var terminais by mutableStateOf<List<Terminal>>(emptyList())
    var params by mutableStateOf(ParametrosConexao())

    var tela by mutableStateOf(Tela.BUSCAR)
    var voltarPara by mutableStateOf(Tela.RESULTADOS)

    // formulário de busca (nada pré-selecionado)
    var origem by mutableStateOf<String?>(null)
    var destino by mutableStateOf<String?>(null)
    var data by mutableStateOf(DATA_INICIAL)
    var pax by mutableStateOf(1)

    // resultado exibido (guardado no momento da busca)
    var buscando by mutableStateOf(false)
    var erroBusca by mutableStateOf<String?>(null)
    var todas by mutableStateOf<List<Rota>?>(null)
    var rOrigem by mutableStateOf("")
    var rDestino by mutableStateOf("")
    var rData by mutableStateOf(DATA_INICIAL)
    var rPax by mutableStateOf(1)
    var ordem by mutableStateOf(Ordem.MAIS_BARATA)
    val resultado: List<Rota>? get() = todas?.let { MotorDeRotas.ordenar(it, ordem).take(40) }

    var rotaAtual by mutableStateOf<Rota?>(null)
    var salvoAgora by mutableStateOf(false)
    var concluidos by mutableStateOf(0)
    val selecionadas = mutableStateListOf<String>()
    val planos = mutableStateListOf<Plano>()

    fun iniciar() {
        escopo.launch {
            carga = Carga.Carregando
            carga = try {
                terminais = api.terminais().sortedBy { it.cidade }
                params = api.parametros()
                Carga.Pronto
            } catch (t: Throwable) {
                Carga.Erro("Não foi possível falar com o servidor (${urlBase()}).")
            }
        }
    }

    fun cidade(id: String) = terminais.firstOrNull { it.id == id }?.cidade ?: id
    fun terminal(id: String) = terminais.firstOrNull { it.id == id }?.nome ?: id

    val podeBuscar get() = origem != null && destino != null && origem != destino

    fun buscar() {
        val o = origem ?: return
        val d = destino ?: return
        if (!podeBuscar || buscando) return
        escopo.launch {
            buscando = true; erroBusca = null
            try {
                val v = api.viagens(data, 3)
                val rotas = MotorDeRotas.buscar(o, d, v, params, limite = 200, partida = 0 until 1440)
                todas = rotas; rOrigem = o; rDestino = d; rData = data; rPax = pax
                selecionadas.clear()
                ir(Tela.RESULTADOS)
            } catch (t: Throwable) {
                erroBusca = "Não foi possível buscar agora. Confira a conexão com o servidor e tente de novo."
            } finally { buscando = false }
        }
    }

    /** Nome estável da opção (A, B, ... AA), independente da ordenação. */
    fun nome(r: Rota): String {
        val i = todas?.sortedBy { it.id }?.indexOfFirst { it.id == r.id } ?: -1
        if (i < 0) return "—"
        return ('A' + i % 26).toString().repeat(i / 26 + 1)
    }

    fun ir(t: Tela) { tela = t; salvoAgora = false }
    fun abrirRota(r: Rota, de: Tela = Tela.RESULTADOS) { rotaAtual = r; voltarPara = de; ir(Tela.ROTA) }
    fun abrirPlano(p: Plano) { rData = p.data; rPax = p.pax; abrirRota(p.rota, Tela.PLANEJAMENTOS) }
    fun simular(r: Rota) { rotaAtual = r; concluidos = 0; ir(Tela.SIMULACAO) }

    fun inverter() { val o = origem; origem = destino; destino = o }
    fun alternarSelecao(id: String) { if (id in selecionadas) selecionadas.remove(id) else if (selecionadas.size < 3) selecionadas.add(id) }
    fun salvar(r: Rota) {
        if (planos.none { it.rota.id == r.id && it.data == rData && it.pax == rPax }) planos.add(Plano(r, rData, rPax))
        salvoAgora = true
    }
}
