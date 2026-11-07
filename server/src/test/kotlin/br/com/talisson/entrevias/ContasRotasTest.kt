package br.com.talisson.entrevias

import br.com.talisson.entrevias.domain.CadastroPedido
import br.com.talisson.entrevias.domain.Empresa
import br.com.talisson.entrevias.domain.EmpresaPedido
import br.com.talisson.entrevias.domain.ErroApi
import br.com.talisson.entrevias.domain.LoginPedido
import br.com.talisson.entrevias.domain.PAPEL_GESTOR
import br.com.talisson.entrevias.domain.PerfilPedido
import br.com.talisson.entrevias.domain.SessaoResposta
import br.com.talisson.entrevias.domain.Usuario
import br.com.talisson.entrevias.domain.Viagem
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Rotas de conta (US8, US9, US11) e de empresas (US7). Um banco só para a execução inteira (BancoDeTeste). */
class ContasRotasTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val relogioReal = Contas.agora
    private val iteracoesReais = Contas.iteracoes

    @BeforeTest fun preparar() { BancoDeTeste.pronto; Contas.iteracoes = 1_000 }
    @AfterTest fun restaurar() { Contas.agora = relogioReal; Contas.iteracoes = iteracoesReais }

    private companion object { val n = AtomicInteger() }
    private fun email() = "pessoa${n.incrementAndGet()}@teste.com"
    private fun pedido(email: String = email(), senha: String = "senha-segura-1", nome: String = "Maria Souza") =
        CadastroPedido(nome, email, "(41) 99999-0000", senha, aceitaTermos = true)

    private suspend fun HttpClient.json(metodo: HttpMethod, url: String, corpo: Any? = null, token: String? = null, texto: String? = null): HttpResponse =
        request(url) {
            method = metodo
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            val t = texto ?: corpo?.let { encode(it) }
            if (t != null) { contentType(ContentType.Application.Json); setBody(t) }
        }

    private fun encode(o: Any): String = when (o) {
        is CadastroPedido -> json.encodeToString(o); is LoginPedido -> json.encodeToString(o)
        is PerfilPedido -> json.encodeToString(o); is EmpresaPedido -> json.encodeToString(o)
        else -> error("tipo sem codificador: $o")
    }

    private suspend fun HttpResponse.sessao() = json.decodeFromString<SessaoResposta>(bodyAsText())
    private suspend fun HttpResponse.erro() = json.decodeFromString<ErroApi>(bodyAsText()).mensagem

    private fun comApp(bloco: suspend ApplicationTestBuilder.() -> Unit) = testApplication { application { module() }; bloco() }

    private fun gestor(): String {
        val r = Contas.cadastrar(pedido(), PAPEL_GESTOR) as ResultadoCadastro.Ok
        return r.sessao.token
    }

    // ---------- US8: cadastro ----------

    @Test fun cadastroCriaContaEDevolveSessaoSemSenha() = comApp {
        val e = "  MARIA${n.incrementAndGet()}@Teste.com "
        val r = client.json(HttpMethod.Post, "/api/cadastro", pedido(email = e))
        assertEquals(HttpStatusCode.Created, r.status)
        val corpo = r.bodyAsText()
        assertFalse(corpo.contains("senha-segura-1"), "a resposta não pode conter a senha")
        assertFalse(corpo.contains("pbkdf2"), "a resposta não pode conter o hash")
        val s = json.decodeFromString<SessaoResposta>(corpo)
        assertEquals(e.trim().lowercase(), s.usuario.email)
        assertEquals("viajante", s.usuario.papel)
        assertTrue(s.token.length >= 40)
    }

    @Test fun senhaFicaComoHashESalNuncaEmTextoPuro() = comApp {
        val e = email()
        client.json(HttpMethod.Post, "/api/cadastro", pedido(email = e))
        val hash = Banco.conexao().use { c -> c.prepareStatement("select senha_hash from usuario where email = ?").use { it.setString(1, e); it.executeQuery().use { rs -> rs.next(); rs.getString(1) } } }
        assertTrue(hash.startsWith("pbkdf2_sha256$"))
        assertFalse(hash.contains("senha-segura-1"))
        assertTrue(Contas.confere("senha-segura-1", hash)); assertFalse(Contas.confere("outra-senha-1", hash))
    }

    @Test fun cadastroSemAceiteDosTermosOuComSenhaCurtaEhRecusado() = comApp {
        assertEquals(HttpStatusCode.BadRequest, client.json(HttpMethod.Post, "/api/cadastro", pedido().copy(aceitaTermos = false)).status)
        assertEquals(HttpStatusCode.BadRequest, client.json(HttpMethod.Post, "/api/cadastro", pedido(senha = "curta")).status)
        assertEquals(HttpStatusCode.BadRequest, client.json(HttpMethod.Post, "/api/cadastro", pedido(email = "nao-e-email")).status)
    }

    @Test fun corpoMalformadoDaBadRequestENuncaErro500() = comApp {
        assertEquals(HttpStatusCode.BadRequest, client.json(HttpMethod.Post, "/api/cadastro", texto = "{isso nao e json").status)
        assertEquals(HttpStatusCode.BadRequest, client.json(HttpMethod.Post, "/api/login", texto = "[]").status)
        assertEquals(HttpStatusCode.BadRequest, client.json(HttpMethod.Post, "/api/cadastro", texto = """{"nome":"A"}""").status)
    }

    @Test fun emailRepetidoDaConflitoComMensagemGenerica() = comApp {
        val e = email()
        assertEquals(HttpStatusCode.Created, client.json(HttpMethod.Post, "/api/cadastro", pedido(email = e)).status)
        val r = client.json(HttpMethod.Post, "/api/cadastro", pedido(email = e.uppercase()))
        assertEquals(HttpStatusCode.Conflict, r.status)
        assertFalse(r.erro().contains(e, ignoreCase = true), "a mensagem não repete o e-mail")
    }

    // ---------- US11: login e bloqueio (RQ12) ----------

    @Test fun loginCertoDaSessaoQueAbreOPerfil() = comApp {
        val e = email(); client.json(HttpMethod.Post, "/api/cadastro", pedido(email = e))
        val r = client.json(HttpMethod.Post, "/api/login", LoginPedido(e.uppercase(), "senha-segura-1"))
        assertEquals(HttpStatusCode.OK, r.status)
        val p = client.json(HttpMethod.Get, "/api/perfil", token = r.sessao().token)
        assertEquals(e, json.decodeFromString<Usuario>(p.bodyAsText()).email)
    }

    @Test fun senhaErradaEEmailInexistenteRecebemARespostaIdentica() = comApp {
        val e = email(); client.json(HttpMethod.Post, "/api/cadastro", pedido(email = e))
        val errada = client.json(HttpMethod.Post, "/api/login", LoginPedido(e, "senha-errada-1"))
        val inexistente = client.json(HttpMethod.Post, "/api/login", LoginPedido(email(), "senha-errada-1"))
        assertEquals(HttpStatusCode.Unauthorized, errada.status)
        assertEquals(errada.status, inexistente.status)
        assertEquals(errada.bodyAsText(), inexistente.bodyAsText())
    }

    @Test fun apos3ErrosBloqueiaPor15MinutosMesmoComASenhaCerta() = comApp {
        var t = 1_000_000L; Contas.agora = { t }
        val e = email(); client.json(HttpMethod.Post, "/api/cadastro", pedido(email = e))
        repeat(2) { assertEquals(HttpStatusCode.Unauthorized, client.json(HttpMethod.Post, "/api/login", LoginPedido(e, "errada-1234")).status) }
        val terceira = client.json(HttpMethod.Post, "/api/login", LoginPedido(e, "errada-1234"))
        assertEquals(HttpStatusCode.TooManyRequests, terceira.status)
        assertEquals("900", terceira.headers[HttpHeaders.RetryAfter])
        assertEquals(HttpStatusCode.TooManyRequests, client.json(HttpMethod.Post, "/api/login", LoginPedido(e, "senha-segura-1")).status, "bloqueada até com a senha certa")
        t += 899
        assertEquals(HttpStatusCode.TooManyRequests, client.json(HttpMethod.Post, "/api/login", LoginPedido(e, "senha-segura-1")).status)
        t += 2
        assertEquals(HttpStatusCode.OK, client.json(HttpMethod.Post, "/api/login", LoginPedido(e, "senha-segura-1")).status)
    }

    @Test fun oBloqueioVaiAlemDeContasExistentesParaNaoRevelarEmails() = comApp {
        Contas.agora = { 2_000_000L }
        val fantasma = email()
        repeat(2) { client.json(HttpMethod.Post, "/api/login", LoginPedido(fantasma, "qualquer-senha-1")) }
        assertEquals(HttpStatusCode.TooManyRequests, client.json(HttpMethod.Post, "/api/login", LoginPedido(fantasma, "qualquer-senha-1")).status)
    }

    @Test fun loginCertoZeraAContagemDeErros() = comApp {
        Contas.agora = { 3_000_000L }
        val e = email(); client.json(HttpMethod.Post, "/api/cadastro", pedido(email = e))
        repeat(2) { client.json(HttpMethod.Post, "/api/login", LoginPedido(e, "errada-1234")) }
        assertEquals(HttpStatusCode.OK, client.json(HttpMethod.Post, "/api/login", LoginPedido(e, "senha-segura-1")).status)
        repeat(2) { assertEquals(HttpStatusCode.Unauthorized, client.json(HttpMethod.Post, "/api/login", LoginPedido(e, "errada-1234")).status) }
    }

    // ---------- sessão ----------

    @Test fun semTokenOuComTokenFalsoDa401() = comApp {
        assertEquals(HttpStatusCode.Unauthorized, client.json(HttpMethod.Get, "/api/perfil").status)
        assertEquals(HttpStatusCode.Unauthorized, client.json(HttpMethod.Get, "/api/perfil", token = "token-inventado").status)
    }

    @Test fun sairInvalidaOTokenEASessaoExpiraEm30Dias() = comApp {
        val e = email(); val token = client.json(HttpMethod.Post, "/api/cadastro", pedido(email = e)).sessao().token
        assertEquals(HttpStatusCode.OK, client.json(HttpMethod.Get, "/api/perfil", token = token).status)
        assertEquals(HttpStatusCode.NoContent, client.json(HttpMethod.Post, "/api/logout", token = token).status)
        assertEquals(HttpStatusCode.Unauthorized, client.json(HttpMethod.Get, "/api/perfil", token = token).status)

        val t0 = 5_000_000L; Contas.agora = { t0 }
        val t2 = client.json(HttpMethod.Post, "/api/login", LoginPedido(e, "senha-segura-1")).sessao().token
        Contas.agora = { t0 + Contas.SESSAO_SEG - 1 }
        assertEquals(HttpStatusCode.OK, client.json(HttpMethod.Get, "/api/perfil", token = t2).status)
        Contas.agora = { t0 + Contas.SESSAO_SEG + 1 }
        assertEquals(HttpStatusCode.Unauthorized, client.json(HttpMethod.Get, "/api/perfil", token = t2).status)
    }

    // ---------- US9: perfil ----------

    @Test fun editarPerfilMudaSoOProprioUsuario() = comApp {
        val a = client.json(HttpMethod.Post, "/api/cadastro", pedido(nome = "Ana A")).sessao()
        val b = client.json(HttpMethod.Post, "/api/cadastro", pedido(nome = "Bia B")).sessao()
        val r = client.json(HttpMethod.Put, "/api/perfil", PerfilPedido("Ana Atualizada", "(11) 3333-4444"), a.token)
        assertEquals(HttpStatusCode.OK, r.status)
        val ana = json.decodeFromString<Usuario>(r.bodyAsText())
        assertEquals("Ana Atualizada", ana.nome); assertEquals("1133334444", ana.celular); assertEquals(a.usuario.email, ana.email)
        val bia = json.decodeFromString<Usuario>(client.json(HttpMethod.Get, "/api/perfil", token = b.token).bodyAsText())
        assertEquals("Bia B", bia.nome)
    }

    @Test fun editarPerfilValidaOsDadosEExigeLogin() = comApp {
        val s = client.json(HttpMethod.Post, "/api/cadastro", pedido()).sessao()
        assertEquals(HttpStatusCode.BadRequest, client.json(HttpMethod.Put, "/api/perfil", PerfilPedido("A"), s.token).status)
        assertEquals(HttpStatusCode.BadRequest, client.json(HttpMethod.Put, "/api/perfil", PerfilPedido("Ana", "123"), s.token).status)
        assertEquals(HttpStatusCode.Unauthorized, client.json(HttpMethod.Put, "/api/perfil", PerfilPedido("Ana")).status)
    }

    // ---------- US7: empresas ----------

    @Test fun empresasSoParaGestor() = comApp {
        assertEquals(HttpStatusCode.Unauthorized, client.json(HttpMethod.Get, "/api/empresas").status)
        val viajante = client.json(HttpMethod.Post, "/api/cadastro", pedido()).sessao().token
        assertEquals(HttpStatusCode.Forbidden, client.json(HttpMethod.Get, "/api/empresas", token = viajante).status)
        assertEquals(HttpStatusCode.Forbidden, client.json(HttpMethod.Post, "/api/empresas", EmpresaPedido("Nova Via"), viajante).status)
        assertEquals(HttpStatusCode.Forbidden, client.json(HttpMethod.Put, "/api/empresas/1", EmpresaPedido("Nova Via"), viajante).status)
        assertEquals(HttpStatusCode.OK, client.json(HttpMethod.Get, "/api/empresas", token = gestor()).status)
    }

    @Test fun gestorCadastraEditaEListaEmpresas() = comApp {
        val g = gestor(); val nome = "Viação Teste ${n.incrementAndGet()}"
        val criada = client.json(HttpMethod.Post, "/api/empresas", EmpresaPedido(nome, "vt", "11.222.333/0001-81"), g)
        assertEquals(HttpStatusCode.Created, criada.status)
        val e = json.decodeFromString<Empresa>(criada.bodyAsText())
        assertEquals("VT", e.sigla); assertEquals("11222333000181", e.cnpj); assertTrue(e.ativa); assertEquals(0, e.linhas)

        val editada = client.json(HttpMethod.Put, "/api/empresas/${e.id}", EmpresaPedido("$nome Ltda", "VT", "11222333000181", ativa = false), g)
        assertEquals(HttpStatusCode.OK, editada.status)
        assertFalse(json.decodeFromString<Empresa>(editada.bodyAsText()).ativa)

        val lista = json.decodeFromString<List<Empresa>>(client.json(HttpMethod.Get, "/api/empresas", token = g).bodyAsText())
        assertTrue(lista.any { it.id == e.id && it.nome == "$nome Ltda" })
        assertEquals(HttpStatusCode.NotFound, client.json(HttpMethod.Put, "/api/empresas/999999", EmpresaPedido("Qualquer"), g).status)
    }

    @Test fun empresaRecusaCnpjInvalidoEDuplicidade() = comApp {
        val g = gestor(); val nome = "Duplicada ${n.incrementAndGet()}"
        assertEquals(HttpStatusCode.BadRequest, client.json(HttpMethod.Post, "/api/empresas", EmpresaPedido(nome, cnpj = "11.222.333/0001-82"), g).status)
        assertEquals(HttpStatusCode.Created, client.json(HttpMethod.Post, "/api/empresas", EmpresaPedido(nome, cnpj = "45.723.174/0001-10"), g).status)
        assertEquals(HttpStatusCode.Conflict, client.json(HttpMethod.Post, "/api/empresas", EmpresaPedido(nome), g).status)
        assertEquals(HttpStatusCode.Conflict, client.json(HttpMethod.Post, "/api/empresas", EmpresaPedido("Outra ${n.incrementAndGet()}", cnpj = "45723174000110"), g).status)
    }

    @Test fun empresaPausadaSaiDaBuscaEVoltaAoReativar() = comApp {
        val g = gestor()
        val alvo = Contas.empresas().first { it.linhas > 0 }
        fun empresasNaBusca() = runBlockingBusca().map { it.empresa }.toSet()
        assertTrue(alvo.nome in empresasNaBusca())
        try {
            client.json(HttpMethod.Put, "/api/empresas/${alvo.id}", EmpresaPedido(alvo.nome, alvo.sigla, alvo.cnpj, ativa = false), g)
            assertFalse(alvo.nome in empresasNaBusca(), "empresa pausada some dos resultados")
        } finally {
            client.json(HttpMethod.Put, "/api/empresas/${alvo.id}", EmpresaPedido(alvo.nome, alvo.sigla, alvo.cnpj, ativa = true), g)
        }
        assertTrue(alvo.nome in empresasNaBusca())
    }

    @Test fun promoverContaJaCadastradaTomaASenhaEEncerraAsSessoesDoInvasor() = comApp {
        // um invasor se cadastra com o e-mail do gestor ANTES de o operador configurar GESTOR_EMAIL
        val e = email()
        val invasor = client.json(HttpMethod.Post, "/api/cadastro", pedido(email = e, senha = "senha-do-invasor-1")).sessao().token
        assertEquals(HttpStatusCode.OK, client.json(HttpMethod.Get, "/api/perfil", token = invasor).status)

        Contas.garantirGestor(e, "senha-do-operador-1")

        assertEquals(HttpStatusCode.Unauthorized, client.json(HttpMethod.Get, "/api/perfil", token = invasor).status, "sessão do invasor encerrada")
        assertEquals(HttpStatusCode.Unauthorized, client.json(HttpMethod.Post, "/api/login", LoginPedido(e, "senha-do-invasor-1")).status, "senha do invasor não vale mais")
        val operador = client.json(HttpMethod.Post, "/api/login", LoginPedido(e, "senha-do-operador-1"))
        assertEquals(HttpStatusCode.OK, operador.status)
        assertEquals(PAPEL_GESTOR, operador.sessao().usuario.papel)
    }

    private fun runBlockingBusca(): List<Viagem> = Banco.viagens(java.time.LocalDate.of(2026, 11, 20), 1)
}
