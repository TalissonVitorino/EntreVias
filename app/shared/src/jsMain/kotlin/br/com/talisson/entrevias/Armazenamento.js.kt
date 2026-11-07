package br.com.talisson.entrevias

import kotlinx.browser.localStorage

/**
 * Web: localStorage. É o armazenamento MAIS FRACO dos cinco alvos: qualquer script da mesma origem lê o token
 * (por isso o app não carrega scripts de terceiros). Se o navegador bloquear o storage, a sessão só não persiste.
 */
private class ArmazenamentoWeb : ArmazenamentoLocal {
    override fun ler(chave: String): String? = runCatching { localStorage.getItem("entrevias.$chave") }.getOrNull()
    override fun gravar(chave: String, valor: String?) {
        runCatching { if (valor == null) localStorage.removeItem("entrevias.$chave") else localStorage.setItem("entrevias.$chave", valor) }
    }
}

actual fun criarArmazenamento(): ArmazenamentoLocal = ArmazenamentoWeb()
