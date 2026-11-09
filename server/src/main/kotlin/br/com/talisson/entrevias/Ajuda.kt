package br.com.talisson.entrevias

import br.com.talisson.entrevias.domain.Avaliacao
import br.com.talisson.entrevias.domain.Chamado
import br.com.talisson.entrevias.domain.NovaAvaliacao
import br.com.talisson.entrevias.domain.NovoChamado
import br.com.talisson.entrevias.domain.StatusPedido
import br.com.talisson.entrevias.domain.TipoNotificacao
import br.com.talisson.entrevias.domain.ValidacaoAjuda
import java.sql.ResultSet

/**
 * F1 (ajuda) e F2 (avaliação). Tudo é da pessoa logada e só ela vê o que é dela; o gestor lê tudo.
 * O suporte real é de uma empresa terceira (ver docs/SIMULADOS.md): aqui o pedido vira um chamado com protocolo.
 */
object Ajuda {
    private fun chamadoDe(rs: ResultSet) = Chamado(
        rs.getInt("id"), rs.getString("protocolo") ?: "", rs.getInt("pedido_id").takeIf { !rs.wasNull() }, rs.getInt("trecho").takeIf { !rs.wasNull() },
        rs.getString("assunto"), rs.getString("mensagem"), rs.getString("status"), rs.getString("resposta"), rs.getLong("criado_em"), rs.getLong("respondido_em").takeIf { !rs.wasNull() },
    )

    fun abrir(usuarioId: Int, p: NovoChamado): Chamado {
        ValidacaoAjuda.chamado(p)?.let { throw ErroDeCompra(400, it) }
        val pedido = p.pedidoId?.let { Compras.pedido(usuarioId, it) ?: throw ErroDeCompra(404, "Viagem não encontrada.") }
        if (p.trecho != null && pedido!!.trechos.none { it.ordem == p.trecho }) throw ErroDeCompra(404, "Trecho não encontrado.")
        val pedidoRef = p.pedidoId; val trechoRef = p.trecho
        return Banco.conexao().use { c ->
            val id = c.prepareStatement("insert into chamado (usuario_id, pedido_id, trecho, assunto, mensagem, criado_em) values (?,?,?,?,?,?)", java.sql.Statement.RETURN_GENERATED_KEYS).use {
                it.setInt(1, usuarioId); if (pedidoRef != null) it.setInt(2, pedidoRef) else it.setNull(2, java.sql.Types.INTEGER)
                if (trechoRef != null) it.setInt(3, trechoRef) else it.setNull(3, java.sql.Types.INTEGER)
                it.setString(4, p.assunto); it.setString(5, p.mensagem?.trim()?.takeIf { m -> m.isNotEmpty() }); it.setLong(6, Contas.agora())
                it.executeUpdate(); it.generatedKeys.use { rs -> rs.next(); rs.getInt(1) }
            }
            c.prepareStatement("update chamado set protocolo = ? where id = ?").use { it.setString(1, "AJ-%05d".format(10_000 + id)); it.setInt(2, id); it.executeUpdate() }
            c.prepareStatement("select * from chamado where id = ?").use { it.setInt(1, id); it.executeQuery().use { rs -> rs.next(); chamadoDe(rs) } }
        }
    }

    fun meus(usuarioId: Int): List<Chamado> = Banco.conexao().use { c ->
        c.prepareStatement("select * from chamado where usuario_id = ? order by criado_em desc, id desc").use { it.setInt(1, usuarioId); it.executeQuery().use { rs -> buildList { while (rs.next()) add(chamadoDe(rs)) } } }
    }

    /** Gestor: tudo, os abertos primeiro. */
    fun todos(): List<Chamado> = Banco.conexao().use { c ->
        c.createStatement().use { s -> s.executeQuery("select * from chamado order by (status = 'aberto') desc, criado_em desc, id desc limit 200").use { rs -> buildList { while (rs.next()) add(chamadoDe(rs)) } } }
    }

