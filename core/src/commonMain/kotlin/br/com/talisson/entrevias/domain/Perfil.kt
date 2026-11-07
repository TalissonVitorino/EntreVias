package br.com.talisson.entrevias.domain

import kotlinx.serialization.Serializable

/**
 * A5 (US10, RQ3). Do cartão guardamos só bandeira, 4 últimos dígitos e validade: o número completo e o CVV
 * NUNCA saem do aparelho (LGPD). Numa integração real isso vira token do processador de pagamento.
 */
@Serializable
data class FormaPagamento(
    val id: Int,
    val tipo: String,                   // TIPO_CARTAO | TIPO_PIX
    val rotulo: String,                 // "Visa •••• 4242" ou "Pix CPF final ••• 321"
    val bandeira: String? = null,
    val ultimos4: String? = null,
    val validade: String? = null,       // MM/AA
    val padrao: Boolean = false,
)

@Serializable
data class FormaPagamentoPedido(
    val tipo: String,
    val bandeira: String? = null,
    val ultimos4: String? = null,
    val validade: String? = null,
    val pixTipo: String? = null,        // "CPF" | "E-mail" | "Celular" | "Aleatória"
    val pixFinal: String? = null,       // só o final da chave (até 4 caracteres)
    val padrao: Boolean = false,
)

const val TIPO_CARTAO = "cartao"
const val TIPO_PIX = "pix"

/** A6 (RQ7, RQ8, RQ13, RQ41): quem viaja com a pessoa. A categoria sai da idade, nunca é digitada. */
@Serializable
data class Passageiro(
    val id: Int,
    val nome: String,
    val relacao: String,                // "Você" | "Dependente" | "Acompanhante"
    val idade: Int,
    val categoria: String,              // Categoria.* calculada pela idade
    val documentoFinal: String? = null, // 3 últimos dígitos, só para conferir no embarque
    val acessibilidade: String? = null, // texto livre curto: "Cadeira de rodas", etc.
) {
    val ocupaPoltrona get() = categoria != CategoriaPassageiro.COLO
    val gratuidade get() = categoria == CategoriaPassageiro.IDOSO || categoria == CategoriaPassageiro.COLO
}

@Serializable
data class PassageiroPedido(
    val nome: String,
    val relacao: String = "Dependente",
    val idade: Int,
    val documentoFinal: String? = null,
    val acessibilidade: String? = null,
)

object CategoriaPassageiro {
    const val COLO = "colo"          // menor de 2 anos, sem poltrona
    const val CRIANCA = "crianca"    // 2 a 11
    const val ADULTO = "adulto"      // 12 a 59
    const val IDOSO = "idoso"        // 60+, gratuidade mediante comprovação

    fun daIdade(idade: Int) = when { idade < 2 -> COLO; idade < 12 -> CRIANCA; idade < 60 -> ADULTO; else -> IDOSO }
    fun rotulo(c: String) = when (c) { COLO -> "colo · sem poltrona"; CRIANCA -> "criança"; IDOSO -> "idoso (60+) · gratuidade com comprovação"; else -> "adulto" }
}

object ValidacaoPerfil {
    val RELACOES = listOf("Você", "Dependente", "Acompanhante")
    val BANDEIRAS = listOf("Visa", "Mastercard", "Elo", "Amex", "Outro")
    val PIX_TIPOS = listOf("CPF", "E-mail", "Celular", "Aleatória")
    const val MAX_FORMAS = 5
    const val MAX_PASSAGEIROS = 10

    /** Soma de Luhn: pega erro de digitação no número do cartão, no aparelho, antes de qualquer envio. */
    fun luhn(numero: String): Boolean {
        val d = numero.filter { it in '0'..'9' }
        if (d.length !in 13..19) return false
        var soma = 0
        d.reversed().forEachIndexed { i, ch -> var n = ch - '0'; if (i % 2 == 1) { n *= 2; if (n > 9) n -= 9 }; soma += n }
        return soma % 10 == 0
    }

    fun bandeiraDe(numero: String): String {
        val d = numero.filter { it in '0'..'9' }
        return when {
            d.startsWith("4") -> "Visa"
            d.take(2).toIntOrNull() in 51..55 || d.take(4).toIntOrNull() in 2221..2720 -> "Mastercard"
            d.take(2) == "34" || d.take(2) == "37" -> "Amex"
            d.take(4) in listOf("6362", "5067", "4389", "4011") -> "Elo"
            else -> "Outro"
        }
    }

    fun validade(v: String?): String? {
        val m = Regex("^(\\d{2})/(\\d{2})$").matchEntire(v?.trim().orEmpty()) ?: return "Validade no formato MM/AA."
        return if (m.groupValues[1].toInt() in 1..12) null else "Mês da validade inválido."
    }

    /** A validade vence no último dia do mês. `anoAtual` e `mesAtual` vêm de fora para o teste não depender do relógio. */
    fun vencido(v: String, anoAtual: Int, mesAtual: Int): Boolean {
        val mm = v.substring(0, 2).toInt(); val aa = 2000 + v.substring(3, 5).toInt()
        return aa < anoAtual || (aa == anoAtual && mm < mesAtual)
    }

    fun forma(p: FormaPagamentoPedido): String? = when (p.tipo) {
        TIPO_CARTAO -> when {
            p.bandeira !in BANDEIRAS -> "Bandeira inválida."
            p.ultimos4?.length != 4 || p.ultimos4.any { it !in '0'..'9' } -> "Informe os 4 últimos dígitos do cartão."
            else -> validade(p.validade)
        }
        TIPO_PIX -> when {
            p.pixTipo !in PIX_TIPOS -> "Escolha o tipo da chave Pix."
            p.pixFinal.isNullOrBlank() || p.pixFinal.length > 4 -> "Informe o final da chave (até 4 caracteres)."
            else -> null
        }
        else -> "Tipo de pagamento inválido."
    }

    fun passageiro(p: PassageiroPedido): String? = when {
        p.nome.trim().length !in 2..100 -> "Informe o nome do passageiro (de 2 a 100 letras)."
        p.relacao !in RELACOES -> "Relação inválida."
        p.idade !in 0..120 -> "Idade inválida."
        p.documentoFinal != null && p.documentoFinal.isNotEmpty() && (p.documentoFinal.length != 3 || p.documentoFinal.any { it !in '0'..'9' }) -> "Documento: informe só os 3 últimos dígitos."
        (p.acessibilidade?.length ?: 0) > 80 -> "Acessibilidade: no máximo 80 caracteres."
        else -> null
    }
}
