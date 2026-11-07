This is a Kotlin Multiplatform project targeting Android, iOS, Web, Desktop (JVM), Server.

* [/app/iosApp](./app/iosApp/iosApp) contains an iOS application. Even if you’re sharing your UI with Compose
  Multiplatform,
  you need this entry point for your iOS app. This is also where you should add SwiftUI code for your project.

* [/app/shared](./app/shared/src) is for code that will be shared across your Compose Multiplatform applications.
  It contains several subfolders:
    - [commonMain](./app/shared/src/commonMain/kotlin) is for code that’s common for all targets.
    - Other folders are for Kotlin code that will be compiled for only the platform indicated in the folder name.
      For example, if you want to use Apple’s CoreCrypto for the iOS part of your Kotlin app,
      the [iosMain](./app/shared/src/iosMain/kotlin) folder would be the right place for such calls.
      Similarly, if you want to edit the Desktop (JVM) specific part, the [jvmMain](./app/shared/src/jvmMain/kotlin)
      folder is the appropriate location.

* [/core](./core/src) is for the code that will be shared between all targets in the project.
  The most important subfolder is [commonMain](./core/src/commonMain/kotlin). If preferred, you
  can add code to the platform-specific folders here too.

* [/server](./server/src/main/kotlin) is for the Ktor server application.

### Running the apps

Use the run configurations provided by the run widget in your IDE's toolbar. You can also use these commands and
options:

- Android app: `./gradlew :app:androidApp:assembleDebug`
- Desktop app:
    - Hot reload: `./gradlew :app:desktopApp:hotRun --auto`
    - Standard run: `./gradlew :app:desktopApp:run`
- Server: `./gradlew :server:run`
- Web app:
    - Wasm target (faster, modern browsers): `./gradlew :app:webApp:wasmJsBrowserDevelopmentRun`
    - JS target (slower, supports older browsers): `./gradlew :app:webApp:jsBrowserDevelopmentRun`
- iOS app: open the [/app/iosApp](./app/iosApp) directory in Xcode and run it from there.

### Running tests

Use the run button in your IDE's editor gutter, or run tests using Gradle tasks:

- Android tests: `./gradlew :app:shared:testAndroidHostTest`
- Desktop tests: `./gradlew :app:shared:jvmTest`
- Server tests: `./gradlew :server:test`
- Web tests:
    - Wasm target: `./gradlew :app:shared:wasmJsTest`
    - JS target: `./gradlew :app:shared:jsTest`
- iOS tests: `./gradlew :app:shared:iosSimulatorArm64Test`

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html),
[Compose Multiplatform](https://kotlinlang.org/compose-multiplatform/),
[Kotlin/Wasm](https://kotl.in/wasm/)…

We would appreciate your feedback on Compose/Web and Kotlin/Wasm in the public Slack
channel [#compose-web](https://slack-chats.kotlinlang.org/c/compose-web).
If you face any issues, please report them on [YouTrack](https://youtrack.jetbrains.com/newIssue?project=CMP).
### Servidor: variáveis de ambiente

| Variável | Para quê |
|---|---|
| `GESTOR_EMAIL` e `GESTOR_SENHA` | criam (ou promovem) o gestor; sem as duas, ninguém vira gestor |
| `CONFIAR_NO_PROXY=true` | atrás de proxy (Render), usa o último IP do `X-Forwarded-For` nos limites de taxa |
| `ENV=production` | desliga os fluxos que dependem de e-mail enquanto o envio for simulado |
| `CAIXA_DE_SAIDA_DEV=true` | só em desenvolvimento: abre `/api/dev/caixa-de-saida` com os e-mails simulados |

O que é simulado, e como trocar por produção: [docs/SIMULADOS.md](docs/SIMULADOS.md).

### Servidor no Docker

O servidor roda em um container (`entrevias-servidor`, porta 8080), e não mais preso a um terminal ou ao IntelliJ.

- **Play no Android Studio** sobe o container antes de instalar o app. Se o Docker estiver desligado, a build avisa e segue.
- **Na mão:** `./gradlew :server:subirServidor` (compila, sobe e espera responder).
- **Parar / ver log:** `docker compose stop` · `docker logs -f entrevias-servidor`.
- `restart: unless-stopped`: se o processo cair, o Docker o sobe de novo; se o Mac reiniciar, volta junto com o Docker Desktop.
- O banco fica no volume `entrevias-dados`: sobrevive a recriar o container. Para zerar: `docker compose down -v`.
- Acesso: emulador Android `http://10.0.2.2:8080`, simulador iOS e navegador `http://localhost:8080`, celular na mesma rede `http://<IP do Mac>:8080`.
- Gestor e caixa de saída de e-mail: crie um arquivo `.env` (fora do git) com `GESTOR_EMAIL`, `GESTOR_SENHA` e, se quiser ler os códigos simulados, `CAIXA_DE_SAIDA_DEV=true`.
- Não rode `:server:run` ao mesmo tempo: as duas usam a porta 8080.
