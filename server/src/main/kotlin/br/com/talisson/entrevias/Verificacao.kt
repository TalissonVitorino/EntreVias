package br.com.talisson.entrevias

import br.com.talisson.entrevias.domain.EstadoEmail
import br.com.talisson.entrevias.domain.Usuario
import br.com.talisson.entrevias.domain.Validacao
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Um e-mail a ser enviado. O texto contém o código: nunca vai para log. */
interface EnvioDeEmail {
    fun enviar(para: String, assunto: String, texto: String)
}

/**
 * SIMULADO (ver docs/SIMULADOS.md). Não envia nada: guarda as últimas mensagens na memória para a equipe ler em
 * desenvolvimento. Trocar por um envio real é implementar [EnvioDeEmail] e atribuir a [Email.envio].
 */
class EnvioSimulado : EnvioDeEmail {
    data class Mensagem(val para: String, val assunto: String, val texto: String, val emEpoch: Long)

    private val caixa = ArrayDeque<Mensagem>()

    @Synchronized
    override fun enviar(para: String, assunto: String, texto: String) {
        caixa.addLast(Mensagem(para, assunto, texto, Contas.agora()))
        if (caixa.size > 50) caixa.removeFirst()
    }

    @Synchronized fun mensagens(): List<Mensagem> = caixa.toList()
    @Synchronized fun limpar() = caixa.clear()
}

/**
 * Ponto único de troca do envio de e-mail.
 * Em produção (`ENV=production`) com o envio simulado ninguém receberia o código, então os fluxos que dependem dele
 * (recuperar senha, confirmar e-mail) ficam DESLIGADOS (503), em vez de fingir que funcionam.
 */
object Email {
    var emProducao = System.getenv("ENV") == "production"
    var envio: EnvioDeEmail = EnvioSimulado()

    /** A caixa de saída de desenvolvimento só abre com `CAIXA_DE_SAIDA_DEV=true`: ela mostra códigos de qualquer pessoa. */
    var caixaDeSaidaAberta = System.getenv("CAIXA_DE_SAIDA_DEV") == "true"

    val simulado get() = envio is EnvioSimulado
    val disponivel get() = !(simulado && emProducao)
    fun estado() = EstadoEmail(simulado, disponivel)
}

/**
 * Códigos de 6 dígitos (RQ9, RQ10). Só o hash fica no banco, vale 15 min, e erra-se no máximo 5 vezes: seis dígitos
 * seriam fáceis de adivinhar sem esse teto. Um código novo substitui o anterior da mesma pessoa e finalidade.
 */
object Codigos {
    const val VALIDADE_SEG = 15 * 60L
    const val MAX_ERROS = 5
    const val EMAIL = "email"
    const val SENHA = "senha"

    private val aleatorio = SecureRandom()

    private fun hash(finalidade: String, usuarioId: Int, codigo: String): String =
        Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest("$finalidade:$usuarioId:$codigo".toByteArray()))

    @Synchronized
    fun emitir(usuarioId: Int, finalidade: String): String {
        val codigo = "%06d".format(aleatorio.nextInt(1_000_000))
        Banco.conexao().use { c ->
            c.prepareStatement(
                "insert into codigo_verificacao (usuario_id, finalidade, codigo_hash, expira_em, tentativas) values (?,?,?,?,0) " +
                    "on conflict(usuario_id, finalidade) do update set codigo_hash = excluded.codigo_hash, expira_em = excluded.expira_em, tentativas = 0",
            ).use { it.setInt(1, usuarioId); it.setString(2, finalidade); it.setString(3, hash(finalidade, usuarioId, codigo)); it.setLong(4, Contas.agora() + VALIDADE_SEG); it.executeUpdate() }
        }
        return codigo
    }

    /** `true` consome o código. Errar conta tentativa; no teto, ou vencido, o código é apagado. */
    @Synchronized
    fun conferir(usuarioId: Int, finalidade: String, codigo: String): Boolean = Banco.conexao().use { c ->
        val linha = c.prepareStatement("select codigo_hash, expira_em, tentativas from codigo_verificacao where usuario_id = ? and finalidade = ?").use {
            it.setInt(1, usuarioId); it.setString(2, finalidade)
            it.executeQuery().use { rs -> if (rs.next()) Triple(rs.getString(1), rs.getLong(2), rs.getInt(3)) else null }
        } ?: return@use false
        fun apagar() = c.prepareStatement("delete from codigo_verificacao where usuario_id = ? and finalidade = ?").use { it.setInt(1, usuarioId); it.setString(2, finalidade); it.executeUpdate() }
        if (linha.second <= Contas.agora()) { apagar(); return@use false }
        if (MessageDigest.isEqual(hash(finalidade, usuarioId, codigo).toByteArray(), linha.first.toByteArray())) { apagar(); return@use true }
        if (linha.third + 1 >= MAX_ERROS) apagar()
        else c.prepareStatement("update codigo_verificacao set tentativas = tentativas + 1 where usuario_id = ? and finalidade = ?").use { it.setInt(1, usuarioId); it.setString(2, finalidade); it.executeUpdate() }
        false
    }
}

