package br.com.talisson.entrevias

/**
 * Horário de Brasília (UTC−3, sem horário de verão) a partir de segundos desde 1970. O servidor guarda tudo em UTC e a base
 * fictícia é toda em Brasília; o app só precisa mostrar, então a conta é aqui, sem biblioteca de datas.
 */
object Tempo {
    private const val OFFSET_SEG = -3 * 3600L

    private fun local(epochSeg: Long) = epochSeg + OFFSET_SEG

    fun minutoDoDia(epochSeg: Long): Int = (local(epochSeg).mod(86_400L) / 60).toInt()

    /** Dia civil (algoritmo de Howard Hinnant, dias desde 1970 → ano/mês/dia). */
    fun data(epochSeg: Long): Data {
        val z = local(epochSeg).floorDiv(86_400L) + 719_468
        val era = z.floorDiv(146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val dia = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val mes = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val ano = (yoe + era * 400 + if (mes <= 2) 1 else 0).toInt()
        return Data(ano, mes, dia)
    }

    fun hora(epochSeg: Long) = hora(minutoDoDia(epochSeg))
    fun dataHora(epochSeg: Long) = "${data(epochSeg).br().dropLast(5)} · ${hora(epochSeg)}"

    /** "09:48": contagem regressiva em minutos e segundos (nunca negativa). */
    fun contagem(segundos: Long): String { val s = segundos.coerceAtLeast(0); return "${(s / 60).toString().padStart(2, '0')}:${(s % 60).toString().padStart(2, '0')}" }
}
