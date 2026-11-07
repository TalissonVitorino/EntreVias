package br.com.talisson.entrevias.domain

import kotlinx.serialization.Serializable

const val PAPEL_VIAJANTE = "viajante"
const val PAPEL_GESTOR = "gestor"

/** US8, US11: pedido de cadastro. `aceitaTermos` nunca vem pré-marcado (RQ11). */
@Serializable
data class CadastroPedido(
    val nome: String,
    val email: String,
    val celular: String? = null,
    val senha: String,
    val aceitaTermos: Boolean = false,
)

@Serializable
data class LoginPedido(val email: String, val senha: String)

/** O que o servidor devolve sobre a pessoa. Nunca inclui senha nem hash. */
@Serializable
data class Usuario(
    val id: Int,
    val nome: String,
    val email: String,
    val celular: String? = null,
    val papel: String = PAPEL_VIAJANTE,
    /** RQ10: o e-mail foi confirmado com um código recebido nele. Campo novo, por isso com padrão. */
    val emailVerificado: Boolean = false,
) {
    val ehGestor get() = papel == PAPEL_GESTOR
}

@Serializable
data class SessaoResposta(val token: String, val usuario: Usuario)

/** US9: o que a pessoa pode editar depois do cadastro. */
@Serializable
data class PerfilPedido(val nome: String, val celular: String? = null)

/** US7: empresa parceira. `linhas` é só leitura (quantas linhas ela opera). */
@Serializable
data class Empresa(
    val id: Int,
    val nome: String,
    val sigla: String? = null,
    val cnpj: String? = null,
    val ativa: Boolean = true,
    val linhas: Int = 0,
)

@Serializable
data class EmpresaPedido(val nome: String, val sigla: String? = null, val cnpj: String? = null, val ativa: Boolean = true)

/** Corpo de toda resposta de erro: texto genérico, em português, sem detalhe interno. */
@Serializable
data class ErroApi(val mensagem: String)

/** RQ9: pede o código de recuperação. A resposta é a mesma exista a conta ou não. */
@Serializable
data class EsqueciSenhaPedido(val email: String)

/** RQ9: troca a senha com o código recebido por e-mail. */
@Serializable
data class RedefinirSenhaPedido(val email: String, val codigo: String, val novaSenha: String)

/** RQ10: confirma o e-mail com o código recebido. */
@Serializable
data class CodigoPedido(val codigo: String)

/** Resposta de sucesso que só carrega um texto para a pessoa. */
@Serializable
data class AvisoApi(val mensagem: String)

/**
 * Como o servidor envia e-mail. `simulado = true` significa que NENHUM e-mail real sai (ver docs/SIMULADOS.md);
 * `disponivel = false` significa que os fluxos que dependem do código estão desligados (simulado em produção).
 */
@Serializable
data class EstadoEmail(val simulado: Boolean, val disponivel: Boolean)
