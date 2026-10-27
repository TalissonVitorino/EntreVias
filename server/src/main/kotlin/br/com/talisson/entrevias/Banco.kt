package br.com.talisson.entrevias

import br.com.talisson.entrevias.domain.BaseFicticia
import br.com.talisson.entrevias.domain.ParametrosConexao
import br.com.talisson.entrevias.domain.Terminal
import br.com.talisson.entrevias.domain.Viagem
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** SQLite local. Tudo que o app mostra vem daqui. Dados de empresas, preços e horários são FICTÍCIOS. */
object Banco {
    private val ZONA = ZoneOffset.ofHours(-3)
    private val INICIO: Long = ZonedDateTime.of(2026, 11, 20, 0, 0, 0, 0, ZONA).toEpochSecond()
    private val COTACAO: Long = ZonedDateTime.of(2026, 10, 1, 10, 0, 0, 0, ZONA).toEpochSecond()
    private const val DIAS_DE_OFERTA = 150
    private const val VERSAO_DADOS = 2   // sobe quando a regra de preços muda: o banco é refeito

    /** Preço fictício do dia: fim de semana, festas e uma variação própria de cada saída. */
    private fun precoDoDia(base: Int, data: LocalDate, saidaMin: Int, chave: Int): Int {
        var f = 1.0
        if (data.dayOfWeek == java.time.DayOfWeek.FRIDAY || data.dayOfWeek == java.time.DayOfWeek.SUNDAY) f += 0.14
        if (data.dayOfWeek == java.time.DayOfWeek.SATURDAY) f += 0.06
        val dm = data.monthValue * 100 + data.dayOfMonth
        if (dm >= 1220 || dm <= 103) f += 0.30                 // Natal e Ano Novo
        if (data.year == 2027 && dm in 205..210) f += 0.30     // Carnaval
        val h = saidaMin / 60
        if (h in 5..8) f += 0.04 else if (h >= 22 || h < 4) f -= 0.03
        val jitter = ((data.toEpochDay() * 31 + chave * 17) % 13).toInt() - 6   // -6..+6 %
        f += jitter / 100.0
        val centavos = (base * f).toInt()
        return ((centavos + 50) / 100) * 100 - 10               // sempre termina em ,90
    }

    private val url = "jdbc:sqlite:" + (System.getProperty("entrevias.db") ?: "entrevias.db")

    fun conexao(): Connection = DriverManager.getConnection(url).also { it.createStatement().use { s -> s.execute("PRAGMA foreign_keys = ON") } }

    fun iniciar() {
        conexao().use { c ->
            val existe = c.createStatement().use { s -> s.executeQuery("select count(*) from sqlite_master where name = 'viagem'").use { it.next(); it.getInt(1) > 0 } }
            if (!existe) criarEsquema(c)
            else {
                val versao = c.createStatement().use { s -> s.executeQuery("select valor from parametro where chave = 'versao_dados'").use { if (it.next()) it.getInt(1) else 0 } }
                if (versao < VERSAO_DADOS) {
                    listOf("clique_compra", "roteiro_trecho", "preco_historico", "alerta", "viagem", "linha", "empresa", "terminal", "cidade", "parametro")
                        .forEach { t -> c.createStatement().use { s -> s.execute("delete from $t") } }
                }
            }
            val vazio = c.createStatement().use { s -> s.executeQuery("select count(*) from viagem").use { it.next(); it.getInt(1) == 0 } }
            if (vazio) semear(c)
        }
    }

    private fun criarEsquema(c: Connection) {
        val sql = Banco::class.java.getResourceAsStream("/schema.sql")!!.bufferedReader().readText()
        sql.lines().map { it.substringBefore("--") }.joinToString("\n").split(";")
            .map { it.trim() }.filter { it.isNotEmpty() }
            .forEach { cmd -> c.createStatement().use { it.execute(cmd) } }
    }

