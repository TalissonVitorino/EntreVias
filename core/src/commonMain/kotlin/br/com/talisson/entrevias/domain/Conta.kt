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
