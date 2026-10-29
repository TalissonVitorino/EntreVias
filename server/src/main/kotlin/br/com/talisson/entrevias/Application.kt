package br.com.talisson.entrevias

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.LocalDate

fun main() {
    Banco.iniciar()
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) { json() }
    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.ContentType)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Delete)
    }
    routing {
        get("/") { call.respondText(sayHello("Ktor")) }
        get("/api/saude") { call.respondText("ok") }
        get("/api/cidades") { call.respond(Banco.terminais()) }
        get("/api/parametros") { call.respond(Banco.parametros()) }
        get("/api/parametros/risco") { call.respond(Banco.parametrosRisco()) }
        get("/api/viagens") {
            val data = call.request.queryParameters["data"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (data == null) { call.respond(HttpStatusCode.BadRequest, "Informe data=AAAA-MM-DD"); return@get }
            val dias = (call.request.queryParameters["dias"]?.toIntOrNull() ?: 3).coerceIn(1, 7)
            call.respond(Banco.viagens(data, dias))
        }
    }
}
