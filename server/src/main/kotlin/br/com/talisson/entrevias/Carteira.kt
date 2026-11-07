package br.com.talisson.entrevias

import br.com.talisson.entrevias.domain.CategoriaPassageiro
import br.com.talisson.entrevias.domain.FormaPagamento
import br.com.talisson.entrevias.domain.FormaPagamentoPedido
import br.com.talisson.entrevias.domain.Passageiro
import br.com.talisson.entrevias.domain.PassageiroPedido
import br.com.talisson.entrevias.domain.TIPO_CARTAO
import br.com.talisson.entrevias.domain.TIPO_PIX
import br.com.talisson.entrevias.domain.ValidacaoPerfil
import java.sql.Connection

sealed interface ResultadoCarteira<out T> {
    data class Ok<T>(val valor: T) : ResultadoCarteira<T>
    data object NaoEncontrado : ResultadoCarteira<Nothing>
    data object LimiteAtingido : ResultadoCarteira<Nothing>
}

/**
 * A5 (formas de pagamento) e A6 (passageiros). Tudo é da pessoa logada: toda consulta leva o `usuario_id` da SESSÃO,
 * então o id de outra pessoa dá "não encontrado", nunca o dado dela. Nada de número de cartão ou documento completo.
 */
object Carteira {
    private fun formaDe(rs: java.sql.ResultSet) =
        FormaPagamento(rs.getInt("id"), rs.getString("tipo"), rs.getString("rotulo"), rs.getString("bandeira"), rs.getString("ultimos4"), rs.getString("validade"), rs.getInt("padrao") == 1)

    fun formas(usuarioId: Int): List<FormaPagamento> = Banco.conexao().use { c ->
        c.prepareStatement("select * from forma_pagamento where usuario_id = ? order by padrao desc, id").use {
            it.setInt(1, usuarioId); it.executeQuery().use { rs -> buildList { while (rs.next()) add(formaDe(rs)) } }
        }
    }

    private fun rotulo(p: FormaPagamentoPedido) =
        if (p.tipo == TIPO_CARTAO) "${p.bandeira} •••• ${p.ultimos4}" else "Pix ${p.pixTipo} final ••• ${p.pixFinal}"

    fun adicionarForma(usuarioId: Int, p: FormaPagamentoPedido): ResultadoCarteira<FormaPagamento> = Banco.conexao().use { c ->
        val total = c.prepareStatement("select count(*) from forma_pagamento where usuario_id = ?").use { it.setInt(1, usuarioId); it.executeQuery().use { rs -> rs.next(); rs.getInt(1) } }
        if (total >= ValidacaoPerfil.MAX_FORMAS) return@use ResultadoCarteira.LimiteAtingido
        val padrao = p.padrao || total == 0   // a primeira forma é sempre a padrão
        if (padrao) c.prepareStatement("update forma_pagamento set padrao = 0 where usuario_id = ?").use { it.setInt(1, usuarioId); it.executeUpdate() }
        val id = c.prepareStatement(
            "insert into forma_pagamento (usuario_id, tipo, rotulo, bandeira, ultimos4, validade, padrao, criado_em) values (?,?,?,?,?,?,?,?)",
            java.sql.Statement.RETURN_GENERATED_KEYS,
        ).use {
            it.setInt(1, usuarioId); it.setString(2, p.tipo); it.setString(3, rotulo(p))
            it.setString(4, p.bandeira.takeIf { p.tipo == TIPO_CARTAO }); it.setString(5, p.ultimos4.takeIf { p.tipo == TIPO_CARTAO }); it.setString(6, p.validade?.takeIf { p.tipo == TIPO_CARTAO })
            it.setInt(7, if (padrao) 1 else 0); it.setLong(8, Contas.agora()); it.executeUpdate()
            it.generatedKeys.use { rs -> rs.next(); rs.getInt(1) }
        }
        ResultadoCarteira.Ok(formas(usuarioId).first { it.id == id })
    }

    fun tornarPadrao(usuarioId: Int, id: Int): ResultadoCarteira<List<FormaPagamento>> = Banco.conexao().use { c ->
        if (!existe(c, "forma_pagamento", usuarioId, id)) return@use ResultadoCarteira.NaoEncontrado
        c.prepareStatement("update forma_pagamento set padrao = (id = ?) where usuario_id = ?").use { it.setInt(1, id); it.setInt(2, usuarioId); it.executeUpdate() }
        ResultadoCarteira.Ok(formas(usuarioId))
    }

