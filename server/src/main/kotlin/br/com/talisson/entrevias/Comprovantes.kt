package br.com.talisson.entrevias

import br.com.talisson.entrevias.domain.CategoriaPassageiro
import br.com.talisson.entrevias.domain.Comprovante
import br.com.talisson.entrevias.domain.LinhaComprovante
import br.com.talisson.entrevias.domain.Pedido
import br.com.talisson.entrevias.domain.SecaoComprovante
import br.com.talisson.entrevias.domain.StatusPedido
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64

/**
 * Comprovante da compra: o mesmo conteúdo vira tela, texto copiado e PDF. O PDF abre pelo navegador do aparelho com um link
 * curto e temporário (o navegador não leva o token da sessão), então o código de abrir arquivo é um só para as cinco plataformas.
 */
object Comprovantes {
    const val VALIDADE_SEG = 300
    private val aleatorio = SecureRandom()
    private val brasilia = ZoneOffset.ofHours(-3)
    private val formato = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
    private fun dataHora(epoch: Long) = formato.format(Instant.ofEpochSecond(epoch).atOffset(brasilia))

    fun montar(usuarioId: Int, pedidoId: Int): Comprovante {
        val p = Compras.pedido(usuarioId, pedidoId) ?: throw ErroDeCompra(404, "Pedido não encontrado.")
        if (p.status != StatusPedido.PAGO && p.status != StatusPedido.CANCELADO) throw ErroDeCompra(409, "O comprovante existe depois do pagamento.")
        return montar(p)
    }

    private fun montar(p: Pedido): Comprovante {
        val cidades = Banco.terminais().associate { it.id to it.cidade }
        fun cidade(id: String) = cidades[id] ?: id
        fun l(r: String, v: String) = LinhaComprovante(r, v)
        val secoes = mutableListOf<SecaoComprovante>()
        secoes += SecaoComprovante("Compra", listOfNotNull(
            l("Código", p.codigo),
            l("Situação", if (p.status == StatusPedido.PAGO) "Paga" else "Cancelada"),
            l("Forma de pagamento", if (p.metodo == "pix") "Pix" else if (p.metodo != null) "Cartão" else "—"),
            p.pagoEm?.let { l("Pago em", dataHora(it)) },
            p.canceladoEm?.let { l("Cancelada em", dataHora(it)) },
            l("Vendedor", "Vendedor fictício: Bilheteria de demonstração"),
        ))
        secoes += SecaoComprovante("Passageiros", p.passageiros.map {
            l(it.nome, listOfNotNull(CategoriaPassageiro.rotulo(it.categoria), "${it.idade} anos", it.documentoFinal?.let { d -> "documento final $d" }).joinToString(" · "))
        })
        for (t in p.trechos) {
            val bilhetes = p.bilhetes.filter { it.trecho == t.ordem }
            secoes += SecaoComprovante("${if (t.volta) "Volta" else "Ida"} · ${cidade(t.origem)} → ${cidade(t.destino)}", listOfNotNull(
                l("Empresa", "${t.empresa} · ${t.categoria}"),
                l("Saída", dataHora(t.saidaReal) + if (t.atrasoMin != 0) " (${if (t.atrasoMin > 0) "atrasada" else "adiantada"} ${kotlin.math.abs(t.atrasoMin)} min)" else ""),
                l("Chegada", dataHora(t.chegadaReal)),
                if (t.cancelada) l("Situação", "Cancelada pela empresa") else null,
                if (t.poltronas.isNotEmpty()) l("Poltronas", t.poltronas.joinToString(", ")) else null,
            ) + bilhetes.map { b -> l("Bilhete", "${b.codigo} · ${b.passageiro}${b.poltrona?.let { n -> " · poltrona $n" } ?: ""}") })
        }
        secoes += SecaoComprovante("Valores", listOfNotNull(
            l("Subtotal", Notificacoes.reais(p.totais.subtotalCentavos)),
            if (p.totais.descontoCategoriaCentavos > 0) l("Desconto por categoria", "- " + Notificacoes.reais(p.totais.descontoCategoriaCentavos)) else null,
            if (p.totais.cupomCentavos > 0) l("Cupom ${p.cupom.orEmpty()}".trim(), "- " + Notificacoes.reais(p.totais.cupomCentavos)) else null,
            l("Total pago", Notificacoes.reais(p.totais.totalCentavos)),
            p.reembolsoCentavos?.let { l("Reembolso", Notificacoes.reais(it)) },
        ))
        val aviso = "Comprovante demonstrativo, sem valor fiscal." + if (Psp.simulado) " Pagamento simulado: nenhum valor foi cobrado." else ""
        return Comprovante(p.codigo, secoes, aviso)
    }

    // ---------- link temporário

    private class Link(val usuarioId: Int, val pedidoId: Int, val expiraEm: Long)
    private val links = HashMap<String, Link>()

