package br.com.talisson.entrevias

import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class TempoTest {
    private fun epoch(a: Int, m: Int, d: Int, h: Int, min: Int) = LocalDateTime.of(a, m, d, h, min).toEpochSecond(ZoneOffset.ofHours(-3))

    @Test fun dataEHoraDeBrasiliaBatemComAJvm() {
        assertEquals(Data(2026, 11, 20), Tempo.data(epoch(2026, 11, 20, 6, 30))); assertEquals("06:30", Tempo.hora(epoch(2026, 11, 20, 6, 30)))
        assertEquals(Data(2026, 11, 20), Tempo.data(epoch(2026, 11, 20, 23, 59))); assertEquals(Data(2026, 11, 21), Tempo.data(epoch(2026, 11, 21, 0, 0)))
    }

    @Test fun viradasEAnoBissexto() {
        assertEquals(Data(2024, 2, 29), Tempo.data(epoch(2024, 2, 29, 12, 0))); assertEquals(Data(2025, 3, 1), Tempo.data(epoch(2025, 3, 1, 0, 1)))
        assertEquals(Data(2027, 1, 1), Tempo.data(epoch(2027, 1, 1, 0, 0))); assertEquals(Data(2026, 12, 31), Tempo.data(epoch(2026, 12, 31, 23, 59)))
        assertEquals(Data(1999, 12, 31), Tempo.data(epoch(1999, 12, 31, 18, 0)))
    }

    @Test fun variosDiasSeguidosBatemTodos() {
        var d = java.time.LocalDate.of(2026, 1, 1)
        repeat(800) { assertEquals(Data(d.year, d.monthValue, d.dayOfMonth), Tempo.data(epoch(d.year, d.monthValue, d.dayOfMonth, 15, 0)), "$d"); d = d.plusDays(1) }
    }

    @Test fun dataHoraFormata() = assertEquals("20/11 · 06:30", Tempo.dataHora(epoch(2026, 11, 20, 6, 30)))
    @Test fun contagemNuncaFicaNegativa() { assertEquals("09:48", Tempo.contagem(588)); assertEquals("00:00", Tempo.contagem(-5)); assertEquals("15:00", Tempo.contagem(900)) }

    @Test fun diasEntreDatasBateComAJvm() {
        var d = java.time.LocalDate.of(2024, 12, 20)
        repeat(900) { i ->
            val x = java.time.LocalDate.of(2024, 12, 20).plusDays(i.toLong())
            assertEquals(i, Tempo.diasEntre(Data(2024, 12, 20), Data(x.year, x.monthValue, x.dayOfMonth)), "$x"); d = x
        }
        assertEquals(-3, Tempo.diasEntre(Data(2026, 11, 20), Data(2026, 11, 17)), "para trás também"); assertEquals(0, Tempo.diasEntre(Data(2026, 11, 20), Data(2026, 11, 20)))
        assertEquals(366, Tempo.diasEntre(Data(2024, 2, 1), Data(2025, 2, 1)), "passa por 29/02/2024"); assertEquals(365, Tempo.diasEntre(Data(2024, 3, 1), Data(2025, 3, 1)), "esse já não passa")
    }

    @Test fun diasEEpochSaoInversos() {
        for (dia in listOf(Data(1970, 1, 1), Data(2000, 2, 29), Data(2026, 11, 20), Data(2100, 3, 1))) {
            val epochMeioDia = Tempo.dias(dia) * 86_400L + 15 * 3600   // 12:00 em Brasília = 15:00 UTC
            assertEquals(dia, Tempo.data(epochMeioDia))
        }
    }
}