    /** Remover a forma padrão promove a mais antiga que sobrou, para nunca ficar uma lista sem padrão. */
    fun removerForma(usuarioId: Int, id: Int): ResultadoCarteira<List<FormaPagamento>> = Banco.conexao().use { c ->
        val eraPadrao: Boolean = c.prepareStatement("select padrao from forma_pagamento where id = ? and usuario_id = ?").use {
            it.setInt(1, id); it.setInt(2, usuarioId); it.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) == 1 else null }
        } ?: return@use ResultadoCarteira.NaoEncontrado
        c.prepareStatement("delete from forma_pagamento where id = ? and usuario_id = ?").use { it.setInt(1, id); it.setInt(2, usuarioId); it.executeUpdate() }
        if (eraPadrao) c.prepareStatement("update forma_pagamento set padrao = 1 where id = (select min(id) from forma_pagamento where usuario_id = ?)").use { it.setInt(1, usuarioId); it.executeUpdate() }
        ResultadoCarteira.Ok(formas(usuarioId))
    }

    private fun existe(c: Connection, tabela: String, usuarioId: Int, id: Int) =
        c.prepareStatement("select 1 from $tabela where id = ? and usuario_id = ?").use { it.setInt(1, id); it.setInt(2, usuarioId); it.executeQuery().use { rs -> rs.next() } }

    // ---------- A6: passageiros ----------

    private fun passageiroDe(rs: java.sql.ResultSet) =
        Passageiro(rs.getInt("id"), rs.getString("nome"), rs.getString("relacao"), rs.getInt("idade"), CategoriaPassageiro.daIdade(rs.getInt("idade")), rs.getString("documento_final"), rs.getString("acessibilidade"))

    fun passageiros(usuarioId: Int): List<Passageiro> = Banco.conexao().use { c ->
        c.prepareStatement("select * from passageiro where usuario_id = ? order by case relacao when 'Você' then 0 else 1 end, id").use {
            it.setInt(1, usuarioId); it.executeQuery().use { rs -> buildList { while (rs.next()) add(passageiroDe(rs)) } }
        }
    }

    private fun grava(st: java.sql.PreparedStatement, p: PassageiroPedido, base: Int) {
        st.setString(base, p.nome.trim()); st.setString(base + 1, p.relacao); st.setInt(base + 2, p.idade)
        st.setString(base + 3, p.documentoFinal?.takeIf { it.isNotBlank() }); st.setString(base + 4, p.acessibilidade?.trim()?.takeIf { it.isNotEmpty() })
    }

    fun adicionarPassageiro(usuarioId: Int, p: PassageiroPedido): ResultadoCarteira<Passageiro> = Banco.conexao().use { c ->
        val total = c.prepareStatement("select count(*) from passageiro where usuario_id = ?").use { it.setInt(1, usuarioId); it.executeQuery().use { rs -> rs.next(); rs.getInt(1) } }
        if (total >= ValidacaoPerfil.MAX_PASSAGEIROS) return@use ResultadoCarteira.LimiteAtingido
        val id = c.prepareStatement("insert into passageiro (nome, relacao, idade, documento_final, acessibilidade, usuario_id, criado_em) values (?,?,?,?,?,?,?)", java.sql.Statement.RETURN_GENERATED_KEYS).use {
            grava(it, p, 1); it.setInt(6, usuarioId); it.setLong(7, Contas.agora()); it.executeUpdate(); it.generatedKeys.use { rs -> rs.next(); rs.getInt(1) }
        }
        ResultadoCarteira.Ok(passageiros(usuarioId).first { it.id == id })
    }

    fun editarPassageiro(usuarioId: Int, id: Int, p: PassageiroPedido): ResultadoCarteira<Passageiro> = Banco.conexao().use { c ->
        val n = c.prepareStatement("update passageiro set nome = ?, relacao = ?, idade = ?, documento_final = ?, acessibilidade = ? where id = ? and usuario_id = ?").use {
            grava(it, p, 1); it.setInt(6, id); it.setInt(7, usuarioId); it.executeUpdate()
        }
        if (n == 0) ResultadoCarteira.NaoEncontrado else ResultadoCarteira.Ok(passageiros(usuarioId).first { it.id == id })
    }

    fun removerPassageiro(usuarioId: Int, id: Int): ResultadoCarteira<List<Passageiro>> = Banco.conexao().use { c ->
        val n = c.prepareStatement("delete from passageiro where id = ? and usuario_id = ?").use { it.setInt(1, id); it.setInt(2, usuarioId); it.executeUpdate() }
        if (n == 0) ResultadoCarteira.NaoEncontrado else ResultadoCarteira.Ok(passageiros(usuarioId))
    }
}
