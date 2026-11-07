package br.com.talisson.entrevias

import br.com.talisson.entrevias.domain.CadastroPedido
import br.com.talisson.entrevias.domain.Empresa
import br.com.talisson.entrevias.domain.EmpresaPedido
import br.com.talisson.entrevias.domain.PAPEL_GESTOR
import br.com.talisson.entrevias.domain.PAPEL_VIAJANTE
import br.com.talisson.entrevias.domain.PerfilPedido
import br.com.talisson.entrevias.domain.SessaoResposta
import br.com.talisson.entrevias.domain.Usuario
import br.com.talisson.entrevias.domain.Validacao
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

sealed interface ResultadoEntrada {
    data class Ok(val sessao: SessaoResposta) : ResultadoEntrada
    data object Invalido : ResultadoEntrada
    data class Bloqueado(val ateEpoch: Long) : ResultadoEntrada
}

sealed interface ResultadoCadastro {
    data class Ok(val sessao: SessaoResposta) : ResultadoCadastro
    data object Duplicado : ResultadoCadastro
}

sealed interface ResultadoEmpresa {
    data class Ok(val empresa: Empresa) : ResultadoEmpresa
    data object NaoEncontrada : ResultadoEmpresa
    data object Duplicada : ResultadoEmpresa
}

/**
 * US7, US8, US9, US11. Senha com PBKDF2 + sal (RNF05); sessão por token aleatório de que só o hash fica no banco;
 * bloqueio de 15 min após 3 erros de senha (RQ12), por hash do e-mail, igual para conta que existe ou não.
 * Nada aqui escreve e-mail, senha ou token em log.
 */
object Contas {
    const val MAX_FALHAS = 3
    const val BLOQUEIO_SEG = 15 * 60L
    const val SESSAO_SEG = 30L * 24 * 3600

    /** Relógio injetável (segundos desde 1970) para testar bloqueio e expiração sem esperar. */
    var agora: () -> Long = { Instant.now().epochSecond }

    /** Iterações do PBKDF2 em hashes NOVOS; o número usado fica dentro do hash, então mudar não invalida os antigos. */
    var iteracoes = 600_000

    private val aleatorio = SecureRandom()
    private fun b64(b: ByteArray) = Base64.getEncoder().withoutPadding().encodeToString(b)
    private fun sha256(s: String) = b64(MessageDigest.getInstance("SHA-256").digest(s.toByteArray()))

    private fun derivar(senha: String, sal: ByteArray, it: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(senha.toCharArray(), sal, it, 256)).encoded

    fun hashSenha(senha: String): String {
        val sal = ByteArray(16).also { aleatorio.nextBytes(it) }
        return "pbkdf2_sha256$$iteracoes$${b64(sal)}$${b64(derivar(senha, sal, iteracoes))}"
    }

    fun confere(senha: String, hash: String): Boolean {
        val p = hash.split('$')
        if (p.size != 4 || p[0] != "pbkdf2_sha256") return false
        val it = p[1].toIntOrNull() ?: return false
        val sal = runCatching { Base64.getDecoder().decode(p[2]) }.getOrNull() ?: return false
        val esperado = runCatching { Base64.getDecoder().decode(p[3]) }.getOrNull() ?: return false
        return MessageDigest.isEqual(derivar(senha, sal, it), esperado)
    }

    /** Hash de uma senha qualquer, para gastar o mesmo tempo quando o e-mail não existe. */
    private val hashFalso by lazy { hashSenha("senha-que-ninguem-tem") }

    private fun usuarioDe(rs: java.sql.ResultSet) =
        Usuario(rs.getInt("id"), rs.getString("nome") ?: "", rs.getString("email"), rs.getString("celular"), rs.getString("papel"))

