package br.com.talisson.entrevias

import br.com.talisson.entrevias.domain.Bilhete
import br.com.talisson.entrevias.domain.CancelamentoPrevia
import br.com.talisson.entrevias.domain.CategoriaPassageiro
import br.com.talisson.entrevias.domain.Cotador
import br.com.talisson.entrevias.domain.EscolhaDePoltronas
import br.com.talisson.entrevias.domain.MapaDePoltronas
import br.com.talisson.entrevias.domain.NovoPedido
import br.com.talisson.entrevias.domain.Pagamento
import br.com.talisson.entrevias.domain.PagamentoCartaoPedido
import br.com.talisson.entrevias.domain.Pedido
import br.com.talisson.entrevias.domain.PedidoPassageiro
import br.com.talisson.entrevias.domain.PedidoTrecho
import br.com.talisson.entrevias.domain.RegrasDePreco
import br.com.talisson.entrevias.domain.StatusPagamento
import br.com.talisson.entrevias.domain.StatusPedido
import br.com.talisson.entrevias.domain.Totais
import br.com.talisson.entrevias.domain.ValidacaoPerfil
import java.security.SecureRandom
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException

/** Erro de regra de negócio com o status HTTP que a rota deve responder e um texto genérico, sem detalhe interno. */
class ErroDeCompra(val status: Int, override val message: String) : RuntimeException(message)

private fun <T> ResultSet.lista(f: (ResultSet) -> T): List<T> = buildList { while (next()) add(f(this@lista)) }

/**
 * PAGAMENTO SIMULADO (ver docs/SIMULADOS.md). Não há processador de pagamento: o Pix é aprovado por um botão de
 * demonstração e o cartão é aprovado, salvo o terminado em 0002, que é recusado. Em produção (`ENV=production`)
 * a compra fica DESLIGADA (503), em vez de fingir que cobra.
 */
object Psp {
    var simulado = true
    val disponivel get() = !(simulado && Email.emProducao)
    const val CARTAO_RECUSADO = "0002"
    const val MAX_TENTATIVAS_CARTAO = 5
}

/**
 * C1 a C7: carrinho, poltronas, pagamento e bilhetes. O preço vem SEMPRE do banco (o app só diz quais viagens e quem viaja),
 * toda consulta leva o usuario_id da sessão, e a chave primária de `reserva_assento` impede vender a mesma poltrona duas vezes.
 */
object Compras {
    private val aleatorio = SecureRandom()

    /** O aviso é consequência: se falhar, a compra (ou o cancelamento) já aconteceu e não pode virar erro 500 para quem pagou. */
    private inline fun avisar(bloco: () -> Unit) { try { bloco() } catch (e: Exception) { System.err.println("[EntreVias] falha ao gravar o aviso: ${e.javaClass.simpleName}") } }

    // ---------- leitura do pedido ----------

    private fun trechosDe(c: Connection, pedidoId: Int): List<PedidoTrecho> {
        val poltronas = c.prepareStatement("select viagem_id, numero from reserva_assento where pedido_id = ? order by passageiro_ordem").use {
            it.setInt(1, pedidoId); it.executeQuery().use { rs -> rs.lista { r -> r.getInt(1) to r.getInt(2) } }
        }.groupBy({ it.first }, { it.second })
        return c.prepareStatement(
            """select pt.ordem, v.id, e.nome, l.categoria, o.codigo, d.codigo, v.saida_em, v.chegada_em, pt.preco_unit,
                      coalesce(ve.atraso_min, 0), coalesce(ve.cancelada, 0)
               from pedido_trecho pt join viagem v on v.id = pt.viagem_id left join viagem_estado ve on ve.viagem_id = v.id join linha l on l.id = v.linha_id
               join empresa e on e.id = l.empresa_id join terminal o on o.id = l.origem_terminal_id join terminal d on d.id = l.destino_terminal_id
               where pt.pedido_id = ? order by pt.ordem""",
        ).use {
            it.setInt(1, pedidoId)
            it.executeQuery().use { rs -> rs.lista { r -> PedidoTrecho(r.getInt(1), r.getInt(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6), r.getLong(7), r.getLong(8), r.getInt(9), poltronas[r.getInt(2)].orEmpty(), plataforma(r.getInt(2)), r.getInt(10), r.getInt(11) == 1) } }
        }
    }

