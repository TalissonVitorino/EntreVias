package br.com.talisson.entrevias

import br.com.talisson.entrevias.domain.AvisoApi
import br.com.talisson.entrevias.domain.CadastroPedido
import br.com.talisson.entrevias.domain.CodigoPedido
import br.com.talisson.entrevias.domain.EsqueciSenhaPedido
import br.com.talisson.entrevias.domain.EstadoEmail
import br.com.talisson.entrevias.domain.RedefinirSenhaPedido
import br.com.talisson.entrevias.domain.Empresa
import br.com.talisson.entrevias.domain.EmpresaPedido
import br.com.talisson.entrevias.domain.ErroApi
import br.com.talisson.entrevias.domain.LoginPedido
import br.com.talisson.entrevias.domain.ParametrosConexao
import br.com.talisson.entrevias.domain.ParametrosPlanoB
import br.com.talisson.entrevias.domain.ParametrosRisco
import br.com.talisson.entrevias.domain.PerfilPedido
import br.com.talisson.entrevias.domain.SessaoResposta
import br.com.talisson.entrevias.domain.Terminal
import br.com.talisson.entrevias.domain.Usuario
import br.com.talisson.entrevias.domain.Viagem
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

expect fun urlBase(): String

/** Resposta de uma chamada de conta: deu certo, ou falhou com o texto que o servidor mandou (sem detalhe interno). */
sealed interface Resp<out T> {
    data class Ok<T>(val valor: T) : Resp<T>
    /** `status` 0 = sem conexão. `esperaSeg` vem do Retry-After quando a conta está bloqueada (429). */
    data class Falha(val status: Int, val mensagem: String, val esperaSeg: Int? = null) : Resp<Nothing>
}

/** Fala com o servidor EntreVias. Nada de horário ou preço fica escrito no app. */
class Api(private val base: String = urlBase()) {
    private val http = HttpClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

    suspend fun terminais(): List<Terminal> = http.get("$base/api/cidades").body()
    suspend fun parametros(): ParametrosConexao = http.get("$base/api/parametros").body()
    suspend fun parametrosRisco(): ParametrosRisco = http.get("$base/api/parametros/risco").body()
    suspend fun viagens(data: Data, dias: Int = 3): List<Viagem> =
        http.get("$base/api/viagens") { parameter("data", data.iso()); parameter("dias", dias) }.body()
    suspend fun parametrosPlanoB(): ParametrosPlanoB = http.get("$base/api/parametros/planob").body()

    // ---------- conta (US8, US9, US11) e empresas (US7) ----------

    private suspend fun falha(r: HttpResponse): Resp.Falha {
        val msg = runCatching { r.body<ErroApi>().mensagem }.getOrElse { "Algo deu errado. Tente de novo." }
        return Resp.Falha(r.status.value, msg, r.headers[HttpHeaders.RetryAfter]?.toIntOrNull())
    }

    private suspend inline fun <reified T> chamar(crossinline req: suspend () -> HttpResponse): Resp<T> = try {
        val r = req()
        if (r.status.isSuccess()) Resp.Ok(r.body<T>()) else falha(r)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        Resp.Falha(0, "Sem conexão com o servidor. Confira a internet e tente de novo.")
    }

    suspend fun cadastrar(p: CadastroPedido): Resp<SessaoResposta> = chamar { http.post("$base/api/cadastro") { contentType(ContentType.Application.Json); setBody(p) } }
    suspend fun entrar(p: LoginPedido): Resp<SessaoResposta> = chamar { http.post("$base/api/login") { contentType(ContentType.Application.Json); setBody(p) } }
    suspend fun sair(token: String) {
        try { http.post("$base/api/logout") { header(HttpHeaders.Authorization, "Bearer $token") } } catch (e: CancellationException) { throw e } catch (_: Exception) { }
    }
    suspend fun perfil(token: String): Resp<Usuario> = chamar { http.get("$base/api/perfil") { header(HttpHeaders.Authorization, "Bearer $token") } }
    suspend fun salvarPerfil(token: String, p: PerfilPedido): Resp<Usuario> =
        chamar { http.put("$base/api/perfil") { header(HttpHeaders.Authorization, "Bearer $token"); contentType(ContentType.Application.Json); setBody(p) } }
    suspend fun empresas(token: String): Resp<List<Empresa>> = chamar { http.get("$base/api/empresas") { header(HttpHeaders.Authorization, "Bearer $token") } }
    suspend fun criarEmpresa(token: String, p: EmpresaPedido): Resp<Empresa> =
        chamar { http.post("$base/api/empresas") { header(HttpHeaders.Authorization, "Bearer $token"); contentType(ContentType.Application.Json); setBody(p) } }
    suspend fun atualizarEmpresa(token: String, id: Int, p: EmpresaPedido): Resp<Empresa> =
        chamar { http.put("$base/api/empresas/$id") { header(HttpHeaders.Authorization, "Bearer $token"); contentType(ContentType.Application.Json); setBody(p) } }

    // ---------- e-mail: recuperar senha (RQ9) e confirmar e-mail (RQ10) ----------

    suspend fun estadoEmail(): Resp<EstadoEmail> = chamar { http.get("$base/api/email/estado") }
    suspend fun esqueciSenha(email: String): Resp<AvisoApi> =
        chamar { http.post("$base/api/senha/esqueci") { contentType(ContentType.Application.Json); setBody(EsqueciSenhaPedido(email)) } }
    suspend fun redefinirSenha(p: RedefinirSenhaPedido): Resp<AvisoApi> =
        chamar { http.post("$base/api/senha/redefinir") { contentType(ContentType.Application.Json); setBody(p) } }
    suspend fun enviarVerificacao(token: String): Resp<AvisoApi> = chamar { http.post("$base/api/verificacao/enviar") { header(HttpHeaders.Authorization, "Bearer $token") } }
    suspend fun confirmarEmail(token: String, codigo: String): Resp<Usuario> =
        chamar { http.post("$base/api/verificacao/confirmar") { header(HttpHeaders.Authorization, "Bearer $token"); contentType(ContentType.Application.Json); setBody(CodigoPedido(codigo)) } }
}
