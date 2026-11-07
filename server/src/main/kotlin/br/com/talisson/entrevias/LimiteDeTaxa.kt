package br.com.talisson.entrevias

/**
 * Janela deslizante em memória: no máximo [max] pedidos por chave dentro de [janelaSeg] segundos.
 * O estado se perde ao reiniciar o servidor; serve para frear abuso, não como cota contratual.
 */
class LimiteDeTaxa(private val max: Int, private val janelaSeg: Long, private val agora: () -> Long = { Contas.agora() }) {
    private val registros = HashMap<String, ArrayDeque<Long>>()

    /** `null` = pode (e o pedido fica registrado); senão, quantos segundos faltam para liberar. */
    @Synchronized
    fun tentar(chave: String): Long? {
        val t = agora()
        if (registros.size > 10_000) registros.entries.removeAll { it.value.isEmpty() || it.value.last() <= t - janelaSeg }
        val fila = registros.getOrPut(chave) { ArrayDeque() }
        while (fila.isNotEmpty() && fila.first() <= t - janelaSeg) fila.removeFirst()
        if (fila.size >= max) return (fila.first() + janelaSeg - t).coerceAtLeast(1)
        fila.addLast(t)
        return null
    }
}

/**
 * Limites do cadastro (cada pedido conta, válido ou não, porque o custo está no hash da senha).
 * Por cliente: 5 por hora. Global: 300 por hora, para o caso de muitos clientes diferentes.
 * Atrás de proxy (Render), defina CONFIAR_NO_PROXY=true: o cliente passa a ser o último IP do X-Forwarded-For,
 * que é o que o proxy viu. Sem a variável, o cabeçalho é ignorado, porque qualquer um pode forjá-lo.
 */
object Limites {
    var cadastro = LimiteDeTaxa(max = 5, janelaSeg = 3600)
    var cadastroGlobal = LimiteDeTaxa(max = 300, janelaSeg = 3600)
    var confiarNoProxy = System.getenv("CONFIAR_NO_PROXY") == "true"
}
