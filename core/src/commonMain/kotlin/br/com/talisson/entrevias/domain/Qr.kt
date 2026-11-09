package br.com.talisson.entrevias.domain

/**
 * Gerador de QR Code (ISO/IEC 18004) em Kotlin puro, para rodar igual em Android, iOS, desktop e web.
 * Só o que o app precisa: modo de bytes (UTF-8), correção de erro M, versões 1 a 20 (até 666 bytes).
 * O código "copia e cola" do Pix cabe com folga. Conferido contra o gerador `qrcode` do Python e lido pelo OpenCV
 * (ver QrTest).
 */
class Qr private constructor(val versao: Int, private val modulos: Array<BooleanArray>) {
    val tamanho get() = modulos.size

    /** `true` = módulo escuro. Fora da grade = falso (a borda branca é desenhada por quem exibe). */
    fun escuro(x: Int, y: Int) = x in 0 until tamanho && y in 0 until tamanho && modulos[y][x]

    companion object {
        const val VERSAO_MAXIMA = 20

        // Códigos de correção por bloco e número de blocos, nível M, versões 1..20 (índice 0 = versão 1).
        private val ECC_POR_BLOCO = intArrayOf(10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26)
        private val BLOCOS = intArrayOf(1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16)

        /** Quantos bytes cabem na versão [v] (modo bytes, nível M). */
        fun capacidade(v: Int): Int {
            val dados = palavrasDeDados(v)
            val cabecalho = if (v < 10) 12 else 20       // 4 bits de modo + contador
            return (dados * 8 - cabecalho) / 8
        }

        fun cabe(texto: String) = texto.encodeToByteArray().size <= capacidade(VERSAO_MAXIMA)

        private fun modulosBrutos(v: Int): Int {
            var r = (16 * v + 128) * v + 64
            if (v >= 2) { val n = v / 7 + 2; r -= (25 * n - 10) * n - 55; if (v >= 7) r -= 36 }
            return r
        }

        private fun palavrasDeDados(v: Int) = modulosBrutos(v) / 8 - ECC_POR_BLOCO[v - 1] * BLOCOS[v - 1]

        /** Gera o QR do [texto]; [mascara] (0 a 7) força a máscara, só para testes. Lança [IllegalArgumentException] se passar de [capacidade]([VERSAO_MAXIMA]) bytes. */
        fun gerar(texto: String, mascara: Int? = null): Qr {
            val bytes = texto.encodeToByteArray()
            val v = (1..VERSAO_MAXIMA).firstOrNull { bytes.size <= capacidade(it) }
                ?: throw IllegalArgumentException("Texto grande demais para o QR (${bytes.size} bytes).")

            // --- bits de dados
            val bits = ArrayList<Int>()
            fun add(valor: Int, n: Int) { for (i in n - 1 downTo 0) bits.add((valor ushr i) and 1) }
            add(0b0100, 4); add(bytes.size, if (v < 10) 8 else 16)
            for (b in bytes) add(b.toInt() and 0xFF, 8)
            val total = palavrasDeDados(v) * 8
            add(0, minOf(4, total - bits.size))
            while (bits.size % 8 != 0) bits.add(0)
            var pad = 0xEC
            while (bits.size < total) { add(pad, 8); pad = pad xor (0xEC xor 0x11) }
            val dados = IntArray(bits.size / 8) { i -> (0 until 8).fold(0) { a, j -> (a shl 1) or bits[i * 8 + j] } }

            val codigos = intercalar(v, dados)
            val tam = v * 4 + 17
            val m = Array(tam) { BooleanArray(tam) }
            val funcao = Array(tam) { BooleanArray(tam) }
            desenharFuncoes(v, m, funcao)
            posicionar(codigos, m, funcao)

            // escolhe a máscara de menor penalidade
            var melhor = mascara ?: 0; var menor = Int.MAX_VALUE
            if (mascara == null) for (mask in 0..7) {
                aplicarMascara(mask, m, funcao); desenharFormato(mask, m, funcao)
                val p = penalidade(m)
                if (p < menor) { menor = p; melhor = mask }
                aplicarMascara(mask, m, funcao)   // desfaz (XOR)
            }
            aplicarMascara(melhor, m, funcao); desenharFormato(melhor, m, funcao)
            return Qr(v, m)
        }

        // ---------- Reed-Solomon

        private val EXP = IntArray(512).also { e ->
            var x = 1
            for (i in 0 until 255) { e[i] = x; x = x shl 1; if (x >= 256) x = x xor 0x11D }
            for (i in 255 until 512) e[i] = e[i - 255]
        }
        private val LOG = IntArray(256).also { l -> for (i in 0 until 255) l[EXP[i]] = i }
        private fun mul(a: Int, b: Int) = if (a == 0 || b == 0) 0 else EXP[LOG[a] + LOG[b]]

        private fun gerador(grau: Int): IntArray {
            var r = intArrayOf(1)
            for (i in 0 until grau) {
                val n = IntArray(r.size + 1)
                for (j in r.indices) { n[j] = n[j] xor mul(r[j], 1); n[j + 1] = n[j + 1] xor mul(r[j], EXP[i]) }
                r = n
            }
            return r   // coeficientes do mais alto ao mais baixo (o primeiro é 1)
        }

        private fun resto(dados: IntArray, grau: Int): IntArray {
            val g = gerador(grau)
            val r = IntArray(grau)
            for (b in dados) {
                val fator = b xor r[0]
                for (i in 0 until grau - 1) r[i] = r[i + 1]
                r[grau - 1] = 0
                for (i in 0 until grau) r[i] = r[i] xor mul(g[i + 1], fator)
            }
            return r
        }

        private fun intercalar(v: Int, dados: IntArray): IntArray {
            val nb = BLOCOS[v - 1]; val ecc = ECC_POR_BLOCO[v - 1]
            val brutas = modulosBrutos(v) / 8
            val curtos = nb - brutas % nb; val tamCurto = brutas / nb
            val blocos = ArrayList<IntArray>(); val eccs = ArrayList<IntArray>()
            var k = 0
            for (i in 0 until nb) {
                val n = tamCurto - ecc + (if (i < curtos) 0 else 1)
                val d = dados.copyOfRange(k, k + n); k += n
                blocos.add(d); eccs.add(resto(d, ecc))
            }
            val out = ArrayList<Int>()
            for (i in 0..tamCurto - ecc) for (j in 0 until nb) if (i < blocos[j].size) out.add(blocos[j][i])
            for (i in 0 until ecc) for (j in 0 until nb) out.add(eccs[j][i])
            return out.toIntArray()
        }

        // ---------- desenho

        private fun set(m: Array<BooleanArray>, f: Array<BooleanArray>, x: Int, y: Int, escuro: Boolean) {
            if (x in m.indices && y in m.indices) { m[y][x] = escuro; f[y][x] = true }
        }

        private fun posicoesDeAlinhamento(v: Int): List<Int> {
            if (v == 1) return emptyList()
            val n = v / 7 + 2
            val passo = if (v == 32) 26 else (v * 4 + n * 2 + 1) / (n * 2 - 2) * 2
            val r = ArrayList<Int>(); r.add(6)
            var pos = v * 4 + 10
            while (r.size < n) { r.add(1, pos); pos -= passo }
            return r
        }

        private fun desenharFuncoes(v: Int, m: Array<BooleanArray>, f: Array<BooleanArray>) {
            val tam = m.size
            for (i in 0 until tam) { set(m, f, 6, i, i % 2 == 0); set(m, f, i, 6, i % 2 == 0) }
            fun localizador(cx: Int, cy: Int) {
                for (dy in -4..4) for (dx in -4..4) {
                    val d = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                    set(m, f, cx + dx, cy + dy, d != 2 && d != 4)
                }
            }
            localizador(3, 3); localizador(tam - 4, 3); localizador(3, tam - 4)
            val pos = posicoesDeAlinhamento(v)
            for (i in pos.indices) for (j in pos.indices) {
                if ((i == 0 && j == 0) || (i == 0 && j == pos.size - 1) || (i == pos.size - 1 && j == 0)) continue
                for (dy in -2..2) for (dx in -2..2) set(m, f, pos[i] + dx, pos[j] + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
            }
            // reserva o formato e a versão (o formato real vem depois da máscara)
            desenharFormato(0, m, f)
            if (v >= 7) {
                var r = v
                repeat(12) { r = (r shl 1) xor ((r ushr 11) * 0x1F25) }
                val bitsV = (v shl 12) or r
                for (i in 0 until 18) {
                    val b = ((bitsV ushr i) and 1) != 0
                    val a = tam - 11 + i % 3; val c = i / 3
                    set(m, f, a, c, b); set(m, f, c, a, b)
                }
            }
        }

        private fun desenharFormato(mask: Int, m: Array<BooleanArray>, f: Array<BooleanArray>) {
            val tam = m.size
            val dados = (0 shl 3) or mask           // nível M = 00
            var r = dados
            repeat(10) { r = (r shl 1) xor ((r ushr 9) * 0x537) }
            val bits = ((dados shl 10) or r) xor 0x5412
            fun bit(i: Int) = ((bits ushr i) and 1) != 0
            for (i in 0..5) set(m, f, 8, i, bit(i))
            set(m, f, 8, 7, bit(6)); set(m, f, 8, 8, bit(7)); set(m, f, 7, 8, bit(8))
            for (i in 9..14) set(m, f, 14 - i, 8, bit(i))
            for (i in 0..7) set(m, f, tam - 1 - i, 8, bit(i))
            for (i in 8..14) set(m, f, 8, tam - 15 + i, bit(i))
            set(m, f, 8, tam - 8, true)
        }

        private fun posicionar(codigos: IntArray, m: Array<BooleanArray>, f: Array<BooleanArray>) {
            val tam = m.size
            var i = 0
            var direita = tam - 1
            while (direita >= 1) {
                if (direita == 6) direita = 5
                for (vert in 0 until tam) for (j in 0..1) {
                    val x = direita - j
                    val sobe = ((direita + 1) and 2) == 0
                    val y = if (sobe) tam - 1 - vert else vert
                    if (!f[y][x] && i < codigos.size * 8) { m[y][x] = ((codigos[i ushr 3] ushr (7 - (i and 7))) and 1) != 0; i++ }
                }
                direita -= 2
            }
        }

        private fun aplicarMascara(mask: Int, m: Array<BooleanArray>, f: Array<BooleanArray>) {
            for (y in m.indices) for (x in m.indices) {
                if (f[y][x]) continue
                val inverte = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                if (inverte) m[y][x] = !m[y][x]
            }
        }

        private fun penalidade(m: Array<BooleanArray>): Int {
            val n = m.size
            var p = 0
            // regra 1: sequências de 5+ módulos iguais (linhas e colunas)
            for (eixo in 0..1) for (a in 0 until n) {
                var corrida = 1
                for (b in 1 until n) {
                    val atual = if (eixo == 0) m[a][b] else m[b][a]
                    val anterior = if (eixo == 0) m[a][b - 1] else m[b - 1][a]
                    if (atual == anterior) { corrida++; if (corrida == 5) p += 3 else if (corrida > 5) p++ } else corrida = 1
                }
            }
            // regra 2: blocos 2x2
            for (y in 0 until n - 1) for (x in 0 until n - 1) { val c = m[y][x]; if (c == m[y][x + 1] && c == m[y + 1][x] && c == m[y + 1][x + 1]) p += 3 }
            // regra 3: padrão parecido com o localizador (1:1:3:1:1) com 4 claros de um lado
            val padrao = booleanArrayOf(true, false, true, true, true, false, true)
            for (eixo in 0..1) for (a in 0 until n) for (b in 0..n - 7) {
                fun v(i: Int) = if (eixo == 0) m[a][b + i] else m[b + i][a]
                if ((0..6).all { v(it) == padrao[it] }) {
                    fun claro(i: Int) = i !in 0 until n || !(if (eixo == 0) m[a][i] else m[i][a])
                    if ((1..4).all { claro(b - it) }) p += 40
                    if ((7..10).all { claro(b + it) }) p += 40
                }
            }
            // regra 4: equilíbrio entre claros e escuros
            val escuros = m.sumOf { l -> l.count { it } }
            val total = n * n
            val k = (kotlin.math.abs(escuros * 20 - total * 10) + total - 1) / total - 1
            p += k * 10
            return p
        }
    }
}
