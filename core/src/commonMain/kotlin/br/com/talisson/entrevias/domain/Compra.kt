package br.com.talisson.entrevias.domain

import kotlinx.serialization.Serializable

/**
 * Regras de preço da compra. São HIPÓTESES a validar com o grupo (como a RN01): a criança paga a metade, o idoso tem a
 * gratuidade (mediante comprovação no embarque) e o bebê de colo não paga nem ocupa poltrona.
 */
object RegrasDePreco {
    const val CRIANCA_PCT = 50
    const val TAXA_CANCELAMENTO_PCT = 5
    /** Remarcação: só até 2 horas antes da saída do trecho, uma vez por compra, sem taxa e sem diferença de preço (ver docs/SIMULADOS.md). */
    const val REMARCACAO_ANTECEDENCIA_SEG = 2 * 3600L
    const val MAX_PASSAGEIROS = 6
    const val RESERVA_SEG = 15 * 60L
    const val PIX_VALIDADE_SEG = 10 * 60L
    const val POLTRONAS = 40
    val POLTRONAS_PCD = setOf(1, 5)

    /** Percentual do preço que a categoria PAGA. */
    fun pctPago(categoria: String) = when (categoria) {
        CategoriaPassageiro.COLO, CategoriaPassageiro.IDOSO -> 0
        CategoriaPassageiro.CRIANCA -> CRIANCA_PCT
        else -> 100
    }

    /** Menor de 12 anos não viaja sozinho: precisa de ao menos um adulto ou idoso no pedido. */
    fun temResponsavel(categorias: List<String>) = categorias.any { it == CategoriaPassageiro.ADULTO || it == CategoriaPassageiro.IDOSO }

    fun poltronasNecessarias(categorias: List<String>) = categorias.count { it != CategoriaPassageiro.COLO }
}

@Serializable
data class Cupom(val codigo: String, val percentual: Int)

/** Valores sempre em centavos. `base` = subtotal − desconto de categoria; o cupom incide sobre a base. */
@Serializable
data class Totais(
    val subtotalCentavos: Int,
    val descontoCategoriaCentavos: Int,
    val cupomCentavos: Int,
    val totalCentavos: Int,
)

object Cotador {
    /**
     * @param precosDosTrechos preço cheio de UMA pessoa em cada trecho
     * @param categorias categoria de cada passageiro do pedido
     * @param cupomPct percentual do cupom, ou `null`
     * Arredonda para baixo cada parcela, para o total nunca passar do que a soma das linhas mostra.
     */
    fun cotar(precosDosTrechos: List<Int>, categorias: List<String>, cupomPct: Int?): Totais {
        val comPoltrona = categorias.filter { it != CategoriaPassageiro.COLO }
        var subtotal = 0; var desconto = 0
        for (preco in precosDosTrechos) for (cat in comPoltrona) {
            subtotal += preco
            desconto += preco - preco * RegrasDePreco.pctPago(cat) / 100
        }
        val base = subtotal - desconto
        val cupom = if (cupomPct != null) base * cupomPct / 100 else 0
        return Totais(subtotal, desconto, cupom, base - cupom)
    }

    /** Reembolso de um cancelamento: o total pago menos a taxa. A taxa arredonda para cima (nunca a favor de quem paga a menos). */
    fun reembolso(totalPagoCentavos: Int, taxaPct: Int = RegrasDePreco.TAXA_CANCELAMENTO_PCT): Pair<Int, Int> {
        val taxa = (totalPagoCentavos * taxaPct + 99) / 100
        return taxa to (totalPagoCentavos - taxa).coerceAtLeast(0)
    }
}

// ---------------------------------------------------------------- DTOs do pedido

object StatusPedido {
    const val CARRINHO = "carrinho"
    const val PAGO = "pago"
    const val CANCELADO = "cancelado"
    const val EXPIRADO = "expirado"
}

@Serializable
data class PedidoTrecho(
    val ordem: Int, val viagemId: Int, val empresa: String, val categoria: String,
    val origem: String, val destino: String, val saidaEm: Long, val chegadaEm: Long,
    val precoUnitCentavos: Int, val poltronas: List<Int> = emptyList(), val plataforma: Int? = null,
    /** D4: atraso (ou mudança de horário) que o gestor registrou, em minutos. Negativo = saiu mais cedo. */
    val atrasoMin: Int = 0,
    val cancelada: Boolean = false,
    /** B1: este trecho é da volta (a ida e a volta são uma compra só). */
    val volta: Boolean = false,
) {
    val saidaReal get() = saidaEm + atrasoMin * 60L
    val chegadaReal get() = chegadaEm + atrasoMin * 60L
}

@Serializable
data class PedidoPassageiro(val nome: String, val categoria: String, val idade: Int, val documentoFinal: String? = null, val acessibilidade: String? = null)

@Serializable
data class Bilhete(val codigo: String, val trecho: Int, val passageiro: String, val poltrona: Int?, val plataforma: Int)