    /** Plataforma de embarque: determinística pelo número da viagem (dado fictício, como o resto da base). */
    fun plataforma(viagemId: Int) = viagemId % 8 + 1

    private fun passageirosDe(c: Connection, pedidoId: Int): List<PedidoPassageiro> =
        c.prepareStatement("select nome, categoria, idade, documento_final, acessibilidade from pedido_passageiro where pedido_id = ? order by ordem").use {
            it.setInt(1, pedidoId); it.executeQuery().use { rs -> rs.lista { r -> PedidoPassageiro(r.getString(1), r.getString(2), r.getInt(3), r.getString(4), r.getString(5)) } }
        }

    private fun bilhetesDe(c: Connection, pedidoId: Int): List<Bilhete> =
        c.prepareStatement("select codigo, trecho, passageiro, poltrona, plataforma from bilhete where pedido_id = ? order by trecho, rowid").use {
            it.setInt(1, pedidoId); it.executeQuery().use { rs -> rs.lista { r -> Bilhete(r.getString(1), r.getInt(2), r.getString(3), r.getInt(4).takeIf { _ -> !r.wasNull() }, r.getInt(5)) } }
        }

    private fun montar(c: Connection, rs: ResultSet): Pedido {
        val id = rs.getInt("id")
        return Pedido(
            id, rs.getString("codigo") ?: "", rs.getString("status"), trechosDe(c, id), passageirosDe(c, id),
            Totais(rs.getInt("subtotal"), rs.getInt("desconto_categoria"), rs.getInt("cupom_centavos"), rs.getInt("total")),
            rs.getString("cupom"), rs.getLong("criado_em"),
            rs.getLong("pago_em").takeIf { !rs.wasNull() }, rs.getString("metodo"), rs.getLong("reserva_expira_em").takeIf { !rs.wasNull() },
            rs.getLong("cancelado_em").takeIf { !rs.wasNull() }, rs.getInt("reembolso").takeIf { !rs.wasNull() }, bilhetesDe(c, id),
        )
    }

    /** Poltronas que ninguém confirmou e cujo prazo passou voltam à venda. Chamada antes de ler ou reservar. */
    private fun liberarVencidas(c: Connection) {
        val t = Contas.agora()
        c.prepareStatement("delete from reserva_assento where confirmada = 0 and expira_em is not null and expira_em <= ?").use { it.setLong(1, t); it.executeUpdate() }
        c.prepareStatement("update pedido set reserva_expira_em = null where status = 'carrinho' and reserva_expira_em is not null and reserva_expira_em <= ?").use { it.setLong(1, t); it.executeUpdate() }
    }

    fun pedido(usuarioId: Int, id: Int): Pedido? = Banco.conexao().use { c ->
        liberarVencidas(c)
        c.prepareStatement("select * from pedido where id = ? and usuario_id = ?").use {
            it.setInt(1, id); it.setInt(2, usuarioId); it.executeQuery().use { rs -> if (rs.next()) montar(c, rs) else null }
        }
    }

    /** D1. `filtro`: "proximas" (pagas que ainda não saíram), "passadas" (pagas que já saíram), "canceladas"; outro valor = todas (menos o carrinho). */
    fun pedidos(usuarioId: Int, filtro: String? = null): List<Pedido> = Banco.conexao().use { c ->
        liberarVencidas(c)
        val todos = c.prepareStatement("select * from pedido where usuario_id = ? and status <> 'carrinho' order by criado_em desc, id desc").use {
            it.setInt(1, usuarioId); it.executeQuery().use { rs -> rs.lista { r -> montar(c, r) } }
        }
        val t = Contas.agora()
        when (filtro) {
            "proximas" -> todos.filter { it.status == StatusPedido.PAGO && it.saidaEm > t }
            "passadas" -> todos.filter { it.status == StatusPedido.PAGO && it.saidaEm <= t }
            "canceladas" -> todos.filter { it.status == StatusPedido.CANCELADO }
            else -> todos
        }
    }

