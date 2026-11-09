package br.com.talisson.entrevias

import br.com.talisson.entrevias.domain.EtapaDoTrecho
import br.com.talisson.entrevias.domain.OcorrenciaResultado
import br.com.talisson.entrevias.domain.Pedido
import br.com.talisson.entrevias.domain.StatusPedido
import br.com.talisson.entrevias.domain.TipoNotificacao
import br.com.talisson.entrevias.domain.TipoOcorrencia
import br.com.talisson.entrevias.domain.ViagemAtiva
import java.sql.Connection

/**
 * D4 e D5. O gestor registra o que aconteceu com uma viagem (atraso, horário alterado, cancelamento); quem tem compra paga
 * nela é avisado. Cancelamento pela empresa reembolsa 100% e sozinho (sem taxa). Atraso que aperta a conexão gera aviso de risco.
 */
object Ocorrencias {
    private fun afetados(c: Connection, viagemId: Int): List<Int> =
        c.prepareStatement("select p.id from pedido p join pedido_trecho pt on pt.pedido_id = p.id where pt.viagem_id = ? and p.status = 'pago' order by p.id").use {
            it.setInt(1, viagemId); it.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getInt(1)) } }
        }

    private fun nomeDoTrecho(p: Pedido, viagemId: Int, cidade: (String) -> String): String =
        p.trechos.first { it.viagemId == viagemId }.let { "${cidade(it.origem)} → ${cidade(it.destino)}" }

    /** Gestor: as viagens de um dia, cada uma com a ocorrência já registrada e o número de compras pagas. */
    fun painel(data: java.time.LocalDate): List<br.com.talisson.entrevias.domain.ViagemDoGestor> {
        val viagens = Banco.viagens(data, 1)
        if (viagens.isEmpty()) return emptyList()
        return Banco.conexao().use { c ->
            val estado = c.createStatement().use { s -> s.executeQuery("select viagem_id, atraso_min, cancelada from viagem_estado").use { rs -> buildMap { while (rs.next()) put(rs.getInt(1), rs.getInt(2) to (rs.getInt(3) == 1)) } } }
            val compras = c.createStatement().use { s ->
                s.executeQuery("select pt.viagem_id, count(distinct p.id) from pedido_trecho pt join pedido p on p.id = pt.pedido_id where p.status = 'pago' group by pt.viagem_id")
                    .use { rs -> buildMap { while (rs.next()) put(rs.getInt(1), rs.getInt(2)) } }
            }
            viagens.map { v -> br.com.talisson.entrevias.domain.ViagemDoGestor(v, estado[v.id]?.first ?: 0, estado[v.id]?.second ?: false, compras[v.id] ?: 0) }
        }
    }

    fun registrar(viagemId: Int, tipo: String, minutos: Int): OcorrenciaResultado {
        if (tipo !in TipoOcorrencia.TODOS) throw ErroDeCompra(400, "Tipo de ocorrência inválido.")
        when (tipo) {
            TipoOcorrencia.ATRASO -> if (minutos !in 1..600) throw ErroDeCompra(400, "Informe o atraso em minutos (de 1 a 600).")
            TipoOcorrencia.HORARIO -> if (minutos == 0 || minutos !in -180..720) throw ErroDeCompra(400, "Informe quantos minutos o horário mudou (diferente de zero).")
        }
        return Banco.conexao().use { c ->
            val existe = c.prepareStatement("select 1 from viagem where id = ?").use { it.setInt(1, viagemId); it.executeQuery().use { rs -> rs.next() } }
            if (!existe) throw ErroDeCompra(404, "Viagem não encontrada.")
            val cancelada = tipo == TipoOcorrencia.CANCELADA
            c.prepareStatement(
                "insert into viagem_estado (viagem_id, atraso_min, cancelada, atualizada_em) values (?,?,?,?) " +
                    "on conflict(viagem_id) do update set atraso_min = excluded.atraso_min, cancelada = excluded.cancelada, atualizada_em = excluded.atualizada_em",
            ).use { it.setInt(1, viagemId); it.setInt(2, if (cancelada) 0 else minutos); it.setInt(3, if (cancelada) 1 else 0); it.setLong(4, Contas.agora()); it.executeUpdate() }

            val cidades = Banco.terminais().associate { it.id to it.cidade }
            val cidade = { id: String -> cidades[id] ?: id }
            val ids = afetados(c, viagemId)
            var reembolsados = 0
            for (pedidoId in ids) {
                val dono = c.prepareStatement("select usuario_id from pedido where id = ?").use { it.setInt(1, pedidoId); it.executeQuery().use { rs -> rs.next(); rs.getInt(1) } }
                val ped = Compras.pedido(dono, pedidoId) ?: continue
                val nome = nomeDoTrecho(ped, viagemId, cidade)
                val trecho = ped.trechos.first { it.viagemId == viagemId }
                when (tipo) {
                    TipoOcorrencia.CANCELADA -> {
                        Compras.cancelarPelaEmpresa(c, dono, pedidoId)
                        reembolsados++
                        Notificacoes.criar(c, dono, TipoNotificacao.VIAGEM_CANCELADA, "Viagem cancelada · $nome",
                            "A ${trecho.empresa} cancelou este trecho. A compra ${ped.codigo} foi cancelada e o reembolso de ${Notificacoes.reais(ped.totais.totalCentavos)} (100%, sem taxa) sai em até 72 horas.", pedidoId)
                    }
                    TipoOcorrencia.ATRASO -> {
                        Notificacoes.criar(c, dono, TipoNotificacao.ATRASO, "Ônibus atrasado · $nome",
                            "Seu ônibus da ${trecho.empresa} está $minutos min atrasado. Nova saída: ${horaBr(trecho.saidaEm + minutos * 60L)}, chegada: ${horaBr(trecho.chegadaEm + minutos * 60L)}.", pedidoId)
                        avisarConexao(c, dono, ped, viagemId, minutos, nome)
                    }
                    else -> {
                        Notificacoes.criar(c, dono, TipoNotificacao.HORARIO, "Horário alterado · $nome",
                            "O horário passou de ${horaBr(trecho.saidaEm)} para ${horaBr(trecho.saidaEm + minutos * 60L)}.", pedidoId)
                        avisarConexao(c, dono, ped, viagemId, minutos, nome)
                    }
                }
            }
            OcorrenciaResultado(ids.size, reembolsados)
        }
    }

    /** US20: se a mudança deixa a folga até o próximo trecho abaixo do mínimo (ou apertada), a pessoa é avisada com a folga e o plano B. */
    private fun avisarConexao(c: Connection, dono: Int, ped: Pedido, viagemId: Int, atrasoMin: Int, nome: String) {
        val i = ped.trechos.indexOfFirst { it.viagemId == viagemId }
        val prox = ped.trechos.getOrNull(i + 1) ?: return
        val a = ped.trechos[i]
        val folgaMin = ((prox.saidaReal - (a.chegadaEm + atrasoMin * 60L)) / 60).toInt()
        val params = Banco.parametros()
        if (folgaMin >= params.apertadaMin) return
        val nivel = if (folgaMin < params.minimaMin) "abaixo do mínimo" else "apertada"
        Notificacoes.criar(c, dono, TipoNotificacao.CONEXAO_RISCO, "Conexão em risco · $nome",
            "Com a mudança, você chega ${horaBr(a.chegadaEm + atrasoMin * 60L)} e o próximo ônibus sai ${horaBr(prox.saidaReal)}. Folga de $folgaMin min, $nivel (recomendado: ${params.apertadaMin}). Veja o plano B da rota.", ped.id)
    }

    /** D5: a viagem em andamento, ou a próxima. Cada trecho vem com a etapa calculada pelo relógio do servidor. */
    fun ativas(usuarioId: Int): List<ViagemAtiva> {
        val t = Contas.agora()
        return Compras.pedidos(usuarioId, null).filter { it.status == StatusPedido.PAGO && it.trechos.maxOf { x -> x.chegadaReal } > t }
            .sortedBy { it.saidaEm }.take(3).map { p ->
                ViagemAtiva(p, EtapaDoTrecho.de(p, t), t)
            }
    }
}
