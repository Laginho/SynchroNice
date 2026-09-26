# SYN-012: Casca do Android
Stage: blocked
Status: ready-for-agent
Blocked by: none
Review: agent

- Primary files:
  - New: android/app/** (módulo do app: Compose, Activity única, leitor de QR, chat)
  - android/settings.gradle.kts
  - android/build.gradle.kts, android/gradle.properties (novos se precisar)
  - android/gradle/wrapper/gradle-wrapper.properties (se o plugin Android exigir Gradle mais novo)
  - android/protocol-client/src/main/kotlin/synchronice/protocolclient/FlatJson.kt, PairingInfo.kt (só validar o conteúdo do QR para o Par)
  - android/protocol-client/src/main/kotlin/synchronice/protocolclient/ProtocolClient.kt (só o ciclo de `/listen` e a consulta autenticada da presença dessa escuta)
  - android/protocol-client/src/test/kotlin/synchronice/protocolclient/PairingInfoTest.kt, ProtocolClientTest.kt (só contratos de QR e `/listen`)
  - pc/src-tauri/src/server.rs (só a consulta autenticada da presença de `/listen`; preservar o GET long-poll e a revogação do Par)
  - pc/src-tauri/tests/server.rs (só o contrato dessa consulta e a regressão do GET)
  - android/protocol-client/build.gradle.kts (só o bloco `plugins`: alinhar o Kotlin JVM à versão que o AGP escolhido traz; o módulo continua JVM puro, sem plugin Android)
  - gate.ps1 (só se o `check` do módulo Android precisar de tarefas explícitas)
  - .gitignore

#### What to build

Spec: `.scratch/android/spec.md`, histórias 1 a 4 e 8 a 13, e a seção "App
Android". Retoma os critérios adiados de SYN-007 (`.scratch/core/issues/07-*`).

O app Android existe e roda no celular, com mínimo Android 10. Um módulo do app
entra ao lado do módulo cliente do protocolo, que continua JVM puro e é
consumido pelo app.

Sem Par, a Activity abre direto na câmera (CameraX com decodificação ZXing, sem
Play Services). O QR do PC é um JSON com nome de instância, porta e token.
Ler o QR grava o Par, e o chat abre. Com Par, o app abre direto no chat.

O celular encontra o PC resolvendo o nome de instância por mDNS (`NsdManager`,
implementando o resolvedor do módulo cliente). Se falhar, tenta o último IP
conhecido, guardado nas preferências. O HTTP sem cifra é liberado na
configuração de segurança de rede (ADR-0002).

A conexão de escuta abre quando o chat aparece e fecha quando sai. O indicador
de presença no topo só fica "Na rede" depois de confirmar que o `/listen` está
ativo e autenticado: resolver o IP ou começar a requisição não basta. Como o
GET long-poll não responde enquanto a fila está vazia, uma consulta
`HEAD /listen` com o mesmo token responde 200 se há escuta autenticada ativa, 204 se
não há e 401 se o token é inválido. O GET e sua revogação mantêm o contrato
atual. Textos do PC viram bolhas à esquerda e são
confirmados ao aparecer. O composer tem campo e botão de enviar; o texto vira
bolha à direita, Pendente até o PC confirmar e depois Entregue. O chat vazio
diz o que fazer. O chat é memória do processo: some quando o Android mata o app.

Build (pronto em `main`, 2026-09-25): Gradle 9.4.1, AGP 9.2.0 e o plugin
Compose 2.2.10 declarados na raiz com `apply false`; o `kotlin("jvm")` do módulo
cliente perdeu a versão e usa o KGP que o AGP traz; `gradle.properties` dá 2 GB
ao Gradle (o dex do Compose + CameraX estoura o padrão); o `:app` já existe, com
o `build.gradle.kts` da tentativa 3 (dependências de Compose, CameraX e ZXing
resolvidas) e um manifesto mínimo que este ticket substitui. O gate já compila o
`:app` e roda o lint dele (~1 min): o critério 2 está coberto pela casca, o
ticket só não pode quebrá-lo. Ao reaproveitar `keep/syn-012`, os arquivos de
build de lá são iguais aos de `main`; só o manifesto conflita, e vale o da tag.

**SDK (resolvido 2026-09-25):** o humano instalou o SDK em
`D:\AppData\Local\Android\Sdk`, com `platforms/android-37.0`, e o
`android/local.properties` (fora do git) aponta para ele. Nenhum stage instala
nem move o SDK.

#### Acceptance criteria

1. O conteúdo do QR do PC (JSON com nome de instância, porta e token) vira o Par; um conteúdo inválido é recusado sem gravar nada.
2. `gate.ps1` compila o módulo do app e continua rodando os testes JVM do módulo cliente.
3. Primeiro uso: o app abre na câmera; ler o QR do PC grava o Par e abre o chat; reabrir o app vai direto para o chat.
4. Na mesma Wi-Fi, o app encontra o PC sem digitar nada; o indicador mostra "Na rede" somente após a confirmação de `/listen` autenticado e ativo, inclusive com a fila vazia, e volta a "Fora da rede" ao fechar ou perder a escuta.
5. Trocar o IP do PC (reconectar o Wi-Fi) e o celular volta a encontrá-lo sem reescanear.
6. Um texto enviado do PC aparece como bolha à esquerda no celular, com o app aberto.
7. Um texto digitado no celular aparece no PC, e a bolha à direita passa de Pendente a Entregue.
8. Com o PC fora da rede, o texto digitado fica Pendente e é entregue quando o PC volta, com o app aberto.
9. O chat vazio mostra uma instrução de uso.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

Critérios 3 a 9 exigem o celular e o PC: verificação manual. O que não der
para ver vira `Débito humano:` (regra "Humano simulado" do `AGENTS.md`).

## Tests stage 2 writes (own commit, red)

- `android/protocol-client/src/test/kotlin/`: ler o conteúdo do QR para o Par,
  com um caso válido e casos inválidos (JSON quebrado, campo faltando, porta
  fora da faixa). Red porque essa leitura ainda não existe. Se ela já existir
  e cobrir isso, o ticket não escreve teste nesta costura e diz isso no commit.
- Na reabertura, `PairingInfoTest.kt` cobre número JSON inválido em campo extra
  com os três campos obrigatórios válidos. `ProtocolClientTest.kt` usa servidor
  falso para provar que a consulta de presença envia o token, confirma a
  escuta ativa mesmo com fila vazia e não confirma presença sem escuta, em 401 ou falha
  de rede. `pc/src-tauri/tests/server.rs` prova `HEAD /listen` 200 só com GET
  autenticado ativo, 204 sem escuta e 401 com token errado; o GET continua
  entregando Itens e a rotação continua revogando a escuta. Novos testes em
  commit próprio, vermelhos antes da correção.

## Comments

- 2026-09-24 Attempt 1 failed: exit 0. Log tail: Encontrei SYN-012 em `Stage: to-implement`, com árvore de trabalho limpa. O binding do repositório atribui o stage 2 ao **gpt-6-luna max**, mas esta sessão não está identificada como esse modelo. Pelo `ticket-flow`, devo parar sem implementar; o próximo passo é executar SYN-012 com gpt-6-luna max.

- 2026-09-24 Attempt 2 failed: exit 0; blocked after two attempts. Log tail: Parei conforme a regra de modelo da `ticket-flow`: [SYN-012](D:/Desktop/Projects/SynchroNice/.scratch/android/issues/03-SYN-012-casca-do-android.md) está em `to-implement`, e o `AGENTS.md` atribui o stage 2 ao **gpt-6-luna max**. Esta sessão não está identificada como esse modelo. /  / Não alterei arquivos nem rodei testes. O ticket já registra uma tentativa anterior com o mesmo impedimento; o próximo passo é executar SYN-012 com gpt-6-luna max.

- 2026-09-24 De volta a to-implement: o bloqueio era o guard de modelo do `ticket-flow` (Codex não informa o modelo à sessão), corrigido no skill. O ticket em si não tem pendência.

- 2026-09-24 Attempt 3 stopped to ask: ## SYN-012 bloqueado /  / Implementei o parser validado do QR e a casca Android com câmera CameraX/ZXing, descoberta mDNS e chat. Os commits na branch `syn-012` são `6b6f3b4` (testes) e `eb57837` (implementação parcial). /  / O ticket está em `Stage: blocked`, registrado em [SYN-012](D:/Desktop/Projects/SynchroNice/.scratch/android/issues/03-SYN-012-casca-do-android.md). O gate confirmou o vermelho inicial esperado; depois, parou na configuração do Gradle, antes de compilar: o AGP compatível com API 37 traz Kotlin Gradle Plugin 2.2.10, mas `protocol-client` fixa 2.0.21. Corrigir isso exige incluir `android/protocol-client/build.gradle.kts` no escopo do ticket. A documentação oficial confirma que AGP 9.2 suporta API 37 e requer Gradle 9.4.1. ([AGP 9.2](https://developer.android.com/build/releases/agp-9-2-0-release-notes)) /  / Também não encontrei o Android SDK nesta máquina; a decisão anterior registrada no `HANDOFF.md` foi não instalá-lo. Sem o SDK, não consegui compilar o app nem fazer a verificação visual; o Computer Use não listou aplicativos disponíveis. /  / Para retomar, falta definir o escopo do arquivo de build do cliente e fornecer o caminho do SDK ou autorizar sua instalação. O gate completo e os fluxos no aparelho continuam sem validação.

- 2026-09-24 Proxy decided: `android/protocol-client/build.gradle.kts` entra nos Primary files, só o bloco `plugins` (dobrado acima; o parágrafo "Build" do corpo diz como) — o AGP 9.x traz o KGP embutido no classpath e o Gradle recusa o `kotlin("jvm") version "2.0.21"` do módulo; alinhar a versão é mudança de build, reversível, sem tocar no código nem nos 7 testes do módulo, que são justamente a rede de segurança para a troca de Kotlin. Manter 2.0.21 não é opção com esse AGP.

- 2026-09-24 Proxy escalated: **não há Android SDK nesta máquina, e instalar é decisão do humano de verdade** — registrada em SYN-007 (`## Comments`, 2026-09-20: "1–2 GB de SDK é mudança na máquina, não no repo, e essa decisão fica com o humano de verdade") e reafirmada no `HANDOFF.md` item 3. Sem SDK o `:app` não compila, o critério 2 não fecha e o stage 3 não teria como verificar 1000+ linhas de Kotlin/Compose nunca compiladas; mergear isso às cegas é o caso "caro de desfazer". Verifiquei de novo hoje: `android/local.properties`, `%LOCALAPPDATA%\Android\Sdk`, `ANDROID_HOME`, `ANDROID_SDK_ROOT` e `sdkmanager` ausentes; `C:\Program Files\Android` só tem o Android Studio. O que o humano precisa decidir, uma das três: (1) instalar o SDK aqui (plataforma 37 + build-tools, via Android Studio ou cmdline-tools) e criar `android/local.properties`; (2) mover o build Android para outra máquina ou para CI (o repo é GitHub e não tem workflow ainda; os runners do Actions vêm com o SDK) — isso muda o contrato do gate e é stage 1; (3) parar este ticket até lá. Nada foi instalado nem autorizado. O trabalho do stage 2 sobrevive só como commits sem ref (`6b6f3b4` testes, `eb57837` implementação, base `61e8da2`, anterior ao merge do SYN-011): quem retomar faz `git branch syn-012 eb57837` e rebase em `main` antes de continuar; a branch `syn-012` em si já não existe.

- 2026-09-25 De volta a to-implement: o humano instalou o Android SDK em `D:\AppData\Local\Android\Sdk`; `android/local.properties` (fora do git) aponta para ele. O trabalho parcial da tentativa 3 está na tag local `keep/syn-012` (`6b6f3b4` testes, `eb57837` implementação), reaproveitável.

- 2026-09-25 Build Android montado em `main` antes do run (humano pediu): gate passa com `:app` compilando e lint incluso. O ticket fica com o código do app.

- 2026-09-25 Attempt 3 failed: exit 0; blocked after two attempts. Log tail: [SYN-012](/C:/Users/bruno/Desktop/Pastinha/Programas/Projects/SynchroNice/.scratch/android/issues/03-SYN-012-casca-do-android.md:2) está `blocked`; commit `1262762` na branch `syn-012` guarda os casos do parser QR. /  / Os 37 testes Rust e 24 Node passaram. Os testes Android não iniciaram porque o download do Gradle 9.4.1 expirou; retome quando a distribuição estiver disponível ou o acesso a `services.gradle.org` funcionar.

- 2026-09-25 Proxy decided: o bloqueio de ambiente foi resolvido. Gradle 9.4.1 oficial está no cache temporário `C:\Users\bruno\AppData\Local\Temp\SynchroNice-gradle-home`, com SHA-256 validado; o `android/local.properties` ignorado aponta para o SDK instalado. Com `GRADLE_USER_HOME` nesse cache, `gate.ps1 -TestsOnly` passou, inclusive `:protocol-client:test` e `:app:testDebugUnitTest`. Retomar o stage 2 a partir do teste vermelho preservado em `1262762`.

- 2026-09-25 Attempt 4 failed: exit 0; blocked after two attempts. Log tail: SYN-012 está em `to-implement` na branch `syn-012`; o binding atribui o stage 2 ao **gpt-6-luna max**. Como esta sessão não identifica o modelo, parei conforme o `ticket-flow`, sem rodar testes nem alterar arquivos. Reexecute com gpt-6-luna max.

- 2026-09-25 Proxy decided: a tentativa 4 foi um falso bloqueio de modelo. O driver lançou `codex exec --model gpt-6-luna --config model_reasoning_effort=max`, conforme o binding; falta de autoidentificação não indica incompatibilidade. Guard do `ticket-flow` esclarecido. Retomar o stage 2 sem alterar o teste vermelho em `1262762`.

- 2026-09-26 Attempt 5 failed: exit 0; blocked after two attempts. Log tail: SYN-012 está `Stage: blocked`, registrado no commit `ede906c`. /  / O critério 4 exige ampliar o escopo para `ProtocolClient.listen()` e seus testes. A etapa 1 precisa aprovar essa costura antes de retornar o ticket a `to-implement`. Nenhum código foi alterado nem teste executado.

- 2026-09-26 Proxy decidiu: autorizar apenas a costura de presença de `/listen` no cliente, no servidor PC e em seus testes, conforme Primary files e critério 4 acima. Com fila vazia, o GET autentica e bloqueia antes de responder; portanto o Android não pode inferir autenticação por resolução mDNS, conexão TCP ou início do GET. `HEAD /listen` confirma escuta autenticada sem mudar o long-poll nem o 401 de revogação. Testes de contrato vermelhos precedem a correção; nenhum outro comportamento do protocolo entra neste ticket.

- 2026-09-26 Attempt 6 failed: timeout; blocked after two attempts. Log tail: ...warning: in the working copy of 'android/protocol-client/src/test/kotlin/synchronice/protocolclient/ProtocolClientTest.kt', LF will be replaced by CRLF the next time Git touches it\n[syn-012 6c05a96] test: fix listen presence assertion typing (SYN-012)\n 1 file changed, 1 insertion(+), 1 deletion(-)\n","exit_code":0,"status":"completed"}} / {"type":"item.started","item":{"id":"item_57","type":"command_execution","command":"\"C:\\\\Users\\\\bruno\\\\.cache\\\\codex-runtimes\\\\codex-primary-runtime\\\\dependencies\\\\native\\\\powershell\\\\pwsh.exe\" -Command '$jdkHome = Join-Path (Join-Path $env:TEMP '\"'SynchroNice-temurin-17-jdk') 'jdk-17.0.20.1+1'\n\"'$androidHome = Join-Path $env:TEMP '\"'SynchroNice-android-user-home'\n\"'$localAppData = Join-Path $androidHome '\"'Local'\nNew-Item -ItemType Directory -Force -Path \"'$localAppData | Out-Null\n$env:JAVA_HOME = $jdkHome\n$env:PATH = \"$jdkHome'\"\\\\bin;\"'$env:PATH\"\n$env:JAVA_TOOL_OPTIONS = \"-Duser.home=$androidHome\"\n$env:ANDROID_USER_HOME = $androidHome\n$env:LOCALAPPDATA = $localAppData\npowershell -ExecutionPolicy Bypass -File .'\"\\\\gate.ps1 -TestsOnly\"","aggregated_output":"","exit_code":null,"status":"in_progress"}} /