@Serializable
data class Pedido(
    val id: Int,
    val codigo: String,
    val status: String,
    val trechos: List<PedidoTrecho>,
    val passageiros: List<PedidoPassageiro>,
    val totais: Totais,
    val cupom: String? = null,
    val criadoEm: Long = 0,
    val pagoEm: Long? = null,
    val metodo: String? = null,
    val reservaExpiraEm: Long? = null,
    val canceladoEm: Long? = null,
    val reembolsoCentavos: Int? = null,
    val bilhetes: List<Bilhete> = emptyList(),
    /** O relógio do SERVIDOR no momento da resposta. Prazos (reserva, Pix) são dele; o app conta a partir daqui, nunca do relógio do aparelho. */
    val agora: Long = 0,
    /** Quando a compra foi remarcada (só se pode uma vez); `null` = nunca. */
    val remarcadaEm: Long? = null,
) {
    val saidaEm get() = trechos.minOf { it.saidaReal }
    val ehIdaEVolta get() = trechos.any { it.volta }
    /** Onde a ida termina (e a volta começa). */
    val destinoDaIda get() = trechos.last { !it.volta }.destino
    val poltronasCompletas get() = trechos.all { it.poltronas.size == RegrasDePreco.poltronasNecessarias(passageiros.map { p -> p.categoria }) }
}

@Serializable
data class NovoPedido(val viagemIds: List<Int>, val passageiroIds: List<Int>, val cupom: String? = null, val viagemIdsVolta: List<Int> = emptyList())

@Serializable
data class CupomPedido(val codigo: String)

/** Mapa de poltronas de um trecho: o que está ocupado, o que é PcD e o que é meu (reservado neste pedido). */
@Serializable
data class MapaDePoltronas(val viagemId: Int, val total: Int, val ocupadas: List<Int>, val pcd: List<Int>, val minhas: List<Int>)

@Serializable
data class EscolhaDePoltronas(val viagemId: Int, val poltronas: List<Int>)

@Serializable
data class EscolhasPedido(val escolhas: List<EscolhaDePoltronas>)

// ---------------------------------------------------------------- pagamento (SIMULADO, ver docs/SIMULADOS.md)

object StatusPagamento {
    const val PENDENTE = "pendente"
    const val APROVADO = "aprovado"
    const val RECUSADO = "recusado"
    const val EXPIRADO = "expirado"
}

@Serializable
data class Pagamento(
    val id: Int, val pedidoId: Int, val metodo: String, val status: String,
    val codigoPix: String? = null, val expiraEm: Long? = null, val motivo: String? = null, val parcelas: Int = 1,
    /** O relógio do servidor no momento da resposta (ver [Pedido.agora]). */
    val agora: Long = 0,
)

@Serializable
data class PagamentoCartaoPedido(val ultimos4: String, val bandeira: String, val parcelas: Int = 1)

@Serializable
data class CancelamentoPrevia(val totalPagoCentavos: Int, val taxaCentavos: Int, val reembolsoCentavos: Int, val taxaPct: Int, val prazoReembolsoHoras: Int = 72)

@Serializable
data class CancelamentoPedido(val motivo: String? = null)

// ---------------------------------------------------------------- D4 notificações

object TipoNotificacao {
    const val COMPRA = "compra"
    const val CANCELAMENTO = "cancelamento"
    const val ATRASO = "atraso"
    const val HORARIO = "horario"
    const val VIAGEM_CANCELADA = "viagem_cancelada"
    const val CONEXAO_RISCO = "conexao_risco"
    const val REMARCACAO = "remarcacao"
    const val SUPORTE = "suporte"
}

@Serializable
data class Notificacao(val id: Int, val tipo: String, val titulo: String, val texto: String, val pedidoId: Int? = null, val criadaEm: Long, val lida: Boolean = false)

/** D4/D5: o que o gestor pode registrar sobre uma viagem. */
object TipoOcorrencia {
    const val ATRASO = "atraso"          // o ônibus está atrasado (chegada e saída adiante)
    const val HORARIO = "horario"        // o horário foi alterado antes da viagem
    const val CANCELADA = "cancelada"    // a empresa cancelou: reembolso integral e automático
    val TODOS = listOf(ATRASO, HORARIO, CANCELADA)
}

@Serializable
data class OcorrenciaPedido(val tipo: String, val minutos: Int = 0)

/** Gestor: uma viagem do dia com o que já foi registrado nela e quantas compras pagas seriam afetadas. */
@Serializable
data class ViagemDoGestor(val viagem: Viagem, val atrasoMin: Int = 0, val cancelada: Boolean = false, val comprasPagas: Int = 0)

@Serializable
data class OcorrenciaResultado(val pedidosAfetados: Int, val reembolsados: Int = 0)

/** D5: viagem em andamento ou a próxima, com a etapa de cada trecho calculada pelo servidor (relógio dele, não do aparelho). */
object EtapaDoTrecho {
    const val CONCLUIDO = "concluido"
    const val EM_ANDAMENTO = "em_andamento"
    const val PROXIMO = "proximo"
    const val CANCELADO = "cancelado"

