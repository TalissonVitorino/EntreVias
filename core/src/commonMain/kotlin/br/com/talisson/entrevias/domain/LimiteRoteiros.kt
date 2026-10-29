package br.com.talisson.entrevias.domain

/** Resultado de tentar guardar um roteiro; em nenhum caso algo já guardado é apagado. */
sealed interface Salvamento<out T> {
    /** Guardado: [lista] já inclui o novo. */
    data class Salvo<T>(val lista: List<T>) : Salvamento<T>

    /** Já estava guardado (mesmo roteiro): a lista não muda. */
    data object Repetido : Salvamento<Nothing>

    /** Chegou ao limite: a lista não muda e o app oferece excluir um ou conhecer o Premium. */
    data object Cheio : Salvamento<Nothing>
}

/** RF34 / RN21 (PLANO.md, seção 3.3.9): roteiros guardados no aparelho, 10 para visitante e para conta gratuita. */
object LimiteRoteiros {
    const val GRATUITO = 10

    const val MENSAGEM_CHEIO = "Você chegou a 10 roteiros salvos. Exclua um para guardar outro."

    /** Repetido ganha de cheio: salvar de novo um roteiro que já está lá nunca é erro. */
    fun <T> salvar(guardados: List<T>, novo: T, limite: Int = GRATUITO): Salvamento<T> = when {
        novo in guardados -> Salvamento.Repetido
        guardados.size >= limite -> Salvamento.Cheio
        else -> Salvamento.Salvo(guardados + novo)
    }
}