    fun responder(id: Int, texto: String): Chamado {
        if (texto.trim().length !in 2..1000) throw ErroDeCompra(400, "A resposta precisa ter de 2 a 1000 caracteres.")
        return Banco.conexao().use { c ->
            val dono = c.prepareStatement("select usuario_id, protocolo from chamado where id = ?").use { it.setInt(1, id); it.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) to rs.getString(2) else null } } ?: throw ErroDeCompra(404, "Chamado não encontrado.")
            c.prepareStatement("update chamado set status = 'respondido', resposta = ?, respondido_em = ? where id = ?").use { it.setString(1, texto.trim()); it.setLong(2, Contas.agora()); it.setInt(3, id); it.executeUpdate() }
            Notificacoes.criar(c, dono.first, TipoNotificacao.SUPORTE, "O suporte respondeu · ${dono.second}", texto.trim().take(200), null)
            c.prepareStatement("select * from chamado where id = ?").use { it.setInt(1, id); it.executeQuery().use { rs -> rs.next(); chamadoDe(rs) } }
        }
    }

    // ---------- F2 ----------

    private fun avaliacaoDe(rs: ResultSet) = Avaliacao(rs.getInt("id"), rs.getInt("pedido_id"), rs.getInt("trecho"), rs.getInt("estrelas"), rs.getString("tags").split(',').filter { it.isNotEmpty() }, rs.getString("comentario"), rs.getInt("reclamacao") == 1, rs.getLong("criada_em"))

    /** Só se avalia o que foi viajado: compra paga e trecho que já chegou (pelo horário real, com atraso). Uma avaliação por trecho. */
    fun avaliar(usuarioId: Int, pedidoId: Int, trecho: Int, p: NovaAvaliacao): Avaliacao {
        ValidacaoAjuda.avaliacao(p)?.let { throw ErroDeCompra(400, it) }
        val ped = Compras.pedido(usuarioId, pedidoId) ?: throw ErroDeCompra(404, "Viagem não encontrada.")
        val t = ped.trechos.firstOrNull { it.ordem == trecho } ?: throw ErroDeCompra(404, "Trecho não encontrado.")
        if (ped.status != StatusPedido.PAGO) throw ErroDeCompra(409, "Só dá para avaliar uma viagem que você fez.")
        if (t.chegadaReal > Contas.agora()) throw ErroDeCompra(409, "Você poderá avaliar depois de chegar ao destino.")
        return Banco.conexao().use { c ->
            try {
                c.prepareStatement("insert into avaliacao (usuario_id, pedido_id, trecho, estrelas, tags, comentario, reclamacao, criada_em) values (?,?,?,?,?,?,?,?)", java.sql.Statement.RETURN_GENERATED_KEYS).use {
                    it.setInt(1, usuarioId); it.setInt(2, pedidoId); it.setInt(3, trecho); it.setInt(4, p.estrelas); it.setString(5, p.tags.joinToString(","))
                    it.setString(6, p.comentario?.trim()?.takeIf { m -> m.isNotEmpty() }); it.setInt(7, if (p.reclamacao) 1 else 0); it.setLong(8, Contas.agora())
                    it.executeUpdate(); val id = it.generatedKeys.use { rs -> rs.next(); rs.getInt(1) }
                    c.prepareStatement("select * from avaliacao where id = ?").use { s -> s.setInt(1, id); s.executeQuery().use { rs -> rs.next(); avaliacaoDe(rs) } }
                }
            } catch (e: java.sql.SQLException) {
                if (e.message?.contains("UNIQUE", true) == true) throw ErroDeCompra(409, "Você já avaliou este trecho.") else throw e
            }
        }
    }

    fun minhas(usuarioId: Int, pedidoId: Int): List<Avaliacao> {
        Compras.pedido(usuarioId, pedidoId) ?: throw ErroDeCompra(404, "Viagem não encontrada.")
        return Banco.conexao().use { c -> c.prepareStatement("select * from avaliacao where pedido_id = ? and usuario_id = ? order by trecho").use { it.setInt(1, pedidoId); it.setInt(2, usuarioId); it.executeQuery().use { rs -> buildList { while (rs.next()) add(avaliacaoDe(rs)) } } } }
    }

    fun todasAsReclamacoes(): List<Avaliacao> = Banco.conexao().use { c ->
        c.createStatement().use { s -> s.executeQuery("select * from avaliacao where reclamacao = 1 order by criada_em desc limit 200").use { rs -> buildList { while (rs.next()) add(avaliacaoDe(rs)) } } }
    }
}