    /** A etapa de um trecho num instante, já contando o atraso registrado. Servidor e app (sem internet) usam esta mesma conta. */
    fun de(t: PedidoTrecho, agora: Long) = when {
        t.cancelada -> CANCELADO
        t.chegadaReal <= agora -> CONCLUIDO
        t.saidaReal <= agora -> EM_ANDAMENTO
        else -> PROXIMO
    }

    fun de(p: Pedido, agora: Long) = p.trechos.map { de(it, agora) }
}

@Serializable
data class ViagemAtiva(val pedido: Pedido, val etapas: List<String>, val agora: Long)

// ---------------------------------------------------------------- F1 ajuda e F2 avaliação

@Serializable
data class NovoChamado(val pedidoId: Int? = null, val trecho: Int? = null, val assunto: String, val mensagem: String? = null)

@Serializable
data class Chamado(
    val id: Int, val protocolo: String, val pedidoId: Int? = null, val trecho: Int? = null, val assunto: String, val mensagem: String? = null,
    val status: String, val resposta: String? = null, val criadoEm: Long, val respondidoEm: Long? = null,
)

@Serializable
data class RespostaChamado(val texto: String)

@Serializable
data class NovaAvaliacao(val estrelas: Int, val tags: List<String> = emptyList(), val comentario: String? = null, val reclamacao: Boolean = false)

@Serializable
data class Avaliacao(val id: Int, val pedidoId: Int, val trecho: Int, val estrelas: Int, val tags: List<String>, val comentario: String? = null, val reclamacao: Boolean = false, val criadaEm: Long)

object ValidacaoAjuda {
    val ASSUNTOS = listOf("Perdi uma conexão", "Atraso ou cancelamento", "Reembolso", "Bagagem e animais", "Acessibilidade", "Outro")
    val TAGS = listOf("Pontual", "Limpeza", "Conforto", "Acessibilidade", "Atraso")
    const val MENSAGEM_MAX = 1000
    const val COMENTARIO_MAX = 500
    const val RECLAMACAO_MIN = 10

    fun chamado(p: NovoChamado): String? = when {
        p.assunto !in ASSUNTOS -> "Escolha o assunto."
        (p.mensagem?.length ?: 0) > MENSAGEM_MAX -> "A mensagem pode ter no máximo $MENSAGEM_MAX caracteres."
        p.assunto == "Outro" && (p.mensagem?.trim()?.length ?: 0) < 5 -> "Conte o que aconteceu."
        p.trecho != null && p.pedidoId == null -> "Escolha a viagem."
        else -> null
    }

    fun avaliacao(p: NovaAvaliacao): String? = when {
        p.estrelas !in 1..5 -> "Escolha de 1 a 5 estrelas."
        p.tags.any { it !in TAGS } || p.tags.distinct().size != p.tags.size -> "Tag inválida."
        (p.comentario?.length ?: 0) > COMENTARIO_MAX -> "O comentário pode ter no máximo $COMENTARIO_MAX caracteres."
        p.reclamacao && (p.comentario?.trim()?.length ?: 0) < RECLAMACAO_MIN -> "Para registrar uma reclamação, conte o que aconteceu (mínimo $RECLAMACAO_MIN caracteres)."
        else -> null
    }
}

// ---------------------------------------------------------------- comprovante (a tela, o texto copiado e o PDF saem do mesmo conteúdo)

@Serializable
data class LinhaComprovante(val rotulo: String, val valor: String)

@Serializable
data class SecaoComprovante(val titulo: String, val linhas: List<LinhaComprovante>)

/** O servidor monta o conteúdo uma vez só; o app mostra e copia, e o PDF usa as mesmas linhas. */
@Serializable
data class Comprovante(val codigo: String, val secoes: List<SecaoComprovante>, val aviso: String) {
    fun texto(): String = buildString {
        append("Comprovante de compra ").append(codigo).append("\n")
        for (s in secoes) {
            append("\n").append(s.titulo.uppercase()).append("\n")
            for (l in s.linhas) append(if (l.rotulo.isBlank()) l.valor else "${l.rotulo}: ${l.valor}").append("\n")
        }
        append("\n").append(aviso)
    }
}

/** Resposta de `POST /api/pedidos/{id}/comprovante/link`: caminho curto, de uso temporário, que abre o PDF sem login. */
@Serializable
data class LinkDoComprovante(val caminho: String, val validoPorSeg: Int)

// ---------------------------------------------------------------- remarcação

/** Uma viagem para onde o trecho pode ser trocado: mesma rota, de preço igual ou menor, que encaixa com os outros trechos. */
@Serializable
data class Alternativa(val viagemId: Int, val empresa: String, val categoria: String, val saidaEm: Long, val chegadaEm: Long, val precoCentavos: Int)

@Serializable
data class RemarcacaoPedido(val trecho: Int, val viagemId: Int, val poltronas: List<Int>)
