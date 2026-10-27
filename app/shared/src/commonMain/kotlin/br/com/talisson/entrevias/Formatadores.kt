package br.com.talisson.entrevias

/** 18970 -> "R$ 189,70" (dinheiro sempre guardado em centavos). */
fun centavosParaReais(c: Int): String {
    val reais = c / 100
    val cents = (c % 100).toString().padStart(2, '0')
    val milhar = reais.toString().reversed().chunked(3).joinToString(".").reversed()
    return "R$ $milhar,$cents"
}

/** Minutos desde 00:00 do dia da busca -> "18:00" ou "00:30 (+1)". */
fun minutosParaHora(m: Int): String {
    val dia = m / 1440
    val resto = m % 1440
    val hh = (resto / 60).toString().padStart(2, '0')
    val mm = (resto % 60).toString().padStart(2, '0')
    return if (dia > 0) "$hh:$mm (+$dia)" else "$hh:$mm"
}

fun duracao(min: Int): String = "${min / 60}h${(min % 60).toString().padStart(2, '0')}"

fun hora(m: Int): String {
    val r = m % 1440
    return "${(r / 60).toString().padStart(2, '0')}:${(r % 60).toString().padStart(2, '0')}"
}

/** Data civil simples (sem biblioteca externa). */
data class Data(val ano: Int, val mes: Int, val dia: Int) {
    fun iso() = "$ano-${mes.toString().padStart(2, '0')}-${dia.toString().padStart(2, '0')}"
    fun br() = "${dia.toString().padStart(2, '0')}/${mes.toString().padStart(2, '0')}/$ano"
    fun curta() = "${SEMANA[diaDaSemana()]}, $dia ${MESES[mes - 1].take(3)} $ano"
    /** 0 = domingo. */
    fun diaDaSemana(): Int {
        val t = intArrayOf(0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4)
        val y = if (mes < 3) ano - 1 else ano
        return (y + y / 4 - y / 100 + y / 400 + t[mes - 1] + dia) % 7
    }
    fun plusDias(n: Int): Data {
        var d = this
        repeat(n) { d = if (d.dia < diasNoMes(d.ano, d.mes)) d.copy(dia = d.dia + 1) else if (d.mes < 12) Data(d.ano, d.mes + 1, 1) else Data(d.ano + 1, 1, 1) }
        return d
    }
    operator fun compareTo(o: Data) = (ano * 10000 + mes * 100 + dia) - (o.ano * 10000 + o.mes * 100 + o.dia)
}

val MESES = listOf("janeiro", "fevereiro", "março", "abril", "maio", "junho", "julho", "agosto", "setembro", "outubro", "novembro", "dezembro")
val SEMANA = listOf("dom", "seg", "ter", "qua", "qui", "sex", "sáb")
fun diasNoMes(ano: Int, mes: Int): Int = when (mes) {
    2 -> if ((ano % 4 == 0 && ano % 100 != 0) || ano % 400 == 0) 29 else 28
    4, 6, 9, 11 -> 30
    else -> 31
}

/** Primeira data com oferta na demonstração (a mesma do protótipo). */
val DATA_INICIAL = Data(2026, 11, 20)

/** Data da viagem: dia escolhido mais os dias que passam (+1 = chegada no dia seguinte). */
fun dataViagem(base: Data, minutos: Int): String = base.plusDias(minutos / 1440).br()

/** 60 -> "1h", 45 -> "45min", 90 -> "1h30". */
fun esperaTxt(min: Int): String = when {
    min < 60 -> "${min}min"
    min % 60 == 0 -> "${min / 60}h"
    else -> "${min / 60}h${(min % 60).toString().padStart(2, '0')}"
}

fun passageirosTxt(n: Int) = if (n == 1) "1 passageiro" else "$n passageiros"
