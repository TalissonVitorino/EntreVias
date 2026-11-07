package br.com.talisson.entrevias

import br.com.talisson.entrevias.domain.Notificacao
import br.com.talisson.entrevias.domain.TipoNotificacao
import java.sql.Connection

/** D4: avisos da pessoa. Só quem é o dono lê e marca; o texto nunca leva dado de pagamento. */
object Notificacoes {
    fun criar(c: Connection, usuarioId: Int, tipo: String, titulo: String, texto: String, pedidoId: Int? = null) {
        c.prepareStatement("insert into aviso (usuario_id, tipo, titulo, texto, pedido_id, criada_em) values (?,?,?,?,?,?)").use {
            it.setInt(1, usuarioId); it.setString(2, tipo); it.setString(3, titulo); it.setString(4, texto)
            if (pedidoId != null) it.setInt(5, pedidoId) else it.setNull(5, java.sql.Types.INTEGER); it.setLong(6, Contas.agora()); it.executeUpdate()
        }
    }

    private fun dono(c: Connection, pedidoId: Int): Pair<Int, String>? =
        c.prepareStatement("select usuario_id, codigo from pedido where id = ?").use { it.setInt(1, pedidoId); it.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) to rs.getString(2) else null } }

    fun compraConfirmada(c: Connection, pedidoId: Int) {
        val (u, cod) = dono(c, pedidoId) ?: return
        criar(c, u, TipoNotificacao.COMPRA, "Compra confirmada · $cod", "Seus bilhetes foram emitidos. Veja em Viagens.", pedidoId)
    }

    fun cancelamento(c: Connection, pedidoId: Int, reembolsoCentavos: Int) {
        val (u, cod) = dono(c, pedidoId) ?: return
        criar(c, u, TipoNotificacao.CANCELAMENTO, "Compra cancelada · $cod", "O reembolso de ${reais(reembolsoCentavos)} será feito em até 72 horas, na forma de pagamento de origem.", pedidoId)
    }

    fun reais(centavos: Int) = "R$ %d,%02d".format(centavos / 100, centavos % 100)

    fun lista(usuarioId: Int): List<Notificacao> = Banco.conexao().use { c ->
        c.prepareStatement("select id, tipo, titulo, texto, pedido_id, criada_em, lida from aviso where usuario_id = ? order by criada_em desc, id desc limit 100").use {
            it.setInt(1, usuarioId)
            it.executeQuery().use { rs -> buildList { while (rs.next()) add(Notificacao(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5).takeIf { _ -> !rs.wasNull() }, rs.getLong(6), rs.getInt(7) == 1)) } }
        }
    }

    fun marcarLidas(usuarioId: Int) = Banco.conexao().use { c ->
        c.prepareStatement("update aviso set lida = 1 where usuario_id = ? and lida = 0").use { it.setInt(1, usuarioId); it.executeUpdate() }
    }
}
