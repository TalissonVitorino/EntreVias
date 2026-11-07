package br.com.talisson.entrevias.domain

/**
 * Regras de entrada compartilhadas: o app valida para avisar cedo, o servidor valida de novo (nunca confia no app).
 * Cada função devolve a mensagem de erro, ou `null` se estiver tudo certo.
 */
object Validacao {
    const val SENHA_MIN = 8
    const val SENHA_MAX = 128
    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$")

    fun normalizarEmail(e: String) = e.trim().lowercase()
    fun soDigitos(s: String) = s.filter { it in '0'..'9' }

    fun nome(n: String): String? {
        val t = n.trim()
        return if (t.length < 2 || t.length > 100) "Informe seu nome (de 2 a 100 letras)." else null
    }

    fun email(e: String): String? {
        val t = normalizarEmail(e)
        return if (t.length > 254 || !EMAIL.matches(t)) "Informe um e-mail válido." else null
    }

    /** Celular é opcional: vazio vale; se preenchido, 10 ou 11 dígitos (DDD + número). */
    fun celular(c: String?): String? {
        if (c.isNullOrBlank()) return null
        return if (soDigitos(c).length in 10..11) null else "Celular com DDD, por exemplo (41) 99999-0000."
    }

    fun senha(s: String): String? = when {
        s.length < SENHA_MIN -> "A senha precisa ter ao menos $SENHA_MIN caracteres."
        s.length > SENHA_MAX -> "A senha pode ter no máximo $SENHA_MAX caracteres."
        else -> null
    }

    const val CODIGO_TAMANHO = 6

    /** Código de verificação: exatamente 6 dígitos. */
    fun codigo(c: String): String? =
        if (c.length == CODIGO_TAMANHO && c.all { it in '0'..'9' }) null else "Informe o código de $CODIGO_TAMANHO dígitos."

    fun cadastro(p: CadastroPedido): String? =
        nome(p.nome) ?: email(p.email) ?: celular(p.celular) ?: senha(p.senha)
            ?: if (!p.aceitaTermos) "Para criar a conta, aceite os Termos de uso e a Política de privacidade." else null

    fun perfil(p: PerfilPedido): String? = nome(p.nome) ?: celular(p.celular)

    fun empresa(p: EmpresaPedido): String? {
        val n = p.nome.trim()
        if (n.length < 2 || n.length > 80) return "Informe o nome da empresa (de 2 a 80 letras)."
        val s = p.sigla?.trim().orEmpty()
        if (s.isNotEmpty() && s.length !in 2..4) return "A sigla deve ter de 2 a 4 letras."
        val c = p.cnpj?.takeIf { it.isNotBlank() }
        if (c != null && !cnpjValido(c)) return "CNPJ inválido."
        return null
    }

    /** CNPJ com os dois dígitos verificadores conferidos. Aceita pontuação. */
    fun cnpjValido(bruto: String): Boolean {
        val d = soDigitos(bruto)
        if (d.length != 14 || d.all { it == d[0] }) return false
        fun dv(base: String, pesos: IntArray): Int {
            val soma = base.indices.sumOf { (base[it] - '0') * pesos[it] }
            val r = soma % 11
            return if (r < 2) 0 else 11 - r
        }
        val p1 = intArrayOf(5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2)
        val p2 = intArrayOf(6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2)
        val d1 = dv(d.substring(0, 12), p1)
        val d2 = dv(d.substring(0, 12) + d1, p2)
        return d[12] - '0' == d1 && d[13] - '0' == d2
    }
}
