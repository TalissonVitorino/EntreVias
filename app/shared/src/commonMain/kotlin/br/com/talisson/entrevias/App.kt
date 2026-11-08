package br.com.talisson.entrevias

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
@Preview
fun App() {
    EntreViasTheme {
        val escopo = androidx.compose.runtime.rememberCoroutineScope()
        val e = remember { Estado(Api(), escopo, criarArmazenamento()).also { it.iniciar() } }
        val c = cores()
        var splash by remember { androidx.compose.runtime.mutableStateOf(true) }
        Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(c.bg).safeDrawingPadding()) {
            // Cabeçalho
            Column(Modifier.fillMaxWidth().background(c.surface)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Logo()
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (e.usuario != null) Sino(e.avisosNaoLidos) { e.abrirAvisos() }
                        Pilula("Dados demonstrativos")
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
            }
            Column(Modifier.fillMaxWidth().background(c.noteBg)) {
                T("Sem consulta em tempo real, fornecedores conectados ou compras reais.", 13, cor = c.muted, mod = Modifier.padding(16.dp, 10.dp))
            }
            // Conteúdo
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = 720.dp).fillMaxWidth()
                        .verticalScroll(androidx.compose.runtime.key(e.tela) { rememberScrollState() })
                        .padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 100.dp),
                ) {
                    val carga = e.carga
                    if (carga is Carga.Carregando) {
                        T("Conectando ao servidor…", 17, cor = c.muted)
                    } else if (carga is Carga.Erro) {
                        Cartao {
                            T("Servidor indisponível", 22, FontWeight.Bold)
                            T(carga.mensagem, 15, cor = c.muted)
                            T("Inicie o servidor com ./gradlew :server:subirServidor (Docker) e tente de novo.", 14, cor = c.muted)
                            Botao("Tentar de novo", { e.iniciar() }, primario = true)
                        }
                    } else when (e.tela) {
                        Tela.BUSCAR -> TelaBuscar(e)
                        Tela.SIMULACAO -> TelaSimulacao(e)
                        Tela.RESULTADOS -> TelaResultados(e)
                        Tela.COMPARAR -> TelaComparar(e)
                        Tela.ROTA -> TelaRota(e)
                        Tela.PLANEJAMENTOS -> TelaPlanejamentos(e)
                        Tela.CONTA -> TelaConta(e)
                        Tela.ENTRAR -> TelaEntrar(e)
                        Tela.CADASTRO -> TelaCadastro(e)
                        Tela.TERMOS -> TelaTermos(e)
                        Tela.EMPRESAS -> TelaEmpresas(e)
                        Tela.GESTAO -> TelaGestao(e)
                        Tela.ESQUECI_SENHA -> TelaEsqueciSenha(e)
                        Tela.REDEFINIR_SENHA -> TelaRedefinirSenha(e)
                        Tela.VERIFICAR_EMAIL -> TelaVerificarEmail(e)
                        Tela.PAGAMENTOS -> TelaPagamentos(e)
                        Tela.PASSAGEIROS -> TelaPassageiros(e)
                        Tela.CARRINHO -> TelaCarrinho(e)
                        Tela.POLTRONAS -> TelaPoltronas(e)
                        Tela.PAGAMENTO -> TelaPagamento(e)
                        Tela.PIX -> TelaPix(e)
                        Tela.RESULTADO -> TelaResultado(e)
                        Tela.BILHETES -> TelaBilhetes(e)
                        Tela.HISTORICO -> TelaHistorico(e)
                        Tela.COMPRA_DETALHE -> TelaCompraDetalhe(e)
                        Tela.CANCELAR -> TelaCancelar(e)
                        Tela.COMPROVANTE -> TelaComprovante(e)
                        Tela.REMARCAR -> TelaRemarcar(e)
                        Tela.AVISOS -> TelaAvisos(e)
                        Tela.EM_ANDAMENTO -> TelaEmAndamento(e)
                        Tela.AJUDA -> TelaAjuda(e)
                        Tela.AVALIAR -> TelaAvaliar(e)
                    }
                }
                if (e.tela == Tela.RESULTADOS && e.selecionadas.isNotEmpty()) {
                    Box(Modifier.align(Alignment.BottomCenter).widthIn(max = 720.dp).fillMaxWidth().padding(16.dp, 0.dp, 16.dp, 10.dp)) {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface)
                                .border(1.dp, c.line, RoundedCornerShape(18.dp)).padding(start = 18.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                T("${e.selecionadas.size} ${if (e.selecionadas.size == 1) "opção selecionada" else "opções selecionadas"}", 16, FontWeight.Bold)
                                T(if (e.selecionadas.size < 2) "Escolha ao menos duas" else "Pronto para comparar", 13, cor = c.muted)
                            }
                            Botao("Comparar", { e.ir(Tela.COMPARAR) }, primario = true, pequeno = true)
                        }
                    }
                }
            }
            // Navegação inferior
            Column(Modifier.fillMaxWidth().background(c.surface)) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
                Row(Modifier.fillMaxWidth()) {
                    val naConta = e.tela in setOf(Tela.CONTA, Tela.ENTRAR, Tela.CADASTRO, Tela.EMPRESAS, Tela.GESTAO, Tela.TERMOS, Tela.ESQUECI_SENHA, Tela.REDEFINIR_SENHA, Tela.VERIFICAR_EMAIL, Tela.PAGAMENTOS, Tela.PASSAGEIROS)
                    val nosSalvos = e.tela == Tela.PLANEJAMENTOS
                    val nasViagens = e.tela in setOf(Tela.HISTORICO, Tela.COMPRA_DETALHE, Tela.CANCELAR, Tela.COMPROVANTE, Tela.REMARCAR, Tela.BILHETES, Tela.AVISOS, Tela.EM_ANDAMENTO, Tela.AJUDA, Tela.AVALIAR)
                    val naBusca = !naConta && !nosSalvos && !nasViagens
                    @Composable
                    fun Aba(rotulo: String, ativa: Boolean, onClick: () -> Unit, icone: @Composable (androidx.compose.ui.graphics.Color) -> Unit) {
                        Column(
                            Modifier.weight(1f).heightIn(min = 64.dp).clickable(onClick = onClick),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                        ) {
                            icone(if (ativa) c.primary else c.fg)
                            // 12 e numa linha só: no iPhone a fonte do sistema é mais larga e "Planejamentos" quebrava em duas linhas
                            T(rotulo, 12, FontWeight.SemiBold, if (ativa) c.primary else c.fg, maxLines = 1, softWrap = false)
                        }
                    }
                    Aba("Buscar", naBusca, { e.ir(Tela.BUSCAR) }) { IconeBuscar(it) }
                    Aba("Viagens", nasViagens, { e.abrirHistorico() }) { IconeViagens(it) }
                    Aba("Planejamentos", nosSalvos, { e.ir(Tela.PLANEJAMENTOS) }) { IconeSalvos(it) }
                    Aba("Conta", naConta, { e.limparMensagensDeConta(); e.ir(Tela.CONTA) }) { IconeConta(it) }
                }
            }
        }
        if (splash) Splash { splash = false }
        }
    }
}