    private fun semear(c: Connection) {
        c.autoCommit = false
        val terminais = BaseFicticia.terminais
        val cidadeId = terminais.map { it.cidade to it.uf }.distinct().withIndex().associate { (i, p) -> p to i + 1 }
        c.prepareStatement("insert into cidade (id, nome, uf) values (?,?,?)").use { st ->
            cidadeId.forEach { (p, id) -> st.setInt(1, id); st.setString(2, p.first); st.setString(3, p.second); st.executeUpdate() }
        }
        val terminalId = terminais.withIndex().associate { (i, t) -> t.id to i + 1 }
        c.prepareStatement("insert into terminal (id, cidade_id, nome, codigo) values (?,?,?,?)").use { st ->
            terminais.forEach { t -> st.setInt(1, terminalId[t.id]!!); st.setInt(2, cidadeId[t.cidade to t.uf]!!); st.setString(3, t.nome); st.setString(4, t.id); st.executeUpdate() }
        }
        val padroes = BaseFicticia.padroesDiarios()
        val empresaId = padroes.map { it.empresa }.distinct().withIndex().associate { (i, n) -> n to i + 1 }
        c.prepareStatement("insert into empresa (id, nome) values (?,?)").use { st ->
            empresaId.forEach { (n, id) -> st.setInt(1, id); st.setString(2, n); st.executeUpdate() }
        }
        val chaves = padroes.map { listOf(it.empresa, it.origem, it.destino, it.categoria) }.distinct()
        val linhaId = chaves.withIndex().associate { (i, k) -> k to i + 1 }
        c.prepareStatement("insert into linha (id, empresa_id, origem_terminal_id, destino_terminal_id, categoria) values (?,?,?,?,?)").use { st ->
            linhaId.forEach { (k, id) ->
                st.setInt(1, id); st.setInt(2, empresaId[k[0]]!!); st.setInt(3, terminalId[k[1]]!!); st.setInt(4, terminalId[k[2]]!!); st.setString(5, k[3]); st.executeUpdate()
            }
        }
        c.prepareStatement("insert into viagem (linha_id, saida_em, chegada_em, preco_centavos, preco_atualizado_em) values (?,?,?,?,?)").use { st ->
            val dataBase = LocalDate.of(2026, 11, 20)
            for (dia in 0 until DIAS_DE_OFERTA) for ((pi, p) in padroes.withIndex()) {
                val saida = INICIO + dia * 86400L + (p.saidaMin % 1440) * 60L
                st.setInt(1, linhaId[listOf(p.empresa, p.origem, p.destino, p.categoria)]!!)
                st.setLong(2, saida); st.setLong(3, saida + (p.chegadaMin - p.saidaMin) * 60L)
                st.setInt(4, precoDoDia(p.precoCentavos, dataBase.plusDays(dia.toLong()), p.saidaMin % 1440, pi)); st.setLong(5, COTACAO); st.executeUpdate()
            }
        }
        c.prepareStatement("insert into parametro (chave, valor) values (?,?)").use { st ->
            listOf("conexao_minima_min" to 30, "conexao_apertada_min" to 60, "conexao_maxima_min" to 480, "versao_dados" to VERSAO_DADOS).forEach { (k, v) -> st.setString(1, k); st.setInt(2, v); st.executeUpdate() }
        }
        c.commit(); c.autoCommit = true
    }

    fun terminais(): List<Terminal> = conexao().use { c ->
        c.createStatement().use { s ->
            s.executeQuery("select t.codigo, ci.nome, ci.uf, t.nome from terminal t join cidade ci on ci.id = t.cidade_id order by ci.nome").use { rs ->
                buildList { while (rs.next()) add(Terminal(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))) }
            }
        }
    }

    fun parametros(): ParametrosConexao = conexao().use { c ->
        val m = c.createStatement().use { s -> s.executeQuery("select chave, valor from parametro").use { rs -> buildMap { while (rs.next()) put(rs.getString(1), rs.getInt(2)) } } }
        ParametrosConexao(m["conexao_minima_min"] ?: 30, m["conexao_apertada_min"] ?: 60, m["conexao_maxima_min"] ?: 480)
    }

    private fun comodidades(cat: String) = when (cat) { "Convencional" -> listOf("Ar-condicionado"); "Executivo" -> listOf("Ar-condicionado", "Banheiro"); else -> listOf("Ar-condicionado", "Banheiro", "Tomada USB") }

    /** Viagens que saem a partir da meia-noite de [data], por [dias] dias. Horários em minutos desde essa meia-noite. */
    fun viagens(data: LocalDate, dias: Int): List<Viagem> {
        val inicio = data.atStartOfDay(ZONA).toEpochSecond()
        return conexao().use { c ->
            c.prepareStatement(
                """select v.id, e.nome, l.categoria, o.codigo, d.codigo, v.saida_em, v.chegada_em, v.preco_centavos, v.preco_atualizado_em
                   from viagem v join linha l on l.id = v.linha_id join empresa e on e.id = l.empresa_id
                   join terminal o on o.id = l.origem_terminal_id join terminal d on d.id = l.destino_terminal_id
                   where v.saida_em >= ? and v.saida_em < ? order by v.saida_em"""
            ).use { st ->
                st.setLong(1, inicio); st.setLong(2, inicio + dias * 86400L)
                st.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(Viagem(
                            id = rs.getInt(1), empresa = rs.getString(2), categoria = rs.getString(3),
                            origem = rs.getString(4), destino = rs.getString(5),
                            saidaMin = ((rs.getLong(6) - inicio) / 60).toInt(), chegadaMin = ((rs.getLong(7) - inicio) / 60).toInt(),
                            precoCentavos = rs.getInt(8), atualizadoEmMin = ((rs.getLong(9) - inicio) / 60).toInt(),
                            comodidades = comodidades(rs.getString(3)),
                        ))
                    }
                }
            }
        }
    }
}