    // ---------- C1: carrinho ----------

    private data class ViagemBase(val id: Int, val origem: String, val destino: String, val saidaEm: Long, val chegadaEm: Long, val preco: Int)

    private fun cupomPct(c: Connection, codigo: String?): Int? {
        if (codigo.isNullOrBlank()) return null
        return c.prepareStatement("select percentual from cupom where codigo = ? and ativo = 1 and (valido_ate is null or valido_ate > ?)").use {
            it.setString(1, codigo.trim().uppercase()); it.setLong(2, Contas.agora()); it.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else throw ErroDeCompra(404, "Cupom inválido ou vencido.") }
        }
    }

    fun criar(usuarioId: Int, p: NovoPedido): Pedido {
        if (!Psp.disponivel) throw ErroDeCompra(503, "A compra está indisponível no momento.")
        if (p.viagemIds.isEmpty() || p.viagemIds.size > 3 || p.viagemIds.distinct().size != p.viagemIds.size) throw ErroDeCompra(400, "Escolha de 1 a 3 trechos.")
        if (p.passageiroIds.isEmpty() || p.passageiroIds.size > RegrasDePreco.MAX_PASSAGEIROS || p.passageiroIds.distinct().size != p.passageiroIds.size) throw ErroDeCompra(400, "Escolha de 1 a ${RegrasDePreco.MAX_PASSAGEIROS} passageiros.")
        return Banco.conexao().use { c ->
            val viagens = p.viagemIds.map { id ->
                c.prepareStatement(
                    """select v.id, o.codigo, d.codigo, v.saida_em, v.chegada_em, v.preco_centavos from viagem v join linha l on l.id = v.linha_id
                       join empresa e on e.id = l.empresa_id and e.ativa = 1 join terminal o on o.id = l.origem_terminal_id join terminal d on d.id = l.destino_terminal_id where v.id = ?""",
                ).use { it.setInt(1, id); it.executeQuery().use { rs -> if (rs.next()) ViagemBase(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getLong(5), rs.getInt(6)) else null } }
                    ?: throw ErroDeCompra(404, "Viagem não encontrada.")
            }
            if (viagens.first().saidaEm <= Contas.agora()) throw ErroDeCompra(409, "Essa viagem já saiu.")
            val minima = Banco.parametros().minimaMin * 60L
            viagens.zipWithNext().forEach { (a, b) -> if (a.destino != b.origem || b.saidaEm - a.chegadaEm < minima) throw ErroDeCompra(409, "Esses trechos não formam uma conexão possível.") }

            val passageiros = p.passageiroIds.map { id ->
                c.prepareStatement("select nome, idade, documento_final, acessibilidade from passageiro where id = ? and usuario_id = ?").use {
                    it.setInt(1, id); it.setInt(2, usuarioId)
                    it.executeQuery().use { rs -> if (rs.next()) PedidoPassageiro(rs.getString(1), CategoriaPassageiro.daIdade(rs.getInt(2)), rs.getInt(2), rs.getString(3), rs.getString(4)) else null }
                } ?: throw ErroDeCompra(404, "Passageiro não encontrado.")
            }
            val categorias = passageiros.map { it.categoria }
            if (RegrasDePreco.poltronasNecessarias(categorias) == 0) throw ErroDeCompra(400, "Pelo menos um passageiro precisa de poltrona.")
            if (!RegrasDePreco.temResponsavel(categorias)) throw ErroDeCompra(400, "Menor de 12 anos precisa viajar com um adulto ou idoso.")

            val pct = cupomPct(c, p.cupom)
            val totais = Cotador.cotar(viagens.map { it.preco }, categorias, pct)
            val t = Contas.agora()
            c.autoCommit = false
            try {
                val id = c.prepareStatement(
                    "insert into pedido (usuario_id, status, cupom, subtotal, desconto_categoria, cupom_centavos, total, criado_em) values (?,?,?,?,?,?,?,?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS,
                ).use {
                    it.setInt(1, usuarioId); it.setString(2, StatusPedido.CARRINHO); it.setString(3, p.cupom?.trim()?.uppercase()?.takeIf { _ -> pct != null })
                    it.setInt(4, totais.subtotalCentavos); it.setInt(5, totais.descontoCategoriaCentavos); it.setInt(6, totais.cupomCentavos); it.setInt(7, totais.totalCentavos); it.setLong(8, t)
                    it.executeUpdate(); it.generatedKeys.use { rs -> rs.next(); rs.getInt(1) }
                }
                c.prepareStatement("update pedido set codigo = ? where id = ?").use { it.setString(1, "EV-%04d".format(4800 + id)); it.setInt(2, id); it.executeUpdate() }
                viagens.forEachIndexed { i, v -> c.prepareStatement("insert into pedido_trecho (pedido_id, ordem, viagem_id, preco_unit) values (?,?,?,?)").use { it.setInt(1, id); it.setInt(2, i + 1); it.setInt(3, v.id); it.setInt(4, v.preco); it.executeUpdate() } }
                passageiros.forEachIndexed { i, q ->
                    c.prepareStatement("insert into pedido_passageiro (pedido_id, ordem, nome, categoria, idade, documento_final, acessibilidade) values (?,?,?,?,?,?,?)").use {
                        it.setInt(1, id); it.setInt(2, i + 1); it.setString(3, q.nome); it.setString(4, q.categoria); it.setInt(5, q.idade); it.setString(6, q.documentoFinal); it.setString(7, q.acessibilidade); it.executeUpdate()
                    }
                }
                c.commit()
                c.autoCommit = true
                c.prepareStatement("select * from pedido where id = ?").use { it.setInt(1, id); it.executeQuery().use { rs -> rs.next(); montar(c, rs) } }
            } catch (e: Exception) { c.rollback(); throw e }
        }
    }

    /** Aplica ou tira o cupom (codigo nulo) e recalcula tudo no servidor. Só enquanto o pedido é carrinho. */
    fun definirCupom(usuarioId: Int, id: Int, codigo: String?): Pedido {
        val atual = pedido(usuarioId, id) ?: throw ErroDeCompra(404, "Pedido não encontrado.")
        if (atual.status != StatusPedido.CARRINHO) throw ErroDeCompra(409, "Este pedido não aceita mais alterações.")
        return Banco.conexao().use { c ->
            val pct = cupomPct(c, codigo)
            val totais = Cotador.cotar(atual.trechos.map { it.precoUnitCentavos }, atual.passageiros.map { it.categoria }, pct)
            c.prepareStatement("update pedido set cupom = ?, cupom_centavos = ?, total = ? where id = ? and usuario_id = ?").use {
                it.setString(1, codigo?.trim()?.uppercase()?.takeIf { _ -> pct != null }); it.setInt(2, totais.cupomCentavos); it.setInt(3, totais.totalCentavos); it.setInt(4, id); it.setInt(5, usuarioId); it.executeUpdate()
            }
            pedido(usuarioId, id)!!
        }
    }

    // ---------- C2: poltronas ----------

    /** Poltronas já vendidas a outras pessoas. SIMULADO: gerado pelo número da viagem, para o mapa não vir vazio. */
    fun ocupadasSimuladas(viagemId: Int): Set<Int> =
        (1..RegrasDePreco.POLTRONAS).filter { it !in RegrasDePreco.POLTRONAS_PCD && (viagemId * 31 + it * 17) % 100 < 22 }.toSet()

    fun mapa(usuarioId: Int, pedidoId: Int, viagemId: Int): MapaDePoltronas {
        val ped = pedido(usuarioId, pedidoId) ?: throw ErroDeCompra(404, "Pedido não encontrado.")
        val trecho = ped.trechos.firstOrNull { it.viagemId == viagemId } ?: throw ErroDeCompra(404, "Trecho não encontrado.")
        val ocupadasPorOutros = Banco.conexao().use { c ->
            c.prepareStatement("select numero from reserva_assento where viagem_id = ? and pedido_id <> ?").use { it.setInt(1, viagemId); it.setInt(2, pedidoId); it.executeQuery().use { rs -> rs.lista { r -> r.getInt(1) } } }
        }
        return MapaDePoltronas(viagemId, RegrasDePreco.POLTRONAS, (ocupadasSimuladas(viagemId) + ocupadasPorOutros).sorted(), RegrasDePreco.POLTRONAS_PCD.sorted(), trecho.poltronas)
    }

    fun escolher(usuarioId: Int, pedidoId: Int, escolhas: List<EscolhaDePoltronas>): Pedido {
        val ped = pedido(usuarioId, pedidoId) ?: throw ErroDeCompra(404, "Pedido não encontrado.")
        if (ped.status != StatusPedido.CARRINHO) throw ErroDeCompra(409, "Este pedido não aceita mais alterações.")
        val necessarias = RegrasDePreco.poltronasNecessarias(ped.passageiros.map { it.categoria })
        val comAcessibilidade = ped.passageiros.count { it.categoria != CategoriaPassageiro.COLO && !it.acessibilidade.isNullOrBlank() }
        if (escolhas.map { it.viagemId }.sorted() != ped.trechos.map { it.viagemId }.sorted()) throw ErroDeCompra(400, "Escolha as poltronas de todos os trechos.")
        for (e in escolhas) {
            if (e.poltronas.size != necessarias || e.poltronas.distinct().size != necessarias) throw ErroDeCompra(400, "Escolha exatamente $necessarias poltronas por trecho.")
            if (e.poltronas.any { it !in 1..RegrasDePreco.POLTRONAS }) throw ErroDeCompra(400, "Poltrona inválida.")
            if (e.poltronas.any { it in ocupadasSimuladas(e.viagemId) }) throw ErroDeCompra(409, "Uma das poltronas acabou de ser ocupada. Escolha outra.")
            if (e.poltronas.count { it in RegrasDePreco.POLTRONAS_PCD } > comAcessibilidade) throw ErroDeCompra(409, "As poltronas PcD são para passageiros com necessidade de acessibilidade cadastrada.")
        }
        val expira = Contas.agora() + RegrasDePreco.RESERVA_SEG
        return Banco.conexao().use { c ->
            liberarVencidas(c)
            c.autoCommit = false
            try {
                c.prepareStatement("delete from reserva_assento where pedido_id = ?").use { it.setInt(1, pedidoId); it.executeUpdate() }
                escolhas.forEach { e -> e.poltronas.forEachIndexed { i, n ->
                    c.prepareStatement("insert into reserva_assento (viagem_id, numero, pedido_id, passageiro_ordem, confirmada, expira_em) values (?,?,?,?,0,?)").use {
                        it.setInt(1, e.viagemId); it.setInt(2, n); it.setInt(3, pedidoId); it.setInt(4, i + 1); it.setLong(5, expira); it.executeUpdate()
                    }
                } }
                c.prepareStatement("update pedido set reserva_expira_em = ? where id = ?").use { it.setLong(1, expira); it.setInt(2, pedidoId); it.executeUpdate() }
                c.commit(); c.autoCommit = true
            } catch (e: SQLException) {
                c.rollback()
                if (e.message?.contains("UNIQUE", true) == true || e.message?.contains("PRIMARY KEY", true) == true) throw ErroDeCompra(409, "Uma das poltronas acabou de ser ocupada. Escolha outra.")
                throw e
            }
            pedido(usuarioId, pedidoId)!!
        }
    }

    // ---------- C3 a C6: pagamento (SIMULADO) ----------

    private fun pagamentoDe(rs: ResultSet) = Pagamento(rs.getInt("id"), rs.getInt("pedido_id"), rs.getString("metodo"), rs.getString("status"), rs.getString("codigo_pix"), rs.getLong("expira_em").takeIf { !rs.wasNull() }, rs.getString("motivo"), rs.getInt("parcelas"))

    private fun prontoParaPagar(usuarioId: Int, pedidoId: Int): Pedido {
        if (!Psp.disponivel) throw ErroDeCompra(503, "A compra está indisponível no momento.")
        val ped = pedido(usuarioId, pedidoId) ?: throw ErroDeCompra(404, "Pedido não encontrado.")
        if (ped.status != StatusPedido.CARRINHO) throw ErroDeCompra(409, "Este pedido não aceita pagamento.")
        if (!ped.poltronasCompletas) throw ErroDeCompra(409, "Escolha as poltronas antes de pagar. Se o prazo da reserva acabou, escolha de novo.")
        return ped
    }

    private fun gravar(c: Connection, pedidoId: Int, metodo: String, status: String, pix: String?, expira: Long?, motivo: String?, parcelas: Int): Int =
        c.prepareStatement("insert into pagamento (pedido_id, metodo, status, codigo_pix, expira_em, motivo, parcelas, criado_em) values (?,?,?,?,?,?,?,?)", java.sql.Statement.RETURN_GENERATED_KEYS).use {
            it.setInt(1, pedidoId); it.setString(2, metodo); it.setString(3, status); it.setString(4, pix); if (expira != null) it.setLong(5, expira) else it.setNull(5, java.sql.Types.INTEGER)
            it.setString(6, motivo); it.setInt(7, parcelas); it.setLong(8, Contas.agora()); it.executeUpdate(); it.generatedKeys.use { rs -> rs.next(); rs.getInt(1) }
        }

    private fun buscarPagamento(c: Connection, id: Int) = c.prepareStatement("select * from pagamento where id = ?").use { it.setInt(1, id); it.executeQuery().use { rs -> if (rs.next()) pagamentoDe(rs) else null } }

    fun pagarComPix(usuarioId: Int, pedidoId: Int): Pagamento {
        prontoParaPagar(usuarioId, pedidoId)
        val expira = Contas.agora() + RegrasDePreco.PIX_VALIDADE_SEG
        val codigo = "EVSIM" + ByteArray(12).also { aleatorio.nextBytes(it) }.joinToString("") { "%02X".format(it) }   // não é um Pix de verdade
        return Banco.conexao().use { c ->
            // o Pix vale 10 min: as poltronas ficam guardadas pelo menos até lá
            c.prepareStatement("update reserva_assento set expira_em = max(expira_em, ?) where pedido_id = ? and confirmada = 0").use { it.setLong(1, expira); it.setInt(2, pedidoId); it.executeUpdate() }
            c.prepareStatement("update pedido set reserva_expira_em = max(coalesce(reserva_expira_em, 0), ?) where id = ?").use { it.setLong(1, expira); it.setInt(2, pedidoId); it.executeUpdate() }
            buscarPagamento(c, gravar(c, pedidoId, "pix", StatusPagamento.PENDENTE, codigo, expira, null, 1))!!
        }
    }

    fun pagarComCartao(usuarioId: Int, pedidoId: Int, p: PagamentoCartaoPedido): Pagamento {
        val ped = prontoParaPagar(usuarioId, pedidoId)
        if (p.ultimos4.length != 4 || p.ultimos4.any { it !in '0'..'9' } || p.bandeira !in ValidacaoPerfil.BANDEIRAS) throw ErroDeCompra(400, "Dados do cartão inválidos.")
        if (p.parcelas !in 1..6 || (p.parcelas > 1 && ped.totais.totalCentavos / p.parcelas < 500)) throw ErroDeCompra(400, "Parcelamento inválido para este valor.")
        return Banco.conexao().use { c ->
            val tentativas = c.prepareStatement("select count(*) from pagamento where pedido_id = ? and metodo = 'cartao'").use { it.setInt(1, pedidoId); it.executeQuery().use { rs -> rs.next(); rs.getInt(1) } }
            if (tentativas >= Psp.MAX_TENTATIVAS_CARTAO) throw ErroDeCompra(429, "Muitas tentativas com cartão neste pedido. Tente o Pix.")
            if (p.ultimos4 == Psp.CARTAO_RECUSADO) buscarPagamento(c, gravar(c, pedidoId, "cartao", StatusPagamento.RECUSADO, null, null, "O cartão foi recusado pelo banco.", p.parcelas))!!
            else aprovar(c, pedidoId, buscarPagamento(c, gravar(c, pedidoId, "cartao", StatusPagamento.PENDENTE, null, null, null, p.parcelas))!!.id)
        }
    }

    /** Aprova o pagamento: pedido vira pago, poltronas confirmadas, bilhetes emitidos. Uma transação só. */
    private fun aprovar(c: Connection, pedidoId: Int, pagamentoId: Int): Pagamento {
        c.autoCommit = false
        try {
            val metodo = c.prepareStatement("select metodo from pagamento where id = ?").use { it.setInt(1, pagamentoId); it.executeQuery().use { rs -> rs.next(); rs.getString(1) } }
            c.prepareStatement("update pagamento set status = 'aprovado' where id = ?").use { it.setInt(1, pagamentoId); it.executeUpdate() }
            c.prepareStatement("update pedido set status = 'pago', pago_em = ?, metodo = ?, reserva_expira_em = null where id = ?").use { it.setLong(1, Contas.agora()); it.setString(2, metodo); it.setInt(3, pedidoId); it.executeUpdate() }
            c.prepareStatement("update reserva_assento set confirmada = 1, expira_em = null where pedido_id = ?").use { it.setInt(1, pedidoId); it.executeUpdate() }
            val trechos = trechosDe(c, pedidoId); val pass = passageirosDe(c, pedidoId).filter { it.categoria != CategoriaPassageiro.COLO }
            val bebes = passageirosDe(c, pedidoId).filter { it.categoria == CategoriaPassageiro.COLO }
            for (t in trechos) {
                pass.forEachIndexed { i, q -> inserirBilhete(c, pedidoId, t, q.nome, t.poltronas.getOrNull(i)) }
                bebes.forEach { inserirBilhete(c, pedidoId, t, it.nome + " (colo)", null) }
            }
            c.commit(); c.autoCommit = true
        } catch (e: Exception) { runCatching { c.rollback() }; c.autoCommit = true; throw e }
        avisar { Notificacoes.compraConfirmada(c, pedidoId) }
        return buscarPagamento(c, pagamentoId)!!
    }

    private fun inserirBilhete(c: Connection, pedidoId: Int, t: PedidoTrecho, nome: String, poltrona: Int?) {
        val codigo = "EVB" + ByteArray(6).also { aleatorio.nextBytes(it) }.joinToString("") { "%02X".format(it) }
        c.prepareStatement("insert into bilhete (codigo, pedido_id, trecho, passageiro, poltrona, plataforma) values (?,?,?,?,?,?)").use {
            it.setString(1, codigo); it.setInt(2, pedidoId); it.setInt(3, t.ordem); it.setString(4, nome)
            if (poltrona != null) it.setInt(5, poltrona) else it.setNull(5, java.sql.Types.INTEGER); it.setInt(6, t.plataforma ?: 1); it.executeUpdate()
        }
    }

    /** Estado do pagamento; um Pix vencido vira "expirado" e devolve as poltronas à venda. */
    fun pagamento(usuarioId: Int, id: Int): Pagamento = Banco.conexao().use { c ->
        val p = buscarPagamento(c, id) ?: throw ErroDeCompra(404, "Pagamento não encontrado.")
        if (pedido(usuarioId, p.pedidoId) == null) throw ErroDeCompra(404, "Pagamento não encontrado.")
        val exp = p.expiraEm
        if (p.status == StatusPagamento.PENDENTE && exp != null && exp <= Contas.agora()) {
            c.prepareStatement("update pagamento set status = 'expirado' where id = ? and status = 'pendente'").use { it.setInt(1, id); it.executeUpdate() }
            c.prepareStatement("delete from reserva_assento where pedido_id = ? and confirmada = 0").use { it.setInt(1, p.pedidoId); it.executeUpdate() }
            c.prepareStatement("update pedido set reserva_expira_em = null where id = ? and status = 'carrinho'").use { it.setInt(1, p.pedidoId); it.executeUpdate() }
            buscarPagamento(c, id)!!
        } else p
    }

    /** SIMULADO: no lugar do aviso do banco, um botão de demonstração aprova o Pix. Não existe em produção. */
    fun simularPixPago(usuarioId: Int, id: Int): Pagamento {
        if (!Psp.simulado || Email.emProducao) throw ErroDeCompra(404, "Pagamento não encontrado.")
        val p = pagamento(usuarioId, id)
        if (p.metodo != "pix") throw ErroDeCompra(404, "Pagamento não encontrado.")
        if (p.status != StatusPagamento.PENDENTE) throw ErroDeCompra(409, "Este Pix não está mais aguardando pagamento.")
        val ped = pedido(usuarioId, p.pedidoId)!!
        if (!ped.poltronasCompletas) throw ErroDeCompra(409, "A reserva das poltronas venceu. Escolha de novo.")
        return Banco.conexao().use { c -> aprovar(c, p.pedidoId, id) }
    }

    // ---------- D3: cancelamento ----------

    fun taxaDeCancelamentoPct(): Int = Banco.conexao().use { c ->
        c.prepareStatement("select valor from parametro where chave = 'taxa_cancelamento_pct'").use { it.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else RegrasDePreco.TAXA_CANCELAMENTO_PCT } }
    }

    fun previaDeCancelamento(usuarioId: Int, id: Int): CancelamentoPrevia {
        val ped = pedido(usuarioId, id) ?: throw ErroDeCompra(404, "Pedido não encontrado.")
        if (ped.status != StatusPedido.PAGO) throw ErroDeCompra(409, "Só dá para cancelar uma compra paga.")
        if (ped.saidaEm <= Contas.agora()) throw ErroDeCompra(409, "A viagem já começou e não pode mais ser cancelada.")
        val pct = taxaDeCancelamentoPct()
        val (taxa, reembolso) = Cotador.reembolso(ped.totais.totalCentavos, pct)
        return CancelamentoPrevia(ped.totais.totalCentavos, taxa, reembolso, pct)
    }

    /** Cancela os trechos juntos: bilhetes saem, poltronas voltam à venda e o reembolso fica registrado (SIMULADO: não há estorno de verdade). */
    fun cancelar(usuarioId: Int, id: Int, motivo: String?): Pedido {
        val previa = previaDeCancelamento(usuarioId, id)
        Banco.conexao().use { c ->
            c.autoCommit = false
            try {
                val n = c.prepareStatement("update pedido set status = 'cancelado', cancelado_em = ?, reembolso = ?, motivo_cancelamento = ? where id = ? and usuario_id = ? and status = 'pago'").use {
                    it.setLong(1, Contas.agora()); it.setInt(2, previa.reembolsoCentavos); it.setString(3, motivo?.take(60)); it.setInt(4, id); it.setInt(5, usuarioId); it.executeUpdate()
                }
                if (n == 0) throw ErroDeCompra(409, "Esta compra já foi cancelada.")
                c.prepareStatement("delete from reserva_assento where pedido_id = ?").use { it.setInt(1, id); it.executeUpdate() }
                c.prepareStatement("delete from bilhete where pedido_id = ?").use { it.setInt(1, id); it.executeUpdate() }
                c.commit(); c.autoCommit = true
            } catch (e: Exception) { runCatching { c.rollback() }; c.autoCommit = true; throw e }
            avisar { Notificacoes.cancelamento(c, id, previa.reembolsoCentavos) }
        }
        return pedido(usuarioId, id)!!
    }

    /** Cancelamento por iniciativa da empresa (viagem cancelada): reembolso de 100%, sem taxa, sem checar a data. */
    fun cancelarPelaEmpresa(c: Connection, usuarioId: Int, id: Int) {
        val total = c.prepareStatement("select total from pedido where id = ? and usuario_id = ? and status = 'pago'").use { it.setInt(1, id); it.setInt(2, usuarioId); it.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else return } }
        c.autoCommit = false
        try {
            c.prepareStatement("update pedido set status = 'cancelado', cancelado_em = ?, reembolso = ?, motivo_cancelamento = 'Viagem cancelada pela empresa' where id = ?").use { it.setLong(1, Contas.agora()); it.setInt(2, total); it.setInt(3, id); it.executeUpdate() }
            c.prepareStatement("delete from reserva_assento where pedido_id = ?").use { it.setInt(1, id); it.executeUpdate() }
            c.prepareStatement("delete from bilhete where pedido_id = ?").use { it.setInt(1, id); it.executeUpdate() }
            c.commit(); c.autoCommit = true
        } catch (e: Exception) { runCatching { c.rollback() }; c.autoCommit = true; throw e }
    }
}
