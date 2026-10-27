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
        val e = remember { Estado(Api(), escopo).also { it.iniciar() } }
        val c = cores()
        var splash by remember { androidx.compose.runtime.mutableStateOf(true) }
        Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(c.bg).safeDrawingPadding()) {
            // Cabeçalho
            Column(Modifier.fillMaxWidth().background(c.surface)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
                ) { Logo(); Pilula("Dados demonstrativos") }
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
                            T("Inicie o servidor com ./gradlew :server:run e tente de novo.", 14, cor = c.muted)
                            Botao("Tentar de novo", { e.iniciar() }, primario = true)
                        }
                    } else when (e.tela) {
                        Tela.BUSCAR -> TelaBuscar(e)
                        Tela.SIMULACAO -> TelaSimulacao(e)
                        Tela.RESULTADOS -> TelaResultados(e)
                        Tela.COMPARAR -> TelaComparar(e)
                        Tela.ROTA -> TelaRota(e)
                        Tela.PLANEJAMENTOS -> TelaPlanejamentos(e)
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
                    val naBusca = e.tela != Tela.PLANEJAMENTOS
                    Column(
                        Modifier.weight(1f).heightIn(min = 64.dp).clickable { e.ir(Tela.BUSCAR) },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        IconeBuscar(if (naBusca) c.primary else c.fg)
                        T("Buscar", 14, FontWeight.SemiBold, if (naBusca) c.primary else c.fg)
                    }
                    Column(
                        Modifier.weight(1f).heightIn(min = 64.dp).clickable { e.ir(Tela.PLANEJAMENTOS) },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        IconeSalvos(if (!naBusca) c.primary else c.fg)
                        T("Planejamentos", 14, FontWeight.SemiBold, if (!naBusca) c.primary else c.fg)
                    }
                }
            }
        }
        if (splash) Splash { splash = false }
        }
    }
}