    private fun novaSessao(c: Connection, u: Usuario): SessaoResposta {
        val token = ByteArray(32).also { aleatorio.nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val t = agora()
        c.prepareStatement("insert into sessao (token_hash, usuario_id, criada_em, expira_em) values (?,?,?,?)").use {
            it.setString(1, sha256(token)); it.setInt(2, u.id); it.setLong(3, t); it.setLong(4, t + SESSAO_SEG); it.executeUpdate()
        }
        return SessaoResposta(token, u)
    }

    fun cadastrar(p: CadastroPedido, papel: String = PAPEL_VIAJANTE): ResultadoCadastro {
        val email = Validacao.normalizarEmail(p.email)
        val hash = hashSenha(p.senha)   // fora da conexão: é a parte lenta
        return Banco.conexao().use { c ->
            try {
                c.prepareStatement(
                    "insert into usuario (email, senha_hash, nome, celular, papel, criado_em, aceitou_termos_em) values (?,?,?,?,?,?,?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS,
                ).use { st ->
                    val t = agora()
                    st.setString(1, email); st.setString(2, hash); st.setString(3, p.nome.trim())
                    st.setString(4, p.celular?.takeIf { it.isNotBlank() }?.let(Validacao::soDigitos))
                    st.setString(5, papel); st.setLong(6, t); st.setLong(7, t); st.executeUpdate()
                    val id = st.generatedKeys.use { it.next(); it.getInt(1) }
                    ResultadoCadastro.Ok(novaSessao(c, buscar(c, id)!!))
                }
            } catch (e: SQLException) {
                if (e.message?.contains("UNIQUE", ignoreCase = true) == true) ResultadoCadastro.Duplicado else throw e
            }
        }
    }

    private fun buscar(c: Connection, id: Int): Usuario? =
        c.prepareStatement("select id, nome, email, celular, papel from usuario where id = ? and excluido_em is null").use {
            it.setInt(1, id); it.executeQuery().use { rs -> if (rs.next()) usuarioDe(rs) else null }
        }

    fun entrar(emailBruto: String, senha: String): ResultadoEntrada {
        val email = Validacao.normalizarEmail(emailBruto)
        val chave = sha256(email)
        return Banco.conexao().use { c ->
            val t = agora()
            val (falhas, bloqueadoAte) = c.prepareStatement("select falhas, bloqueado_ate from tentativa_login where chave = ?").use {
                it.setString(1, chave); it.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) to rs.getLong(2) else 0 to 0L }
            }
            if (bloqueadoAte > t) return@use ResultadoEntrada.Bloqueado(bloqueadoAte)

            val linha = c.prepareStatement("select id, senha_hash from usuario where email = ? and excluido_em is null").use {
                it.setString(1, email); it.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) to rs.getString(2) else null }
            }
            val ok = confere(senha, linha?.second ?: hashFalso) && linha != null
            if (!ok) {
                val novas = if (bloqueadoAte in 1..t) 1 else falhas + 1   // bloqueio vencido recomeça a contagem
                val bloquear = novas >= MAX_FALHAS
                c.prepareStatement(
                    "insert into tentativa_login (chave, falhas, bloqueado_ate) values (?,?,?) " +
                        "on conflict(chave) do update set falhas = excluded.falhas, bloqueado_ate = excluded.bloqueado_ate",
                ).use {
                    it.setString(1, chave); it.setInt(2, if (bloquear) 0 else novas)
                    if (bloquear) it.setLong(3, t + BLOQUEIO_SEG) else it.setNull(3, java.sql.Types.INTEGER)
                    it.executeUpdate()
                }
                return@use if (bloquear) ResultadoEntrada.Bloqueado(t + BLOQUEIO_SEG) else ResultadoEntrada.Invalido
            }
            c.prepareStatement("delete from tentativa_login where chave = ?").use { it.setString(1, chave); it.executeUpdate() }
            ResultadoEntrada.Ok(novaSessao(c, buscar(c, linha!!.first)!!))
        }
    }

    fun usuarioDoToken(token: String?): Usuario? {
        if (token.isNullOrBlank() || token.length > 128) return null
        return Banco.conexao().use { c ->
            c.prepareStatement(
                "select u.id, u.nome, u.email, u.celular, u.papel from sessao s join usuario u on u.id = s.usuario_id " +
                    "where s.token_hash = ? and s.expira_em > ? and u.excluido_em is null",
            ).use { it.setString(1, sha256(token)); it.setLong(2, agora()); it.executeQuery().use { rs -> if (rs.next()) usuarioDe(rs) else null } }
        }
    }

    fun sair(token: String?) {
        if (token.isNullOrBlank()) return
        Banco.conexao().use { c -> c.prepareStatement("delete from sessao where token_hash = ?").use { it.setString(1, sha256(token)); it.executeUpdate() } }
    }

    fun atualizarPerfil(id: Int, p: PerfilPedido): Usuario? = Banco.conexao().use { c ->
        c.prepareStatement("update usuario set nome = ?, celular = ? where id = ? and excluido_em is null").use {
            it.setString(1, p.nome.trim()); it.setString(2, p.celular?.takeIf { s -> s.isNotBlank() }?.let(Validacao::soDigitos)); it.setInt(3, id)
            it.executeUpdate()
        }
        buscar(c, id)
    }

    /**
     * Cria ou promove o gestor a partir de variáveis de ambiente. Sem as duas variáveis, não cria ninguém.
     * Se o e-mail já tinha conta (alguém pode ter se cadastrado antes com ele, pois o cadastro não confirma o e-mail),
     * a senha passa a ser a das variáveis e todas as sessões dessa conta são encerradas: quem manda é o operador.
     */
    fun garantirGestor(email: String?, senha: String?) {
        if (email.isNullOrBlank() || senha.isNullOrBlank()) return
        if (Validacao.email(email) != null || Validacao.senha(senha) != null) return
        val e = Validacao.normalizarEmail(email)
        val existe = Banco.conexao().use { c ->
            c.prepareStatement("select count(*) from usuario where email = ?").use { it.setString(1, e); it.executeQuery().use { rs -> rs.next(); rs.getInt(1) > 0 } }
        }
        if (existe) {
            val hash = hashSenha(senha)
            Banco.conexao().use { c ->
                c.prepareStatement("delete from sessao where usuario_id in (select id from usuario where email = ?)").use { it.setString(1, e); it.executeUpdate() }
                c.prepareStatement("update usuario set papel = ?, senha_hash = ?, excluido_em = null where email = ?").use {
                    it.setString(1, PAPEL_GESTOR); it.setString(2, hash); it.setString(3, e); it.executeUpdate()
                }
            }
        } else cadastrar(CadastroPedido("Gestor", e, senha = senha, aceitaTermos = true), PAPEL_GESTOR)
    }

    // ---------- US7: empresas parceiras ----------

    private const val SQL_EMPRESA =
        "select e.id, e.nome, e.sigla, e.cnpj, e.ativa, (select count(*) from linha l where l.empresa_id = e.id) from empresa e"

    private fun empresaDe(rs: java.sql.ResultSet) =
        Empresa(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5) == 1, rs.getInt(6))

    fun empresas(): List<Empresa> = Banco.conexao().use { c ->
        c.createStatement().use { s -> s.executeQuery("$SQL_EMPRESA order by e.nome").use { rs -> buildList { while (rs.next()) add(empresaDe(rs)) } } }
    }

    private fun empresa(c: Connection, id: Int): Empresa? =
        c.prepareStatement("$SQL_EMPRESA where e.id = ?").use { it.setInt(1, id); it.executeQuery().use { rs -> if (rs.next()) empresaDe(rs) else null } }

    private fun grava(st: java.sql.PreparedStatement, p: EmpresaPedido) {
        st.setString(1, p.nome.trim())
        st.setString(2, p.sigla?.trim()?.uppercase()?.takeIf { it.isNotEmpty() })
        st.setString(3, p.cnpj?.takeIf { it.isNotBlank() }?.let(Validacao::soDigitos))
        st.setInt(4, if (p.ativa) 1 else 0)
    }

    private fun duplicada(e: SQLException) = e.message?.contains("UNIQUE", ignoreCase = true) == true

    fun criarEmpresa(p: EmpresaPedido): ResultadoEmpresa = Banco.conexao().use { c ->
        try {
            c.prepareStatement("insert into empresa (nome, sigla, cnpj, ativa) values (?,?,?,?)", java.sql.Statement.RETURN_GENERATED_KEYS).use { st ->
                grava(st, p); st.executeUpdate()
                ResultadoEmpresa.Ok(empresa(c, st.generatedKeys.use { it.next(); it.getInt(1) })!!)
            }
        } catch (e: SQLException) { if (duplicada(e)) ResultadoEmpresa.Duplicada else throw e }
    }

    fun atualizarEmpresa(id: Int, p: EmpresaPedido): ResultadoEmpresa = Banco.conexao().use { c ->
        try {
            val n = c.prepareStatement("update empresa set nome = ?, sigla = ?, cnpj = ?, ativa = ? where id = ?").use { st -> grava(st, p); st.setInt(5, id); st.executeUpdate() }
            if (n == 0) ResultadoEmpresa.NaoEncontrada else ResultadoEmpresa.Ok(empresa(c, id)!!)
        } catch (e: SQLException) { if (duplicada(e)) ResultadoEmpresa.Duplicada else throw e }
    }
}
