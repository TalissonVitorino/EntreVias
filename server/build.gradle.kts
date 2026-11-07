import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.HttpURLConnection
import java.net.URI

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlinSerialization)
}

group = "br.com.talisson.entrevias"
version = "1.0.0"
application {
    mainClass = "br.com.talisson.entrevias.ApplicationKt"
}

dependencies {
    api(project(":core"))
    implementation(libs.logback)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    implementation(libs.ktor.serverContentNegotiation)
    implementation(libs.ktor.serverCors)
    implementation(libs.ktor.serializationJson)
    implementation(libs.sqlite.jdbc)
    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.kotlin.testJunit)
}

// A imagem Docker usa Java 21: o servidor é compilado para essa versão, não para a do JDK da máquina.
kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_21 } }
tasks.withType<JavaCompile>().configureEach { options.release = 21 }

/**
 * Sobe o servidor no Docker (docker compose up -d --build) e espera ele responder. Ligada ao Play do Android Studio
 * (ver app/androidApp/build.gradle.kts). Se o Docker estiver desligado, AVISA e segue: não trava o build do app.
 */
val subirServidor by tasks.registering {
    group = "entrevias"
    description = "Sobe o servidor do EntreVias no Docker e espera ele responder em :8080"
    dependsOn("installDist")
    val raiz = rootProject.projectDir
    doLast {
        fun aviso(m: String) = logger.warn("\n[EntreVias] $m\n")
        val p = try {
            ProcessBuilder("docker", "compose", "up", "-d", "--build").directory(raiz).redirectErrorStream(true).start()
        } catch (e: Exception) { return@doLast aviso("Docker não encontrado. O servidor NÃO foi iniciado. Abra o Docker Desktop e rode ./gradlew :server:subirServidor") }
        val saida = p.inputStream.bufferedReader().readText()
        if (p.waitFor() != 0) return@doLast aviso("O Docker não subiu o servidor (o Docker Desktop está aberto?):\n$saida")
        val limite = System.currentTimeMillis() + 40_000
        while (System.currentTimeMillis() < limite) {
            val ok = runCatching { (URI("http://localhost:8080/api/saude").toURL().openConnection() as HttpURLConnection).run { connectTimeout = 1000; readTimeout = 1000; responseCode == 200 } }.getOrDefault(false)
            if (ok) return@doLast logger.lifecycle("[EntreVias] Servidor no ar em http://localhost:8080 (Docker, container entrevias-servidor)")
            Thread.sleep(500)
        }
        aviso("O container subiu, mas o servidor não respondeu em 40 s. Veja: docker logs entrevias-servidor")
    }
}
