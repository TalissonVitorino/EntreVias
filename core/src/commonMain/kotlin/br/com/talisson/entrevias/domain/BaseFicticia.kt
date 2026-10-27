package br.com.talisson.entrevias.domain

/** Mesma base fictícia do protótipo e do banco de teste. Empresas, preços e horários são inventados. */
object BaseFicticia {
    val terminais = listOf(
        Terminal("SP", "São Paulo", "SP", "Terminal Rodoviário do Tietê"),
        Terminal("CWB", "Curitiba", "PR", "Terminal Rodoviário de Curitiba"),
        Terminal("JVL", "Joinville", "SC", "Terminal Rodoviário de Joinville"),
        Terminal("FLN", "Florianópolis", "SC", "Terminal Rodoviário Rita Maria"),
        Terminal("RJ", "Rio de Janeiro", "RJ", "Rodoviária Novo Rio"),
        Terminal("BH", "Belo Horizonte", "MG", "Rodoviária de Belo Horizonte"),
        Terminal("BSB", "Brasília", "DF", "Rodoviária do Plano Piloto"),
        Terminal("VIT", "Vitória", "ES", "Rodoviária de Vitória"),
        Terminal("CAM", "Campinas", "SP", "Terminal Rodoviário de Campinas"),
        Terminal("GYN", "Goiânia", "GO", "Rodoviária de Goiânia"),
        Terminal("SSA", "Salvador", "BA", "Rodoviária de Salvador"),
        Terminal("POA", "Porto Alegre", "RS", "Rodoviária de Porto Alegre"),
    )
    private const val DIA = 1440
    private fun h(hhmm: String, dia: Int = 0): Int {
        val (hh, mm) = hhmm.split(":").map { it.toInt() }
        return dia * DIA + hh * 60 + mm
    }
    private const val HS = "Viação Horizonte Sul"
    private const val SA = "Viação Serra Azul"
    private const val EC = "Expresso Costa"
    private const val AC = "Ar-condicionado"
    private const val BA = "Banheiro"
    private const val US = "Tomada USB"

    /** As 7 viagens do protótipo (ids 1 a 7), que os testes T01 a T03 usam. */
    private val doPrototipo = listOf(
        Viagem(1, HS, "Convencional", "SP", "CWB", h("18:00"), h("00:30", 1), 9990, comodidades = listOf(AC)),
        Viagem(2, SA, "Convencional", "CWB", "JVL", h("01:30", 1), h("03:45", 1), 4990, comodidades = listOf(AC)),
        Viagem(3, EC, "Convencional", "JVL", "FLN", h("04:30", 1), h("07:30", 1), 3990, comodidades = listOf(AC)),
        Viagem(4, HS, "Executivo", "SP", "CWB", h("19:00"), h("01:30", 1), 11990, comodidades = listOf(AC, BA)),
        Viagem(5, EC, "Executivo", "CWB", "FLN", h("03:00", 1), h("07:00", 1), 8990, comodidades = listOf(AC, BA)),
        Viagem(6, HS, "Semi-leito", "SP", "FLN", h("21:00"), h("07:00", 1), 21990, comodidades = listOf(AC, BA, US)),
        Viagem(7, SA, "Leito", "SP", "FLN", h("22:00"), h("07:30", 1), 32990, comodidades = listOf(AC, BA, US)),
    )

    /** Corredor: origem, destino, duração em horas, preço base em centavos (valores inventados). */
    private data class Corredor(val a: String, val b: String, val horas: Int, val precoBase: Int)

    private val corredores = listOf(
        Corredor("SP", "RJ", 6, 11990), Corredor("SP", "CAM", 2, 3990), Corredor("SP", "BH", 8, 13990),
        Corredor("RJ", "BH", 7, 11990), Corredor("RJ", "VIT", 7, 12990), Corredor("BH", "VIT", 8, 11990),
        Corredor("BH", "BSB", 10, 18990), Corredor("BH", "SSA", 21, 31990), Corredor("SP", "GYN", 9, 16990),
        Corredor("GYN", "BSB", 4, 6990), Corredor("SP", "BSB", 14, 21990), Corredor("VIT", "SSA", 15, 23990),
        Corredor("FLN", "POA", 7, 13990),
    )
    private val saidas = listOf("06:30", "14:00", "22:30")
    private val categorias = listOf("Convencional" to 100, "Executivo" to 125, "Leito" to 180)
    private val empresas = listOf(HS, SA, EC)

    /** Gera, para cada corredor, 3 saídas por dia em dois dias, nos dois sentidos. */
    private val geradas: List<Viagem> = buildList {
        var id = 100
        corredores.forEachIndexed { ci, c ->
            for ((o, d) in listOf(c.a to c.b, c.b to c.a)) {
                for (dia in 0..1) saidas.forEachIndexed { si, hs ->
                    val (cat, pct) = categorias[si]
                    val saida = h(hs, dia)
                    val am = when (cat) { "Convencional" -> listOf(AC); "Executivo" -> listOf(AC, BA); else -> listOf(AC, BA, US) }
                    add(Viagem(id++, empresas[(ci + si) % 3], cat, o, d, saida, saida + c.horas * 60, c.precoBase * pct / 100, comodidades = am))
                }
            }
        }
    }

    val viagens: List<Viagem> = doPrototipo + geradas

    /** Viagens que se repetem todos os dias (para o servidor gerar o calendário). */
    fun padroesDiarios(): List<Viagem> = viagens.distinctBy {
        listOf(it.empresa, it.categoria, it.origem, it.destino, it.saidaMin % 1440, it.chegadaMin - it.saidaMin, it.precoCentavos)
    }
}
