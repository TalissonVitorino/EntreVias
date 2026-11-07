package br.com.talisson.entrevias

/** Guarda pequenos textos no aparelho. Cada plataforma protege como pode (ver as implementações). */
interface ArmazenamentoLocal {
    fun ler(chave: String): String?
    /** `valor == null` apaga. */
    fun gravar(chave: String, valor: String?)
}

/** Cada plataforma entrega o seu: Keystore no Android, Keychain no iOS, arquivo restrito no desktop, localStorage na web. */
expect fun criarArmazenamento(): ArmazenamentoLocal

/** Só na memória do processo: serve de padrão nos testes e quando a plataforma não oferece nada melhor. */
class ArmazenamentoEmMemoria : ArmazenamentoLocal {
    private val dados = mutableMapOf<String, String>()
    override fun ler(chave: String) = dados[chave]
    override fun gravar(chave: String, valor: String?) { if (valor == null) dados.remove(chave) else dados[chave] = valor }
}

/**
 * O token de sessão guardado no aparelho. Nunca lança: falha de armazenamento só significa "entrar de novo".
 * Só aceita o formato que o servidor emite (base64 sem símbolos), então lixo ou texto adulterado vira "sem sessão".
 */
class SessaoSalva(private val armazenamento: ArmazenamentoLocal) {
    fun token(): String? = runCatching { armazenamento.ler(CHAVE) }.getOrNull()
        ?.takeIf { it.length in 20..128 && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' } }

    fun guardar(token: String) { runCatching { armazenamento.gravar(CHAVE, token) } }
    fun apagar() { runCatching { armazenamento.gravar(CHAVE, null) } }

    private companion object { const val CHAVE = "sessao" }
}