/** RQ9 (recuperar senha) e RQ10 (confirmar e-mail). Nada aqui escreve e-mail, código ou senha em log. */
object Recuperacao {
    private fun idDoEmail(email: String): Int? = Banco.conexao().use { c ->
        c.prepareStatement("select id from usuario where email = ? and excluido_em is null").use { it.setString(1, email); it.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else null } }
    }

    /** Sempre volta sem dizer se a conta existe. Só envia se existir e o limite por e-mail permitir. */
    fun pedirCodigoDeSenha(emailBruto: String) {
        val email = Validacao.normalizarEmail(emailBruto)
        if (Limites.recuperacaoPorEmail.tentar(email) != null) return
        val id = idDoEmail(email) ?: return
        Email.envio.enviar(
            email, "Recuperar senha do EntreVias",
            "Seu código para redefinir a senha é ${Codigos.emitir(id, Codigos.SENHA)}. Ele vale por 15 minutos. Se não foi você, ignore este e-mail.",
        )
    }

    /** Sucesso troca a senha, encerra TODAS as sessões e zera o bloqueio de login; receber o código prova o e-mail. */
    fun redefinirSenha(emailBruto: String, codigo: String, novaSenha: String): Boolean {
        val email = Validacao.normalizarEmail(emailBruto)
        val id = idDoEmail(email) ?: return false
        if (!Codigos.conferir(id, Codigos.SENHA, codigo)) return false
        val novoHash = Contas.hashSenha(novaSenha)
        Banco.conexao().use { c ->
            c.prepareStatement("update usuario set senha_hash = ?, email_verificado_em = coalesce(email_verificado_em, ?) where id = ?").use { it.setString(1, novoHash); it.setLong(2, Contas.agora()); it.setInt(3, id); it.executeUpdate() }
            c.prepareStatement("delete from sessao where usuario_id = ?").use { it.setInt(1, id); it.executeUpdate() }
            c.prepareStatement("delete from codigo_verificacao where usuario_id = ?").use { it.setInt(1, id); it.executeUpdate() }
            c.prepareStatement("delete from tentativa_login where chave = ?").use { it.setString(1, Contas.chaveDeTentativa(email)); it.executeUpdate() }
        }
        return true
    }

    /** `false` = limite por pessoa estourado (3 por hora). */
    fun enviarVerificacao(u: Usuario): Boolean {
        if (Limites.verificacaoPorUsuario.tentar(u.id.toString()) != null) return false
        Email.envio.enviar(
            u.email, "Confirme seu e-mail no EntreVias",
            "Seu código para confirmar o e-mail é ${Codigos.emitir(u.id, Codigos.EMAIL)}. Ele vale por 15 minutos.",
        )
        return true
    }

    fun confirmarEmail(id: Int, codigo: String): Boolean {
        if (!Codigos.conferir(id, Codigos.EMAIL, codigo)) return false
        Banco.conexao().use { c ->
            c.prepareStatement("update usuario set email_verificado_em = coalesce(email_verificado_em, ?) where id = ?").use { it.setLong(1, Contas.agora()); it.setInt(2, id); it.executeUpdate() }
        }
        return true
    }
}
