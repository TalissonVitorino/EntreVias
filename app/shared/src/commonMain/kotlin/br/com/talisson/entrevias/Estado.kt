package br.com.talisson.entrevias

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import br.com.talisson.entrevias.domain.AtrasoFicticio
import br.com.talisson.entrevias.domain.CadastroPedido
import br.com.talisson.entrevias.domain.Empresa
import br.com.talisson.entrevias.domain.EstadoEmail
import br.com.talisson.entrevias.domain.ValidacaoPerfil
import br.com.talisson.entrevias.domain.TIPO_PIX
import br.com.talisson.entrevias.domain.TIPO_CARTAO
import br.com.talisson.entrevias.domain.PassageiroPedido
import br.com.talisson.entrevias.domain.Passageiro
import br.com.talisson.entrevias.domain.FormaPagamentoPedido
import br.com.talisson.entrevias.domain.FormaPagamento
import br.com.talisson.entrevias.domain.EmpresaPedido
import br.com.talisson.entrevias.domain.LoginPedido
import br.com.talisson.entrevias.domain.LimiteRoteiros
import br.com.talisson.entrevias.domain.MotorDeRotas
import br.com.talisson.entrevias.domain.Ordem
import br.com.talisson.entrevias.domain.ParametrosConexao
import br.com.talisson.entrevias.domain.ParametrosPlanoB
import br.com.talisson.entrevias.domain.ParametrosRisco
import br.com.talisson.entrevias.domain.PerfilPedido
import br.com.talisson.entrevias.domain.PlanoB
import br.com.talisson.entrevias.domain.RedefinirSenhaPedido
import br.com.talisson.entrevias.domain.PlanoBConexao
import br.com.talisson.entrevias.domain.RiscoConexao
import br.com.talisson.entrevias.domain.RiscoRota
import br.com.talisson.entrevias.domain.Rota
import br.com.talisson.entrevias.domain.RoteiroSalvo
import br.com.talisson.entrevias.domain.RoteirosSalvos
import br.com.talisson.entrevias.domain.Salvamento
import br.com.talisson.entrevias.domain.Terminal
import br.com.talisson.entrevias.domain.Usuario
import br.com.talisson.entrevias.domain.Validacao
import br.com.talisson.entrevias.domain.Viagem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

enum class Tela { BUSCAR, RESULTADOS, COMPARAR, ROTA, SIMULACAO, PLANEJAMENTOS, CONTA, ENTRAR, CADASTRO, EMPRESAS, TERMOS, ESQUECI_SENHA, REDEFINIR_SENHA, VERIFICAR_EMAIL, PAGAMENTOS, PASSAGEIROS }

sealed interface Carga {
    data object Carregando : Carga
    data object Pronto : Carga
    data class Erro(val mensagem: String) : Carga
}

data class Plano(val rota: Rota, val data: Data, val pax: Int)

const val COTACAO = "Cotação demonstrativa de 01/10/2026 às 10:00"
const val VENDEDOR = "Vendedor fictício: Bilheteria de demonstração"
const val SEPARADAS = "Passagens separadas: atrasos podem causar a perda da conexão. Não há proteção de conexão confirmada."
private const val CHAVE_ROTEIROS = "roteiros"
const val TAXAS = "Taxas de compra não consultadas. O valor final pode variar fora da demonstração."