    /** Cria um link só para este pedido e esta pessoa; vale [VALIDADE_SEG] segundos. Só em memória: reiniciar o servidor invalida. */
    @Synchronized
    fun link(usuarioId: Int, pedidoId: Int): String {
        montar(usuarioId, pedidoId)   // 404 se não for dela, 409 se ainda não foi paga
        val agora = Contas.agora()
        links.entries.removeAll { it.value.expiraEm <= agora }
        val bytes = ByteArray(24).also { aleatorio.nextBytes(it) }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        links[token] = Link(usuarioId, pedidoId, agora + VALIDADE_SEG)
        return token
    }

    /** O PDF do pedido a que o [token] dá direito, ou `null` (inexistente, vencido ou pedido sumiu). */
    @Synchronized
    fun pdf(token: String): ByteArray? {
        val l = links[token] ?: return null
        if (l.expiraEm <= Contas.agora()) { links.remove(token); return null }
        val c = runCatching { montar(l.usuarioId, l.pedidoId) }.getOrNull() ?: return null
        return Pdf.de(c)
    }
}

/** PDF mínimo (A4, Helvetica, texto em Latin-1) feito à mão: sem biblioteca, só o que o comprovante precisa. */
object Pdf {
    private const val LARGURA = 595; private const val ALTURA = 842; private const val MARGEM = 50

    /** Latin-1 cobre os acentos do português; o que sai do conjunto vira equivalente simples. */
    internal fun limpar(s: String): String = buildString {
        for (ch in s) append(when (ch) { '→' -> "->"; '—', '–' -> "-"; '“', '”' -> '"'; '‘', '’' -> '\''; else -> if (ch.code in 32..255) ch else '?' })
    }

    private fun escapar(s: String) = limpar(s).replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")

    private class Linha(val x: Int, val y: Int, val tamanho: Int, val negrito: Boolean, val texto: String)

    private fun quebrar(texto: String, max: Int): List<String> {
        val palavras = texto.split(' '); val out = mutableListOf<String>(); var atual = ""
        for (p in palavras) { if (atual.isNotEmpty() && atual.length + 1 + p.length > max) { out += atual; atual = p } else atual = if (atual.isEmpty()) p else "$atual $p" }
        if (atual.isNotEmpty()) out += atual
        return out.ifEmpty { listOf("") }
    }

    fun de(c: Comprovante): ByteArray {
        val paginas = mutableListOf<MutableList<Linha>>(mutableListOf())
        var y = ALTURA - MARGEM
        fun pagina() = paginas.last()
        fun nova() { paginas.add(mutableListOf()); y = ALTURA - MARGEM }
        fun escrever(x: Int, tam: Int, neg: Boolean, t: String, passo: Int) { if (y - passo < MARGEM) nova(); pagina() += Linha(x, y, tam, neg, t); y -= passo }
        escrever(MARGEM, 20, true, "Comprovante de compra", 28)
        escrever(MARGEM, 12, false, "EntreVias · compra ${c.codigo}", 26)
        for (s in c.secoes) {
            if (y - 60 < MARGEM) nova()
            y -= 6; escrever(MARGEM, 13, true, s.titulo, 20)
            for (l in s.linhas) {
                val partes = quebrar(l.valor, 62)
                partes.forEachIndexed { i, parte ->
                    if (y - 15 < MARGEM) nova()
                    if (i == 0) pagina() += Linha(MARGEM, y, 10, true, l.rotulo)
                    pagina() += Linha(MARGEM + 170, y, 10, false, parte); y -= 15
                }
            }
        }
        y -= 12
        quebrar(c.aviso, 95).forEach { escrever(MARGEM, 9, false, it, 13) }

        val out = ByteArrayOutputStream(); val deslocamentos = ArrayList<Int>()
        fun w(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        fun obj(n: Int, corpo: String) { deslocamentos.add(out.size()); w("$n 0 obj\n$corpo\nendobj\n") }
        w("%PDF-1.4\n")
        val nPag = paginas.size
        obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
        obj(2, "<< /Type /Pages /Kids [${(0 until nPag).joinToString(" ") { "${5 + it * 2} 0 R" }}] /Count $nPag >>")
        obj(3, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
        obj(4, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>")
        paginas.forEachIndexed { i, linhas ->
            val conteudo = buildString { for (l in linhas) append("BT /${if (l.negrito) "F2" else "F1"} ${l.tamanho} Tf ${l.x} ${l.y} Td (${escapar(l.texto)}) Tj ET\n") }
            val tam = conteudo.toByteArray(Charsets.ISO_8859_1).size
            obj(5 + i * 2, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $LARGURA $ALTURA] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents ${6 + i * 2} 0 R >>")
            obj(6 + i * 2, "<< /Length $tam >>\nstream\n${conteudo}endstream")
        }
        val xref = out.size(); val total = deslocamentos.size + 1
        w("xref\n0 $total\n0000000000 65535 f \n")
        for (d in deslocamentos) w("%010d 00000 n \n".format(d))
        w("trailer\n<< /Size $total /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toByteArray()
    }
}
