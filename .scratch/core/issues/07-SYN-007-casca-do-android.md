# SYN-007: Casca do Android
Stage: to-implement
Status: ready-for-agent
Blocked by: SYN-005
Review: agent

- Primary files:
  - New: android/** (projeto Gradle Kotlin com wrapper: módulo cliente do protocolo, JVM puro)
  - gate.ps1 (incluir testes JVM do módulo cliente)
  - .gitignore (artefatos do Gradle)

#### What to build

**Escopo desta entrega (decisão de 2026-09-20, ver `## Comments`):** só o
módulo cliente do protocolo, Kotlin/JVM puro, sem `com.android.application`.
Ele é a peça da casca que compila e é testável com o JDK 17 já instalado, e
é o que o critério 7 cobre. O projeto Gradle vem com o wrapper commitado
(`gradle/wrapper/gradle-wrapper.properties`, `gradle-wrapper.jar`,
`gradlew.bat`) para o `gate.ps1` conseguir rodar `.\gradlew.bat` sem Gradle
de sistema. Nada de UI, câmera, mDNS ou serviço nesta rodada.

O módulo cliente fala o protocolo do PC: envia texto com o token do Par,
mantém a escuta e entrega Itens em ordem, deixa o Item Pendente quando o
servidor está indisponível e o envia ao reconectar, e resolve o PC pelo nome
de instância caindo para o último IP conhecido quando a resolução falha.
Fica separado da interface e testável na JVM.

#### Adiado — precisa do Android SDK

O que segue é a casca do app de fato e sai do escopo desta entrega. Vira
ticket próprio quando o Android SDK existir numa máquina de build.

O app Android existe. Sem Par, abre a câmera para escanear o QR do PC e grava
o Par. Com Par, mostra o chat vazio no estilo do PC, com indicador de presença.
Encontra o PC resolvendo o nome de instância por mDNS; se falhar, tenta o
último IP conhecido. Mantém a conexão de escuta enquanto o app está em tela e
mostra Itens de texto vindos do PC como bolhas à esquerda. Há um composer com
botão de enviar que manda texto ao PC (bolha à direita, Pendente até o PC
confirmar).

Critérios adiados:

1. Primeiro uso: escanear o QR do PC grava o Par; reabrir o app vai direto para o chat.
2. Com PC e celular na mesma Wi-Fi, o app encontra o PC sem digitar nada e o indicador mostra "na rede".
3. Trocar o IP do PC (reconectar o Wi-Fi) e o celular volta a encontrá-lo sem reescanear.
4. Um texto enviado do PC aparece como bolha à esquerda no celular.
5. Um texto digitado no celular aparece no PC e a bolha à direita passa de Pendente a Entregue.
6. Com o PC fora da rede, o texto digitado fica Pendente e é entregue quando o PC volta.

#### Acceptance criteria

7. `gate.ps1` roda os testes JVM do módulo cliente e falha se eles falharem.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

Critérios 1 a 6: adiados (bloco acima), sem verificação manual nesta entrega.

## Tests stage 2 writes (own commit, red)

- `android/.../test/ProtocolClientTest.kt` contra um servidor falso local: enviar texto com token faz a requisição certa; escuta recebe Itens em ordem; servidor indisponível deixa o Item Pendente e ele é enviado ao reconectar; resolução falhando usa o último IP conhecido. Vermelho porque o módulo cliente não existe.

## Comments

- 2026-09-20 Attempt 1 stopped to ask: ...on this machine: JDK 17 is present, but there's no system Gradle and **no Android SDK** (`ANDROID_HOME`/`ANDROID_SDK_ROOT` unset, no `%LOCALAPPDATA%\Android\Sdk`). Network access works, so I *could* download the Gradle wrapper and the full Android SDK (cmdline-tools + platform + build-tools — roughly 1–2GB), but that's a real install on your machine, not something scoped to the repo. /  / SYN-007 asks for a real Android app module (camera QR scan, chat UI, mDNS, background listening) — that needs `com.android.application` + the SDK to even compile, let alone pass `gradle check` in the full gate. Two ways I can go: /  / 1. **I install the Android SDK now** (cmdline-tools, platform 34, build-tools) so the ticket ships in full — app module included, gate builds it for real. / 2. **Scope this session to the protocol-client module only** — pure Kotlin/JVM, fully covered by the tests the ticket already specifies, gate wired for it (criterion 7). The actual app shell (UI/camera/mDNS/service, criteria 1–6) gets deferred to a follow-up ticket once the SDK exists on a build machine, tracked as a comment on SYN-007 rather than silently dropped. /  / Which do you want — install the SDK, or split the scope? /
- 2026-09-20 Resposta (humano): **opção 2.** Não instale o Android SDK: 1–2 GB de SDK é mudança na máquina, não no repo, e essa decisão fica com o humano de verdade. O módulo cliente em Kotlin/JVM puro compila e testa só com o JDK 17 que já existe, e é exatamente o que o critério 7 pede. **Baixar o Gradle wrapper para dentro do repo está liberado** — `gradle/wrapper/gradle-wrapper.properties`, `gradle-wrapper.jar` da distribuição oficial e `gradlew.bat` são arquivos do projeto, prática padrão, não instalam nada no sistema; sem eles o `gate.ps1` não tem como rodar `.\gradlew.bat`. Escopo do ticket reduzido ao módulo cliente + critério 7; critérios 1–6 movidos para o bloco "Adiado — precisa do Android SDK" no corpo.
- Débito humano: Android SDK não instalado nesta máquina — a casca do app (critérios 1–6: UI, câmera/QR, mDNS, serviço em background) não foi construída nem verificada; decidir se instala o SDK e abrir ticket para ela.

- 2026-09-20 (stage 2, notas para o revisor): módulo `android/protocol-client`
  em `synchronice.protocolclient`, Kotlin/JVM puro (`kotlin("jvm")`,
  `jvmToolchain(17)`). `ProtocolClient` recebe um `PairingInfo` (nome de
  instância, porta, token), um `InstanceResolver` (o mDNS de verdade é a
  casca do app, adiada) e um `KnownAddressStore` — a resolução tenta o
  resolvedor e cai pro último IP salvo quando ele devolve `null`, e todo IP
  resolvido com sucesso é salvo, então a próxima falha já cai nele.

  `sendText` tenta mandar na hora; se `IOException` (PC fora do ar), o texto
  entra numa fila em memória (`ConcurrentLinkedQueue`) e só sai numa chamada
  explícita a `retryPending()` — sem retry automático em background, porque
  isso é "manter a conexão de escuta enquanto o app está em tela", que é
  casca do app e está no bloco Adiado. `listen()` é um ciclo só do long-poll
  (bloqueia, devolve os Itens da resposta, fecha); reabrir em loop também
  fica pra casca do app.

  Sem dependência de JSON: troquei kotlinx.serialization (que pediria o
  plugin de serialização e mais uma versão pra casar com o Kotlin do
  projeto) por um parser interno (`FlatJson.kt`) — o corpo de `/listen` é um
  objeto plano de um nível (`id`, `at_ms`, `kind`, e `text` ou `name`+`size`,
  spec em `server.rs`), então um parser geral seria mais superfície do que
  o contrato pede.

  Os 4 testes usam `com.sun.net.httpserver.HttpServer` (built-in do JDK, sem
  dependência nova) como servidor fake: um por comportamento do "Tests stage
  2 writes" — token na requisição, ordem dos Itens, Pendente-e-reenvio (porta
  livre fechada primeiro pra simular indisponível, depois um servidor real
  sobe nela pra simular reconectar), e resolução falhando usando o IP salvo.
  Vermelho confirmado antes do código (módulo não existia, erro de
  compilação); depois de escrever o código, sobrou um vermelho residual só
  de inferência de tipo em dois `assertEquals` (kotlin.test usa
  `@OnlyInputTypes`, que não generaliza `List<Pair<String, String>>` para
  `List<Pair<String?, String>>` sem anotação) — ajustei em commit próprio,
  ainda só teste, antes do código de verdade.

  Gate completo verde (`fmt`, `clippy`, `cargo test`, `tsc`, `gradle check`).
  Uma rodada anterior do gate completo pegou
  `dois_envios_concorrentes_do_mesmo_nome_preservam_ambos` (pc/src-tauri)
  falhando com `NotFound` — não toquei em nada de `pc/`, e uma segunda
  rodada, sem qualquer mudança, passou; leio como flakiness pré-existente do
  teste de concorrência, não como algo que este ticket introduziu.

  Critério 7 verificado (`gate.ps1` roda os testes do módulo e falharia se
  eles falhassem — foi exatamente isso que aconteceu antes do código
  existir). Critérios 1–6 seguem Adiados, sem verificação manual nesta
  entrega, como já registrado acima.

- 2026-09-20 Attempt 1 stopped to ask: Hit a permission wall: writing `android/gradle/wrapper/gradle-wrapper.properties` is being auto-blocked as a "sensitive file" edit, even via heredoc. Content is just the standard Gradle wrapper config (distribution URL for `gradle-8.10-bin.zip`, no secrets) — I need it to point the wrapper at a real Gradle download so `gate.ps1 -TestsOnly` can run `.\gradlew.bat test`. /  / Can you approve that edit, or would you rather I write it a different way (e.g. you create the file, or you adjust the permission rule for `*.properties` under `android/`)? /
- 2026-09-20 Resposta (humano): **obstáculo já removido, não recrie nada.** O wrapper está commitado nesta branch em `2c8b73c`: `android/gradlew`, `android/gradlew.bat`, `android/gradle/wrapper/gradle-wrapper.jar` e `android/gradle/wrapper/gradle-wrapper.properties` apontando para `gradle-8.10-bin.zip` (release final, não o rc). Não toque nesses quatro arquivos. O `gate.ps1` mudou no mesmo commit: ele agora dispara o Gradle quando existe `android/settings.gradle.kts`, não mais quando existe a pasta `android/` — o wrapper chega antes do módulo, e Gradle num projeto vazio derrubaria o gate dos stages seguintes. Verificado: com o wrapper no lugar e sem `settings.gradle.kts`, o gate completo passa verde. Consequência: no commit em que `android/settings.gradle.kts` aparecer, o módulo `protocol-client` já precisa buildar e passar nos testes, porque o Gradle entra no gate de verdade a partir dali. O clone temporário `.tmp-gradle-wrapper/` de uma sessão anterior já foi removido, árvore limpa. Escopo segue o decidido acima: `protocol-client` em Kotlin/JVM puro (critério 7); critérios 1–6 continuam no bloco "Adiado — precisa do Android SDK".

#### Review (2026-09-20, stage 3) — reaberto

Verdict: Reopen. Critério 7 ✅ (gate verde: Rust 18/18, `tsc --noEmit` limpo,
Kotlin 4/4, `gradle check` BUILD SUCCESSFUL; e o vermelho antes do código foi
real — `c0a31d8` é só teste + scaffolding Gradle, `e3e3f3c` é só código, sem
tocar em teste). **Nenhum critério numerado caiu.** A reabertura é por três
defeitos no código que este ticket entregou, e dois deles precisam de teste
novo — pela regra mecânica do fluxo ("precisa de teste novo ⇒ volta pro
stage 2"), o revisor não os conserta.

O que falta (tudo em `android/protocol-client/`, dentro dos Primary files):

1. ❌ **`sendText` fura a fila.** `ProtocolClient.sendText` sempre tenta a rede
   primeiro, mesmo com a fila de Pendentes cheia: com A pendente e o PC de
   volta, um `sendText("B")` entrega B antes de A. A spec é explícita — "Ao
   detectar o outro lado, drena em ordem". `retryPending()` está certo; o
   caminho que reordena é o `sendText`. Teste que falta: A pendente com o
   servidor fora, servidor sobe, `sendText("B")`, asserta que o servidor viu
   A antes de B.
2. ❌ **Qualquer resposta não-201 vira Pendente.** `trySendText` só olha
   `responseCode == 201`, então um 401 (`server.rs`: "401 sem token ou com
   token errado") é indistinguível do PC fora do ar: o texto entra na fila e
   é retentado para sempre, em silêncio. Recusa do PC não é indisponibilidade
   — precisa sair como erro, não como Pendente. Teste que falta: servidor
   fake que responde 401, asserta que não fica Pendente.
3. ❌ **`FlatJson` estraga `\b` e `\f`.** `parseJsonString` trata `n`, `t`,
   `r`, `u` e cai no `else -> sb.append(escaped)`, então os escapes `\b` e
   `\f` que o `serde_json` emite viram as letras literais `b` e `f` no texto
   do Item. Teste que falta: uma linha de `/listen` com `\b`/`\f` no campo
   `text`.

Também vale endereçar, mesmo sem teste próprio:

- `trySendText` não chama `disconnect()` no caminho de `IOException` —
  conexão vazando no cenário que mais acontece (PC fora da rede).
- `listen()` não olha o `responseCode`: num 401 quem estoura é o
  `inputStream` com `IOException`, o que funciona por acidente, não por
  contrato.

Já consertado pelo revisor (pequeno, dentro dos Primary files, sem teste):

- `.gitignore` ganhou `android/.kotlin/`. O plugin Kotlin do Gradle cria esse
  diretório em `android/` e ele não estava coberto pela seção "Android" —
  hoje está vazio, então a árvore parece limpa, mas uma sessão de build que
  deixe arquivo ali sujaria a árvore de quem rodar o gate.

Fora do escopo deste ticket, já rastreado nos donos (nada a fazer aqui):

- Fila de Pendentes em memória em vez de em disco → **SYN-008** já pede
  exatamente isso (Primary files "fila de Pendentes em disco", critério 6,
  e o teste "fila gravada em disco é relida ao recriar o cliente e drenada em
  ordem"). O `ConcurrentLinkedQueue` daqui é escopo correto, não defeito.
- Ausência de `POST /outbox/{id}/ack` e de ignorar id já recebido → **SYN-009**,
  que já carrega dois comentários da revisão de SYN-004 dizendo isso com todas
  as letras.
- `GET /outbox/{id}/file` e `POST /items/file` com progresso → **SYN-009** e
  **SYN-008** respectivamente.

Ruído revisado e descartado: `Item.FileMeta` vs `Item.File` (o `kind` do
contrato é `file`, mas `FileMeta` diz a verdade — a bolha ainda não tem os
bytes); `KnownAddressStore` com uma só implementação (a costura é o que torna
o critério de fallback testável, e SYN-008 dá a implementação de disco);
`build.gradle.kts` declarando `kotlin("test")` e `kotlin("test-junit5")` (o
segundo é redundante sob `useJUnitPlatform()`, mas mexer no build por estética
arrisca o verde por nada).
