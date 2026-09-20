# SYN-008: Android envia por Compartilhar
Stage: to-implement
Status: ready-for-agent
Blocked by: SYN-007, SYN-003
Review: agent

- Primary files:
  - android/** (intent filters SEND e SEND_MULTIPLE, serviço em primeiro plano, envio de arquivo em stream com progresso, fila de Pendentes em disco, bolhas de arquivo)

#### What to build

**Escopo desta entrega (decisão de 2026-09-20, ver `## Comments`):** só o
envio de arquivo e a fila de Pendentes em disco no módulo cliente do
protocolo (`android/protocol-client`, Kotlin/JVM puro), sem
`com.android.application`. É a parte do ticket que compila e é testável com
o JDK 17 já instalado, é a única costura que "Tests stage 2 writes" nomeia,
e é o que o critério 7 cobre. Nada de intent filters, serviço em primeiro
plano, notificação ou bolhas nesta rodada.

O módulo cliente ganha `sendFile`: manda um arquivo ao PC em stream
(`POST /items/file`), sem carregar o arquivo inteiro na memória, emitindo
progresso crescente que termina em 100. Falha no meio deixa o Item Pendente,
com a mesma política de fila do `sendText` — drena os Pendentes antes, nunca
fura a ordem. A fila de Pendentes sai da memória e vai para o disco, atrás
de uma costura própria (`PendingQueueStore`): recriar o cliente relê a fila
e a drena em ordem, que é o que a casca do app vai precisar para o critério
6 quando existir.

#### Adiado — precisa do Android SDK

O que segue é o fluxo de Compartilhar no app de fato e sai do escopo desta
entrega. Vira ticket próprio quando o Android SDK existir numa máquina de
build.

O caminho principal de entrada. "Compartilhar" em qualquer app do Android
lista o SynchroNice. Texto ou link compartilhado vira Item de texto e chega
no PC. Imagem ou arquivo compartilhado vira Item de arquivo e chega na Pasta
do PC. Vários arquivos compartilhados juntos viram Itens separados, nunca
agrupados. O envio roda num serviço em primeiro plano com notificação de
progresso, para continuar mesmo com o app fora de tela. Cada Item aparece no
chat do celular como bolha à direita com progresso animado, Pendente se o PC
não está na rede, e a fila Pendente é gravada em disco e drenada quando o PC
aparece. Compartilhar não abre o chat: mostra uma confirmação curta e pronto.

Critérios adiados:

1. Compartilhar uma foto da galeria para o SynchroNice a faz chegar na Pasta do PC e aparecer no chat do PC, sem abrir o chat do celular.
2. Compartilhar um link do navegador faz o texto chegar no PC.
3. Compartilhar 3 fotos de uma vez gera 3 bolhas separadas nos dois lados e 3 arquivos na Pasta.
4. Compartilhar um vídeo de mais de 500 MB mostra progresso na notificação e na bolha, e o arquivo chega íntegro mesmo com o app fora de tela durante o envio.
5. Compartilhar com o PC fora da rede deixa os Itens Pendentes; ao voltar para a rede do PC eles são entregues sem ação do usuário.
6. Fechar o app e reabrir mantém os Pendentes na fila (o chat visível está vazio; a fila não).

#### Acceptance criteria

7. `gate.ps1` roda os testes JVM do módulo cliente para o envio de arquivo (`sendFile`: progresso crescente até 100, Pendente em falha) e para a fila de Pendentes em disco (relida e drenada em ordem ao recriar o cliente), e falha se eles falharem.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

Critérios 1 a 6: adiados (bloco acima), sem verificação manual nesta entrega.

## Tests stage 2 writes (own commit, red)

- `android/.../test/ProtocolClientTest.kt`: envio de arquivo em stream emite progresso crescente e termina em 100; falha no meio deixa o Item Pendente; fila gravada em disco é relida ao recriar o cliente e drenada em ordem. Vermelho porque envio de arquivo e fila não existem.

## Comments

- 2026-09-20 (revisão de SYN-007, para quem for implementar a fila em disco):
  hoje uma recusa do PC trava a fila. `ProtocolClient.retryPending()` faz
  `peek()` e, se a resposta não for 201, lança `ProtocolRejectedException`
  com o Item ainda na cabeça da fila — daí em diante todo `sendText` lança
  antes de chegar na rede, nada mais é entregue, e o texto daquela chamada
  se perde (não entra na fila). Cenário real: o Par rotaciona o token
  (SYN-005) enquanto há Pendente; o PC volta e responde 401. O critério 5
  ("ao voltar para a rede do PC eles são entregues sem ação do usuário")
  cobre o caso de indisponibilidade, não o de recusa — decidir aqui o que
  acontece com um Item recusado (descartar, marcar como falho na bolha,
  pedir re-pareamento) e escrever o teste junto com a fila em disco.
- 2026-09-20 (revisão de SYN-007): `ProtocolClient` não é thread-safe. O
  `ConcurrentLinkedQueue` protege a fila, mas `sendText` drena e então
  envia sem exclusão nenhuma, então duas threads intercalando furam a ordem
  que o próprio SYN-007 consertou. Sem chamador concorrente hoje; o serviço
  em primeiro plano deste ticket é o primeiro. Vale um lock em volta do par
  drenar/enviar quando o serviço entrar.

- 2026-09-20 Attempt 1 failed: exit 0. Log tail: **SYN-008 is `Stage: blocked` on branch `syn-008`**, off the session branch. /  / Delivered (gate green, red-before-green proven): `ProtocolClient.sendFile()` — streams a file to `/items/file` with 0→100 progress, no full-file buffering, and the same never-fork-the-queue policy as `sendText`. The in-memory pending queue became a `PendingQueueStore` seam with a real disk-backed implementation, so Pendentes (text and file) survive recreating the client — the exact seam the ticket named in "Tests stage 2 writes." /  / Blocked on the rest: intent filters, the foreground service, and chat-bubble UI need a real Android app module (`com.android.application`), which needs the Android SDK — not installed on this machine. This is the identical wall SYN-007 hit, where you already said not to install it. I left the question on the ticket rather than guess: install the SDK now and I'll build the app shell, or keep that decision and split SYN-008's remaining criteria into a follow-up ticket once a build machine has the SDK. /

- 2026-09-20 (stage 2, notas para o revisor): entreguei a parte testável
  deste ticket — a única costura que "Tests stage 2 writes" nomeia. Branch
  `syn-008`, dois commits: `9a22f07`+`0e03055` (teste, vermelho — erro de
  compilação porque `sendFile` e `PendingQueueStore` não existiam; segundo
  commit só corrige um byte FF literal que reescrever o arquivo de teste
  inteiro tinha comido, ver nota de SYN-007 sobre esse byte) e `e48df1a`
  (código). `ProtocolClient.sendFile(file, onProgress)` manda o arquivo em
  stream pro `/items/file` (`X-File-Name`, `setFixedLengthStreamingMode`,
  sem bufferizar o arquivo inteiro), progresso 0–100 a cada chunk de 8 KB,
  termina em 100. A fila de Pendentes virou `PendingQueueStore`
  (`InMemoryPendingQueueStore` pros testes que já existiam,
  `FilePendingQueueStore` — uma linha por Item, formato interno, TAB e
  escapes próprios — pro critério 6 de verdade). `sendText`/`sendFile`
  seguem a mesma política: drena o Pendente antes, nunca fura a fila; uma
  falha de rede em qualquer ponto do envio (não simulei o meio da conexão
  TCP quebrando — usei porta fechada, mesmo truque dos testes de texto já
  existentes, decisão de determinismo sobre realismo, igual ao resto do
  arquivo) deixa o Item Pendente igual a `sendText`. Gate completo verde
  (`cargo test` 24/24, `tsc`, `gradle check` com `protocol-client` 10/10 —
  4 antigos + 3 novos + os 3 que este ticket somou). Vermelho confirmado
  antes do código: erro de compilação (`queueStore`/`sendFile` não
  existiam) — mesmo padrão que o primeiro round de SYN-007 aceitou
  (capacidade nova, não defeito reaberto).

  A nota de thread-safety acima (SYN-007) segue valendo: ainda não tem
  chamador concorrente, porque o chamador concorrente é o serviço em
  primeiro plano que não construí (bloqueio abaixo). Quem implementar o
  serviço precisa olhar essa nota antes de chamar `sendText`/`sendFile` de
  mais de uma thread.

  **Bloqueado no resto do ticket — mesma parede de SYN-007.** Os Primary
  files pedem "intent filters SEND e SEND_MULTIPLE, serviço em primeiro
  plano, ... bolhas de arquivo" — app Android de verdade, não o módulo
  `protocol-client` (JVM puro). Isso exige `com.android.application` no
  Gradle, que exige o Android SDK local (não vem do Maven) — e esta máquina
  não tem: sem `ANDROID_HOME`/`ANDROID_SDK_ROOT`, sem
  `android/local.properties`. É a mesma "Débito humano" que SYN-007 deixou
  aberta ("Android SDK não instalado nesta máquina — decidir se instala o
  SDK e abrir ticket para ela") e a mesma decisão que o humano já tomou uma
  vez para o projeto (comentário de SYN-007: "não instale... 1–2 GB de SDK
  é mudança na máquina, não no repo"). Não tentei instalar de novo, nem
  toquei em `android/settings.gradle.kts` — declarar `com.android.application`
  sem o SDK quebraria `gradle check` pra todo mundo a partir do commit em
  que aparecesse.

  Preciso de uma decisão, igual à de SYN-007:

  1. Instalar o Android SDK agora (cmdline-tools, platform, build-tools —
     ~1–2 GB nesta máquina), e eu volto e construo o app de verdade
     (intent filters, serviço, bolhas) neste mesmo ticket; ou
  2. Manter a decisão já tomada em SYN-007 (não instalar) e cortar o
     escopo: SYN-008 fecha com só a parte entregue acima (o `protocol-client`
     cobre exatamente o que "Tests stage 2 writes" pedia), e os critérios
     1–6 — que são todos comportamento do app de verdade — viram um ticket
     próprio (`SYN-010`?), adiado até existir SDK numa máquina de build,
     igual ao bloco "Adiado" que SYN-007 já usa.

  Nada commitado além do que já está nesta branch (dois commits de teste,
  um de código, este comentário); não toquei nos critérios numerados nem
  no corpo do ticket além de `Stage` e deste comentário.

- 2026-09-20 Resposta (humano): **opção 2, a mesma de SYN-007.** Não instale
  o Android SDK: a decisão registrada em SYN-007 continua valendo — 1–2 GB de
  SDK é mudança na máquina, não no repo, e fica com o humano de verdade. O
  que este ticket tem de testável sem SDK é o módulo cliente: `sendFile` em
  stream com progresso e a fila de Pendentes em disco, exatamente o que
  "Tests stage 2 writes" nomeia e o que a tentativa 1 entregou com gate
  verde. Escopo reduzido a isso + critério 7; critérios 1–6 (intent filters,
  serviço em primeiro plano, bolhas) movidos para o bloco "Adiado — precisa
  do Android SDK" no corpo, numeração original mantida, igual a SYN-007. Sem
  ticket novo por enquanto: o bloco Adiado é o registro, e o ticket próprio
  nasce quando houver SDK numa máquina de build, junto com a casca de
  SYN-007.

  **Sobre a tentativa 1:** as notas para o revisor logo acima foram
  recuperadas por mim, sem edição, do commit `c74d49d` — o driver apagou a
  branch `syn-008` ao vê-la terminar sem `to-review` (a branch nasceu nesta
  tentativa) e anotou "Attempt 1 failed" na sessão, então o código entregue
  não está em branch nenhuma. Os commits continuam no repositório,
  alcançáveis pelo reflog: `9a22f07` e `0e03055` (só teste, vermelho),
  `e48df1a` (só código: `sendFile` + `PendingQueueStore`), `c74d49d` (só
  este ticket, não reaproveitar). Quem pegar o stage 2 agora: criar
  `syn-008` a partir da branch de sessão e `git cherry-pick 9a22f07 0e03055
  e48df1a`, nessa ordem, em vez de reescrever — mantém a separação
  teste/código e a prova de vermelho (refazer a prova: `-TestsOnly` no
  estado sem `e48df1a`, vermelho; com ele, verde); o hunk de `9a22f07` neste
  arquivo é só a linha de `Stage` e deve aplicar limpo. Depois gate completo
  e `to-review`, sem tocar em nada fora de `android/protocol-client/`.
- Débito humano: fluxo de Compartilhar no app Android (critérios 1–6: intent filters SEND/SEND_MULTIPLE, serviço em primeiro plano com notificação de progresso, bolhas de arquivo com progresso, confirmação curta sem abrir o chat) não foi construído nem verificado à mão — depende do Android SDK numa máquina de build; decidir se instala e abrir ticket para ele, junto com a casca de SYN-007.