class Estado(
    private val api: Api,
    private val escopo: CoroutineScope,
    private val armazenamento: ArmazenamentoLocal = ArmazenamentoEmMemoria(),
) {
    private val sessaoSalva = SessaoSalva(armazenamento)
    var carga by mutableStateOf<Carga>(Carga.Carregando)
    var terminais by mutableStateOf<List<Terminal>>(emptyList())
    var params by mutableStateOf(ParametrosConexao())
    var paramsRisco by mutableStateOf(ParametrosRisco())

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
    var limiteAtingido by mutableStateOf(false)
    var concluidos by mutableStateOf(0)
    val selecionadas = mutableStateListOf<String>()
    /** Roteiros salvos (RF07, US22): carregados do aparelho ao abrir e regravados a cada mudança. */
    val planos = mutableStateListOf<Plano>().apply { addAll(lerRoteiros()) }

    private fun lerRoteiros(): List<Plano> =
        RoteirosSalvos.decodificar(runCatching { armazenamento.ler(CHAVE_ROTEIROS) }.getOrNull()).map {
            Plano(Rota(it.viagens, it.params), Data(it.ano, it.mes, it.dia), it.pax)
        }

    private fun guardarRoteiros() {
        val salvos = planos.map { RoteiroSalvo(it.rota.viagens, it.rota.params, it.data.ano, it.data.mes, it.data.dia, it.pax) }
        runCatching { armazenamento.gravar(CHAVE_ROTEIROS, RoteirosSalvos.codificar(salvos)) }
    }

    /** US22: tira o roteiro da lista e do aparelho. Não mexe em mais nada. */
    fun removerPlano(p: Plano) { if (planos.remove(p)) guardarRoteiros() }

    // ---------- conta (US8, US9, US11) e empresas parceiras (US7) ----------
    // O token fica em memória e também no armazenamento seguro do aparelho (SessaoSalva), conferido ao abrir o app.
    private var token: String? = null
    var usuario by mutableStateOf<Usuario?>(null)
    var contaOcupada by mutableStateOf(false)
    var contaErro by mutableStateOf<String?>(null)
    var contaAviso by mutableStateOf<String?>(null)
    val empresas = mutableStateListOf<Empresa>()
    var empresasCarga by mutableStateOf<Carga>(Carga.Pronto)

    /** Vira `true` quando a verificação do token guardado terminou (deu certo, foi recusado ou o servidor não respondeu). */
    var sessaoVerificada by mutableStateOf(false)

    /**
     * Ao abrir o app: se há token guardado, o servidor diz se ainda vale. Recusado (401) = apaga do aparelho.
     * Servidor fora do ar = mantém guardado e segue como visitante, sem apagar a sessão por causa de uma queda de rede.
     */
    private suspend fun restaurarSessao() {
        try {
            if (token != null) return
            val t = sessaoSalva.token() ?: return
            when (val r = api.perfil(t)) {
                is Resp.Ok -> { token = t; usuario = r.valor }
                is Resp.Falha -> if (r.status == 401) sessaoSalva.apagar()
            }
        } finally { sessaoVerificada = true }
    }

    fun limparMensagensDeConta() { contaErro = null; contaAviso = null }

    // Rascunho do cadastro: fica aqui (não na tela) para não se perder quando a pessoa sai para ler os Termos.
    var cadNome by mutableStateOf("")
    var cadEmail by mutableStateOf("")
    var cadCelular by mutableStateOf("")
    var cadSenha by mutableStateOf("")
    var cadAceita by mutableStateOf(false)   // nunca pré-marcado
    var documento by mutableStateOf(Documento.TERMOS)

    /** Mexer no aceite apaga o erro de tela: ele falava justamente do aceite, e não pode ficar vermelho depois de marcado. */
    fun marcarAceite(v: Boolean) { cadAceita = v; contaErro = null }

    fun limparRascunhoDoCadastro() { cadNome = ""; cadEmail = ""; cadCelular = ""; cadSenha = ""; cadAceita = false }
    fun abrirDocumento(d: Documento) { documento = d; ir(Tela.TERMOS) }

    private fun abrirSessao(u: Usuario, t: String) { token = t; sessaoSalva.guardar(t); usuario = u; limparMensagensDeConta(); limparRascunhoDoCadastro(); ir(Tela.CONTA) }

    fun cadastrar(p: CadastroPedido) {
        Validacao.cadastro(p)?.let { contaErro = it; return }
        if (contaOcupada) return
        escopo.launch {
            contaOcupada = true; limparMensagensDeConta()
            when (val r = api.cadastrar(p)) {
                is Resp.Ok -> abrirSessao(r.valor.usuario, r.valor.token)
                is Resp.Falha -> contaErro = r.mensagem
            }
            contaOcupada = false
        }
    }

    fun entrar(email: String, senha: String) {
        if (Validacao.email(email) != null || senha.isEmpty()) { contaErro = "Informe e-mail e senha."; return }
        if (contaOcupada) return
        escopo.launch {
            contaOcupada = true; limparMensagensDeConta()
            when (val r = api.entrar(LoginPedido(email, senha))) {
                is Resp.Ok -> abrirSessao(r.valor.usuario, r.valor.token)
                is Resp.Falha -> contaErro =
                    if (r.status == 429) "Muitas tentativas. Tente de novo em ${minutosTxt(r.esperaSeg)}." else r.mensagem
            }
            contaOcupada = false
        }
    }

    fun sair() {
        val t = token
        token = null; usuario = null; sessaoSalva.apagar(); empresas.clear(); formas.clear(); passageiros.clear(); limparMensagensDeConta(); limparRascunhoDoCadastro(); ir(Tela.CONTA)
        if (t != null) escopo.launch { api.sair(t) }
    }

    fun salvarPerfil(p: PerfilPedido) {
        val t = token ?: return
        Validacao.perfil(p)?.let { contaErro = it; return }
        if (contaOcupada) return
        escopo.launch {
            contaOcupada = true; limparMensagensDeConta()
            when (val r = api.salvarPerfil(t, p)) {
                is Resp.Ok -> { usuario = r.valor; contaAviso = "Dados salvos." }
                is Resp.Falha -> { contaErro = r.mensagem; if (r.status == 401) sessaoExpirou() }
            }
            contaOcupada = false
        }
    }

    // ---------- e-mail: recuperar senha (RQ9) e confirmar e-mail (RQ10) ----------

    /** Como o servidor envia e-mail. `null` até a primeira resposta. Simulado = nenhum e-mail real sai (docs/SIMULADOS.md). */
    var emailEstado by mutableStateOf<EstadoEmail?>(null)
    var recEmail by mutableStateOf("")

    fun carregarEstadoEmail() {
        escopo.launch { (api.estadoEmail() as? Resp.Ok)?.let { emailEstado = it.valor } }
    }

    fun abrirRecuperacao() { limparMensagensDeConta(); recEmail = cadEmail.ifBlank { recEmail }; ir(Tela.ESQUECI_SENHA) }

    fun pedirCodigoDeSenha(email: String) {
        Validacao.email(email)?.let { contaErro = it; return }
        if (contaOcupada) return
        escopo.launch {
            contaOcupada = true; limparMensagensDeConta()
            when (val r = api.esqueciSenha(email)) {
                is Resp.Ok -> { recEmail = email; ir(Tela.REDEFINIR_SENHA); contaAviso = r.valor.mensagem }
                is Resp.Falha -> contaErro = if (r.status == 429) "Muitas tentativas. Tente de novo em ${minutosTxt(r.esperaSeg)}." else r.mensagem
            }
            contaOcupada = false
        }
    }

    fun redefinirSenha(codigo: String, novaSenha: String) {
        val p = RedefinirSenhaPedido(recEmail, codigo.trim(), novaSenha)
        (Validacao.codigo(p.codigo) ?: Validacao.senha(novaSenha))?.let { contaErro = it; return }
        if (contaOcupada) return
        escopo.launch {
            contaOcupada = true; limparMensagensDeConta()
            when (val r = api.redefinirSenha(p)) {
                is Resp.Ok -> { recEmail = ""; ir(Tela.ENTRAR); contaAviso = r.valor.mensagem }
                is Resp.Falha -> contaErro = if (r.status == 429) "Muitas tentativas. Tente de novo em ${minutosTxt(r.esperaSeg)}." else r.mensagem
            }
            contaOcupada = false
        }
    }

    fun abrirVerificacao() { limparMensagensDeConta(); ir(Tela.VERIFICAR_EMAIL) }

    fun reenviarVerificacao() {
        val t = token ?: return
        if (contaOcupada) return
        escopo.launch {
            contaOcupada = true; limparMensagensDeConta()
            when (val r = api.enviarVerificacao(t)) {
                is Resp.Ok -> contaAviso = r.valor.mensagem
                is Resp.Falha -> { contaErro = if (r.status == 429) "Muitas tentativas. Tente de novo mais tarde." else r.mensagem; if (r.status == 401) sessaoExpirou() }
            }
            contaOcupada = false
        }
    }

    fun confirmarEmail(codigo: String) {
        val t = token ?: return
        Validacao.codigo(codigo.trim())?.let { contaErro = it; return }
        if (contaOcupada) return
        escopo.launch {
            contaOcupada = true; limparMensagensDeConta()
            when (val r = api.confirmarEmail(t, codigo.trim())) {
                is Resp.Ok -> { usuario = r.valor; ir(Tela.CONTA); contaAviso = "E-mail confirmado." }
                is Resp.Falha -> { contaErro = r.mensagem; if (r.status == 401) sessaoExpirou() }
            }
            contaOcupada = false
        }
    }

    // ---------- A5 formas de pagamento e A6 passageiros ----------

    val formas = mutableStateListOf<FormaPagamento>()
    val passageiros = mutableStateListOf<Passageiro>()
    var carteiraCarga by mutableStateOf<Carga>(Carga.Pronto)
    var carteiraErro by mutableStateOf<String?>(null)
    var carteiraAviso by mutableStateOf<String?>(null)
    var carteiraOcupada by mutableStateOf(false)

    fun limparMensagensDaCarteira() { carteiraErro = null; carteiraAviso = null }

    /** Roda uma chamada autenticada da carteira: ocupado, erro genérico, sessão vencida (401) volta ao login. */
    private fun naCarteira(aviso: String? = null, bloco: suspend (String) -> Resp<*>) {
        val t = token ?: return
        if (carteiraOcupada) return
        escopo.launch {
            carteiraOcupada = true; limparMensagensDaCarteira()
            when (val r = bloco(t)) {
                is Resp.Ok<*> -> carteiraAviso = aviso
                is Resp.Falha -> { carteiraErro = r.mensagem; if (r.status == 401) sessaoExpirou() }
            }
            carteiraOcupada = false
        }
    }

    fun carregarCarteira() {
        val t = token ?: return
        escopo.launch {
            carteiraCarga = Carga.Carregando
            val f = api.formas(t); val p = api.passageiros(t)
            if (f is Resp.Ok) { formas.clear(); formas.addAll(f.valor) }
            if (p is Resp.Ok) { passageiros.clear(); passageiros.addAll(p.valor) }
            carteiraCarga = (f as? Resp.Falha ?: p as? Resp.Falha)?.let { Carga.Erro(it.mensagem) } ?: Carga.Pronto
            if ((f as? Resp.Falha)?.status == 401 || (p as? Resp.Falha)?.status == 401) sessaoExpirou()
        }
    }

    /**
     * O número do cartão é conferido AQUI (soma de Luhn, validade) e só bandeira, 4 últimos dígitos e validade seguem para o
     * servidor. O número completo e o CVV nunca saem do aparelho.
     */
    fun adicionarCartao(numero: String, validade: String, padrao: Boolean) {
        if (!ValidacaoPerfil.luhn(numero)) { carteiraErro = "Número de cartão inválido. Confira os dígitos."; return }
        ValidacaoPerfil.validade(validade)?.let { carteiraErro = it; return }
        if (ValidacaoPerfil.vencido(validade.trim(), DATA_INICIAL.ano, DATA_INICIAL.mes)) { carteiraErro = "Este cartão está vencido."; return }
        val d = numero.filter { it in '0'..'9' }
        val pedido = FormaPagamentoPedido(TIPO_CARTAO, ValidacaoPerfil.bandeiraDe(d), d.takeLast(4), validade.trim(), padrao = padrao)
        naCarteira("Cartão adicionado.") { t -> api.adicionarForma(t, pedido).also { if (it is Resp.Ok) formas.apply { clear(); addAll((api.formas(t) as? Resp.Ok)?.valor ?: emptyList()) } } }
    }

    fun adicionarPix(tipo: String, finalDaChave: String, padrao: Boolean) {
        val pedido = FormaPagamentoPedido(TIPO_PIX, pixTipo = tipo, pixFinal = finalDaChave.trim(), padrao = padrao)
        ValidacaoPerfil.forma(pedido)?.let { carteiraErro = it; return }
        naCarteira("Pix adicionado.") { t -> api.adicionarForma(t, pedido).also { if (it is Resp.Ok) formas.apply { clear(); addAll((api.formas(t) as? Resp.Ok)?.valor ?: emptyList()) } } }
    }

    fun tornarPadrao(f: FormaPagamento) = naCarteira("Forma de pagamento padrão atualizada.") { t -> api.tornarPadrao(t, f.id).also { if (it is Resp.Ok) formas.apply { clear(); addAll(it.valor) } } }
    fun removerForma(f: FormaPagamento) = naCarteira("Forma de pagamento removida.") { t -> api.removerForma(t, f.id).also { if (it is Resp.Ok) formas.apply { clear(); addAll(it.valor) } } }

    fun salvarPassageiro(id: Int?, p: PassageiroPedido, aoTerminar: (String?) -> Unit) {
        ValidacaoPerfil.passageiro(p)?.let { aoTerminar(it); return }
        val t = token ?: return
        escopo.launch {
            val r = if (id == null) api.adicionarPassageiro(t, p) else api.editarPassageiro(t, id, p)
            when (r) {
                is Resp.Ok -> { (api.passageiros(t) as? Resp.Ok)?.let { passageiros.clear(); passageiros.addAll(it.valor) }; aoTerminar(null) }
                is Resp.Falha -> { aoTerminar(r.mensagem); if (r.status == 401) sessaoExpirou() }
            }
        }
    }

    fun removerPassageiro(p: Passageiro) = naCarteira("Passageiro removido.") { t -> api.removerPassageiro(t, p.id).also { if (it is Resp.Ok) passageiros.apply { clear(); addAll(it.valor) } } }

    private fun sessaoExpirou() { token = null; usuario = null; sessaoSalva.apagar(); empresas.clear(); formas.clear(); passageiros.clear(); ir(Tela.ENTRAR); contaErro = "Sua sessão acabou. Entre de novo." }

    fun carregarEmpresas() {
        val t = token ?: return
        escopo.launch {
            empresasCarga = Carga.Carregando
            when (val r = api.empresas(t)) {
                is Resp.Ok -> { empresas.clear(); empresas.addAll(r.valor); empresasCarga = Carga.Pronto }
                is Resp.Falha -> { empresasCarga = Carga.Erro(r.mensagem); if (r.status == 401) sessaoExpirou() }
            }
        }
    }

    /** Cria (id == null) ou edita uma empresa parceira; devolve o texto de erro pelo [aoTerminar] (`null` = deu certo). */
    fun salvarEmpresa(id: Int?, p: EmpresaPedido, aoTerminar: (String?) -> Unit) {
        val t = token ?: return
        Validacao.empresa(p)?.let { aoTerminar(it); return }
        escopo.launch {
            val r = if (id == null) api.criarEmpresa(t, p) else api.atualizarEmpresa(t, id, p)
            when (r) {
                is Resp.Ok -> {
                    val i = empresas.indexOfFirst { it.id == r.valor.id }
                    if (i >= 0) empresas[i] = r.valor else { empresas.add(r.valor); empresas.sortBy { it.nome } }
                    aoTerminar(null)
                }
                is Resp.Falha -> { aoTerminar(r.mensagem); if (r.status == 401) sessaoExpirou() }
            }
        }
    }

    private fun minutosTxt(seg: Int?): String {
        val m = ((seg ?: 900) + 59) / 60
        return if (m <= 1) "1 minuto" else "$m minutos"
    }

    // plano B (RF20): as regras do servidor e as viagens do dia da rota aberta
    var paramsPlanoB by mutableStateOf(ParametrosPlanoB())
    var base by mutableStateOf<List<Viagem>?>(null)
    var baseCarga by mutableStateOf<Carga>(Carga.Pronto)
    private var baseData: Data? = null   // dia a que `base` pertence (ou que está sendo carregado)

    fun iniciar() {
        escopo.launch {
            carga = Carga.Carregando
            carga = try {
                terminais = api.terminais().sortedBy { it.cidade }
                params = api.parametros()
                // servidor sem o endpoint de risco (versão antiga): vale a fórmula inicial do plano
                paramsRisco = try { api.parametrosRisco() } catch (e: CancellationException) { throw e } catch (_: Exception) { ParametrosRisco() }
                // servidor sem o endpoint do plano B (versão antiga): valem as regras iniciais do plano
                paramsPlanoB = try { api.parametrosPlanoB() } catch (e: CancellationException) { throw e } catch (_: Exception) { ParametrosPlanoB() }
                Carga.Pronto
            } catch (t: Throwable) {
                Carga.Erro("Não foi possível falar com o servidor (${urlBase()}).")
            }
            if (carga is Carga.Pronto) restaurarSessao()
        }
    }

    fun cidade(id: String) = terminais.firstOrNull { it.id == id }?.cidade ?: id
    fun terminal(id: String) = terminais.firstOrNull { it.id == id }?.nome ?: id

    /** RF19: nota de risco da rota e de cada conexão (atraso típico fictício, rotulado "simulado"). */
    fun risco(r: Rota): RiscoRota = RiscoConexao.avaliar(r, paramsRisco, AtrasoFicticio) { cidade(it) }

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
                base = v; baseData = data; baseCarga = Carga.Pronto
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

    fun ir(t: Tela) { tela = t; salvoAgora = false; limiteAtingido = false }
    fun abrirRota(r: Rota, de: Tela = Tela.RESULTADOS) { rotaAtual = r; voltarPara = de; ir(Tela.ROTA) }
    fun abrirPlano(p: Plano) { rData = p.data; rPax = p.pax; garantirBase(p.data); abrirRota(p.rota, Tela.PLANEJAMENTOS) }

    /** O plano B precisa das viagens do dia da rota; ao abrir um planejamento salvo de outro dia, busca de novo. */
    fun garantirBase(d: Data) {
        if (baseData == d && baseCarga !is Carga.Erro) return
        base = null; baseData = d; baseCarga = Carga.Carregando
        escopo.launch {
            try {
                val v = api.viagens(d, 3)
                if (baseData == d) { base = v; baseCarga = Carga.Pronto }   // uma busca nova no meio do caminho vence
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (baseData == d) baseCarga = Carga.Erro("Não foi possível carregar as viagens do dia.")
            }
        }
    }

    /** RF20: o que fazer se a conexão [conexao] da rota for perdida; `null` enquanto as viagens do dia não chegaram. */
    fun planoB(r: Rota, conexao: Int): PlanoB? =
        base?.let { PlanoBConexao.avaliar(r, conexao, it, paramsPlanoB, params) { id -> cidade(id) } }
    fun simular(r: Rota) { rotaAtual = r; concluidos = 0; ir(Tela.SIMULACAO) }

    fun inverter() { val o = origem; origem = destino; destino = o }
    fun alternarSelecao(id: String) { if (id in selecionadas) selecionadas.remove(id) else if (selecionadas.size < 3) selecionadas.add(id) }
    /** RF34: guarda no aparelho até o limite; ao chegar nele não apaga nada, só avisa (`limiteAtingido`). */
    fun salvar(r: Rota) {
        when (val s = LimiteRoteiros.salvar(planos.toList(), Plano(r, rData, rPax))) {
            is Salvamento.Salvo -> { planos.clear(); planos.addAll(s.lista); guardarRoteiros(); salvoAgora = true }
            Salvamento.Repetido -> salvoAgora = true
            Salvamento.Cheio -> limiteAtingido = true
        }
    }
}
