package br.com.talisson.entrevias

import br.com.talisson.entrevias.domain.CadastroPedido
import br.com.talisson.entrevias.domain.CodigoPedido
import br.com.talisson.entrevias.domain.ErroApi
import br.com.talisson.entrevias.domain.EsqueciSenhaPedido
import br.com.talisson.entrevias.domain.EstadoEmail
import br.com.talisson.entrevias.domain.LoginPedido
import br.com.talisson.entrevias.domain.RedefinirSenhaPedido
import br.com.talisson.entrevias.domain.SessaoResposta
import br.com.talisson.entrevias.domain.Usuario
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** RQ9 (recuperar senha) e RQ10 (confirmar e-mail), com o envio SIMULADO. Um banco só para a execução (BancoDeTeste). */
class RecuperacaoRotasTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val relogioReal = Contas.agora
    private val iteracoesReais = Contas.iteracoes
    private val limitesReais = listOf(Limites.cadastro, Limites.cadastroGlobal, Limites.recuperacao, Limites.recuperacaoGlobal, Limites.recuperacaoPorEmail, Limites.verificacaoPorUsuario)
    private val envioReal = Email.envio
    private val producaoReal = Email.emProducao
    private val caixaAbertaReal = Email.caixaDeSaidaAberta
    private lateinit var caixa: EnvioSimulado

    @BeforeTest fun preparar() {
        BancoDeTeste.pronto; Contas.iteracoes = 1_000
        val folga = { LimiteDeTaxa(1_000_000, 3600) }
        Limites.cadastro = folga(); Limites.cadastroGlobal = folga(); Limites.recuperacao = folga(); Limites.recuperacaoGlobal = folga()
        Limites.recuperacaoPorEmail = folga(); Limites.verificacaoPorUsuario = folga()
        caixa = EnvioSimulado(); Email.envio = caixa; Email.emProducao = false; Email.caixaDeSaidaAberta = false
    }

    @AfterTest fun restaurar() {
        Contas.agora = relogioReal; Contas.iteracoes = iteracoesReais
        Limites.cadastro = limitesReais[0]; Limites.cadastroGlobal = limitesReais[1]; Limites.recuperacao = limitesReais[2]
        Limites.recuperacaoGlobal = limitesReais[3]; Limites.recuperacaoPorEmail = limitesReais[4]; Limites.verificacaoPorUsuario = limitesReais[5]
        Email.envio = envioReal; Email.emProducao = producaoReal; Email.caixaDeSaidaAberta = caixaAbertaReal
    }

    private companion object { val n = AtomicInteger() }
    private fun email() = "recupera${n.incrementAndGet()}@teste.com"
    private fun pedido(email: String, senha: String = "senha-antiga-1") = CadastroPedido("Maria Souza", email, null, senha, aceitaTermos = true)

    private fun comApp(bloco: suspend ApplicationTestBuilder.() -> Unit) = testApplication { application { module() }; bloco() }

    private suspend fun HttpClient.post(url: String, corpo: String? = null, token: String? = null) = request(url) {
        method = HttpMethod.Post
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        if (corpo != null) { contentType(ContentType.Application.Json); setBody(corpo) }
    }

    private suspend fun HttpClient.get(url: String, token: String? = null) = request(url) {
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

    private suspend fun HttpClient.cadastrar(email: String, senha: String = "senha-antiga-1"): SessaoResposta =
        post("/api/cadastro", json.encodeToString(pedido(email, senha))).also { assertEquals(HttpStatusCode.Created, it.status) }
            .let { json.decodeFromString(it.bodyAsText()) }

    private suspend fun HttpClient.esqueci(email: String) = post("/api/senha/esqueci", json.encodeToString(EsqueciSenhaPedido(email)))
    private suspend fun HttpClient.redefinir(email: String, codigo: String, nova: String = "senha-nova-22") =
        post("/api/senha/redefinir", json.encodeToString(RedefinirSenhaPedido(email, codigo, nova)))
    private suspend fun HttpClient.entrar(email: String, senha: String) = post("/api/login", json.encodeToString(LoginPedido(email, senha)))
    private suspend fun HttpClient.confirmar(codigo: String, token: String?) = post("/api/verificacao/confirmar", json.encodeToString(CodigoPedido(codigo)), token)

    /** O código está no texto do último e-mail simulado para essa pessoa. */
    private fun ultimoCodigo(para: String): String =
        Regex("\\d{6}").find(caixa.mensagens().last { it.para == para }.texto)!!.value

    private fun outroCodigo(certo: String) = if (certo == "000000") "111111" else "000000"

    // ---------- RQ9: recuperar senha ----------

    @Test fun pedirCodigoResponde202IgualParaContaQueExisteEQueNaoExiste() = comApp {
        val e = email(); client.cadastrar(e); caixa.limpar()
        val existe = client.esqueci(e); val naoExiste = client.esqueci(email())
        assertEquals(HttpStatusCode.Accepted, existe.status); assertEquals(HttpStatusCode.Accepted, naoExiste.status)
        assertEquals(existe.bodyAsText(), naoExiste.bodyAsText(), "o corpo não pode revelar se a conta existe")
        assertEquals(1, caixa.mensagens().size, "só quem tem conta recebe e-mail")
        assertEquals(e, caixa.mensagens().single().para)
    }

    @Test fun fluxoCompletoTrocaASenhaEncerraSessoesELiberaOBloqueio() = comApp {
        val e = email(); val antiga = client.cadastrar(e)
        repeat(3) { client.entrar(e, "errada-errada") }   // bloqueia o login
        assertEquals(HttpStatusCode.TooManyRequests, client.entrar(e, "senha-antiga-1").status)
        caixa.limpar(); client.esqueci(e)
        assertEquals(HttpStatusCode.OK, client.redefinir(e, ultimoCodigo(e)).status)

        assertEquals(HttpStatusCode.OK, client.entrar(e, "senha-nova-22").status, "a senha nova entra (e o bloqueio foi zerado)")
        assertEquals(HttpStatusCode.Unauthorized, client.entrar(e, "senha-antiga-1").status, "a senha antiga não entra mais")
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/perfil", antiga.token).status, "a sessão antiga foi encerrada")
    }

    @Test fun codigoNaoValeDuasVezes() = comApp {
        val e = email(); client.cadastrar(e); caixa.limpar(); client.esqueci(e)
        val c = ultimoCodigo(e)
        assertEquals(HttpStatusCode.OK, client.redefinir(e, c).status)
        assertEquals(HttpStatusCode.BadRequest, client.redefinir(e, c, "outra-senha-33").status)
        assertEquals(HttpStatusCode.OK, client.entrar(e, "senha-nova-22").status, "a segunda tentativa não trocou a senha")
    }

    @Test fun cincoCodigosErradosInvalidamOCertoTambem() = comApp {
        val e = email(); client.cadastrar(e); caixa.limpar(); client.esqueci(e)
        val certo = ultimoCodigo(e)
        repeat(5) { assertEquals(HttpStatusCode.BadRequest, client.redefinir(e, outroCodigo(certo)).status) }
        assertEquals(HttpStatusCode.BadRequest, client.redefinir(e, certo).status, "no teto de erros o código morre, mesmo o certo")
        assertEquals(HttpStatusCode.OK, client.entrar(e, "senha-antiga-1").status, "a senha não mudou")
    }

    @Test fun codigoVencidoEhRecusado() = comApp {
        val e = email(); client.cadastrar(e); caixa.limpar(); client.esqueci(e)
        val c = ultimoCodigo(e)
        val agora = relogioReal()
        Contas.agora = { agora + Codigos.VALIDADE_SEG + 1 }
        assertEquals(HttpStatusCode.BadRequest, client.redefinir(e, c).status)
    }

    @Test fun codigoDeUmaContaNaoTrocaASenhaDeOutra() = comApp {
        val a = email(); val b = email(); client.cadastrar(a); client.cadastrar(b); caixa.limpar()
        client.esqueci(a); client.esqueci(b)
        val deA = ultimoCodigo(a)
        assertNotEquals(deA, ultimoCodigo(b), "códigos independentes")
        assertEquals(HttpStatusCode.BadRequest, client.redefinir(b, deA).status)
        assertEquals(HttpStatusCode.OK, client.entrar(b, "senha-antiga-1").status)
    }

    @Test fun pedirDeNovoSubstituiOCodigoAnterior() = comApp {
        val e = email(); client.cadastrar(e); caixa.limpar()
        client.esqueci(e); val primeiro = ultimoCodigo(e)
        client.esqueci(e); val segundo = ultimoCodigo(e)
        if (primeiro != segundo) assertEquals(HttpStatusCode.BadRequest, client.redefinir(e, primeiro).status, "o código antigo deixa de valer")
        assertEquals(HttpStatusCode.OK, client.redefinir(e, segundo).status)
    }

    @Test fun senhaNovaCurtaDa400SemConsumirOCodigo() = comApp {
        val e = email(); client.cadastrar(e); caixa.limpar(); client.esqueci(e)
        val c = ultimoCodigo(e)
        assertEquals(HttpStatusCode.BadRequest, client.redefinir(e, c, "curta").status)
        assertEquals(HttpStatusCode.OK, client.redefinir(e, c).status, "o erro de validação não gasta o código")
    }

    @Test fun codigoMalFormadoOuEmailInvalidoDa400() = comApp {
        assertEquals(HttpStatusCode.BadRequest, client.redefinir(email(), "12ab56").status)
        assertEquals(HttpStatusCode.BadRequest, client.redefinir(email(), "123").status)
        assertEquals(HttpStatusCode.BadRequest, client.esqueci("nao-e-email").status)
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/senha/esqueci", "{quebrado").status)
    }

    @Test fun maisDeTresPedidosPorHoraParaOMesmoEmailNaoGeramMaisMensagens() = comApp {
        Limites.recuperacaoPorEmail = LimiteDeTaxa(3, 3600)
        val e = email(); client.cadastrar(e); caixa.limpar()
        repeat(5) { assertEquals(HttpStatusCode.Accepted, client.esqueci(e).status, "a resposta não muda, para não revelar o limite") }
        assertEquals(3, caixa.mensagens().size, "mas só 3 e-mails saem: ninguém é inundado")
    }

    @Test fun limitePorClienteDaMuitasTentativas() = comApp {
        Limites.recuperacao = LimiteDeTaxa(2, 3600)
        assertEquals(HttpStatusCode.Accepted, client.esqueci(email()).status)
        assertEquals(HttpStatusCode.Accepted, client.esqueci(email()).status)
        val r = client.esqueci(email())
        assertEquals(HttpStatusCode.TooManyRequests, r.status); assertNotNull(r.headers[HttpHeaders.RetryAfter])
        assertEquals(HttpStatusCode.TooManyRequests, client.redefinir(email(), "123456").status, "o limite vale para redefinir também")
    }

    @Test fun codigoNoBancoEhSoHashEORespostaNuncaTrazOCodigo() = comApp {
        val e = email(); client.cadastrar(e); caixa.limpar()
        val r = client.esqueci(e); val c = ultimoCodigo(e)
        assertFalse(r.bodyAsText().contains(c), "a resposta HTTP não pode conter o código")
        val guardado = Banco.conexao().use { cn ->
            cn.prepareStatement("select codigo_hash from codigo_verificacao cv join usuario u on u.id = cv.usuario_id where u.email = ? and cv.finalidade = 'senha'")
                .use { it.setString(1, e); it.executeQuery().use { rs -> rs.next(); rs.getString(1) } }
        }
        assertNotEquals(c, guardado); assertTrue(guardado.length > 20)
    }

    @Test fun emProducaoComEnvioSimuladoOsFluxosFicamDesligadosENadaEhEnviado() = comApp {
        val e = email(); val s = client.cadastrar(e); caixa.limpar()
        Email.emProducao = true
        assertEquals(HttpStatusCode.ServiceUnavailable, client.esqueci(e).status)
        assertEquals(HttpStatusCode.ServiceUnavailable, client.redefinir(e, "123456").status)
        assertEquals(HttpStatusCode.ServiceUnavailable, client.post("/api/verificacao/enviar", token = s.token).status)
        assertTrue(caixa.mensagens().isEmpty())
        val estado = json.decodeFromString<EstadoEmail>(client.get("/api/email/estado").bodyAsText())
        assertEquals(EstadoEmail(simulado = true, disponivel = false), estado)
    }

    @Test fun estadoDoEmailAvisaQueEhSimuladoEmDesenvolvimento() = comApp {
        assertEquals(EstadoEmail(simulado = true, disponivel = true), json.decodeFromString(client.get("/api/email/estado").bodyAsText()))
    }

    @Test fun caixaDeSaidaDeDesenvolvimentoSoAbreComAFlagEForaDeProducao() = comApp {
        val e = email(); client.cadastrar(e)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/dev/caixa-de-saida").status, "fechada por padrão")
        Email.caixaDeSaidaAberta = true
        val aberta = client.get("/api/dev/caixa-de-saida")
        assertEquals(HttpStatusCode.OK, aberta.status); assertTrue(aberta.bodyAsText().contains(e))
        Email.emProducao = true
        assertEquals(HttpStatusCode.NotFound, client.get("/api/dev/caixa-de-saida").status, "em produção nunca abre, mesmo com a flag")
    }

    // ---------- RQ10: confirmar e-mail ----------

    @Test fun cadastroJaEnviaOCodigoEConfirmarMarcaOEmailComoVerificado() = comApp {
        val e = email(); val s = client.cadastrar(e)
        assertFalse(s.usuario.emailVerificado)
        val r = client.confirmar(ultimoCodigo(e), s.token)
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(json.decodeFromString<Usuario>(r.bodyAsText()).emailVerificado)
        assertTrue(json.decodeFromString<Usuario>(client.get("/api/perfil", s.token).bodyAsText()).emailVerificado)
    }

    @Test fun confirmarComCodigoErradoOuSemLoginFalha() = comApp {
        val e = email(); val s = client.cadastrar(e); val c = ultimoCodigo(e)
        assertEquals(HttpStatusCode.BadRequest, client.confirmar(outroCodigo(c), s.token).status)
        assertEquals(HttpStatusCode.Unauthorized, client.confirmar(c, null).status)
        assertEquals(HttpStatusCode.Unauthorized, client.confirmar(c, "token-que-nao-existe").status)
        assertFalse(json.decodeFromString<Usuario>(client.get("/api/perfil", s.token).bodyAsText()).emailVerificado)
    }

    @Test fun codigoDeConfirmacaoDeUmaPessoaNaoConfirmaOutra() = comApp {
        val a = email(); val b = email(); client.cadastrar(a); val sb = client.cadastrar(b)
        assertEquals(HttpStatusCode.BadRequest, client.confirmar(ultimoCodigo(a), sb.token).status)
    }

    @Test fun reenviarGeraCodigoNovoELimitaATresPorHora() = comApp {
        Limites.verificacaoPorUsuario = LimiteDeTaxa(3, 3600)
        val e = email(); val s = client.cadastrar(e)   // o cadastro já gastou 1 dos 3
        assertEquals(HttpStatusCode.Accepted, client.post("/api/verificacao/enviar", token = s.token).status)
        assertEquals(HttpStatusCode.Accepted, client.post("/api/verificacao/enviar", token = s.token).status)
        val r = client.post("/api/verificacao/enviar", token = s.token)
        assertEquals(HttpStatusCode.TooManyRequests, r.status); assertNotNull(r.headers[HttpHeaders.RetryAfter])
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/verificacao/enviar").status)
    }

    @Test fun cincoErrosNaConfirmacaoInvalidamOCodigo() = comApp {
        val e = email(); val s = client.cadastrar(e); val c = ultimoCodigo(e)
        repeat(5) { client.confirmar(outroCodigo(c), s.token) }
        assertEquals(HttpStatusCode.BadRequest, client.confirmar(c, s.token).status)
    }

    @Test fun codigoDeConfirmacaoNaoValeDuasVezes() = comApp {
        val e = email(); val s = client.cadastrar(e); val c = ultimoCodigo(e)
        assertEquals(HttpStatusCode.OK, client.confirmar(c, s.token).status)
        assertEquals(HttpStatusCode.BadRequest, client.confirmar(c, s.token).status, "o código foi consumido")
    }

    @Test fun tetoDeErrosEhCinco() {
        assertEquals(5, Codigos.MAX_ERROS, "os testes de teto usam o literal 5 de propósito: não podem acompanhar a constante")
    }

    @Test fun recuperarASenhaTambemProvaOEmail() = comApp {
        val e = email(); client.cadastrar(e); caixa.limpar(); client.esqueci(e)
        client.redefinir(e, ultimoCodigo(e))
        val s = json.decodeFromString<SessaoResposta>(client.entrar(e, "senha-nova-22").bodyAsText())
        assertTrue(s.usuario.emailVerificado)
    }

    @Test fun mensagemDeErroDaRecuperacaoNaoVazaDetalhe() = comApp {
        val r = client.redefinir(email(), "123456")
        val aviso = json.decodeFromString<ErroApi>(r.bodyAsText()).mensagem
        assertEquals("Código inválido ou vencido. Peça um novo.", aviso, "igual para conta que existe ou não")
    }
}
