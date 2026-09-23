# SYN-008: Android envia por Compartilhar
Stage: done
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

7. `gate.ps1` roda os testes JVM do módulo cliente para o envio de arquivo (`sendFile`: progresso crescente até 100, Pendente em falha) e para a fila de Pendentes em disco (relida e drenada em ordem ao recriar o cliente, copiada para pasta interna, payload ausente descartado, sobrevive a `\r` e a arquivo corrompido), e falha se eles falharem.

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

- 2026-09-20 (stage 2, retomada): recuperados os três commits da tentativa 1
  via `git cherry-pick 9a22f07 0e03055 e48df1a` numa `syn-008` nova a partir
  da sessão, como a resposta acima pediu — sem reescrever teste nem código.
  Prova de vermelho refeita num worktree descartável: em `793999e` (antes do
  código), `gate.ps1 -TestsOnly` falha no `gradle test` com erro de
  compilação (`sendFile`, `PendingQueueStore`, `InMemoryPendingQueueStore`,
  `FilePendingQueueStore` não resolvidos), Rust intacto (24/24); com
  `e48df1a` em cima, verde. Gate completo nesta branch: `cargo test` 24/24,
  `tsc --noEmit` limpo, `gradle check` BUILD SUCCESSFUL,
  `protocol-client` 10/10 (`TEST-synchronice.protocolclient.ProtocolClientTest.xml`:
  `tests="10" failures="0" errors="0"`). Nada tocado fora de
  `android/protocol-client/` e deste ticket.

#### Review (2026-09-20, stage 3) — reaberto

Verdict: Reopen. Gate verde nesta branch (Rust 18/18 + doc-tests, `tsc --noEmit`
limpo, `gradle check` BUILD SUCCESSFUL, `protocol-client` 10/10 —
`tests="10" failures="0" errors="0"`), separação teste/código real (`4566dd2` e
`793999e` só tocam `ProtocolClientTest.kt`, `7543830` só código), e o contrato
HTTP bate com o servidor (`pc/src-tauri/src/server.rs`: `POST /items/file`,
`X-File-Name`, `Authorization: Bearer`, `Content-Length` via
`setFixedLengthStreamingMode`, 201). `sendFile` em si está certo: stream sem
bufferizar, progresso crescente terminando em 100, falha deixa Pendente.

Cai o **critério 7**, na metade da fila em disco: os testes passam, mas o que foi
persistido não é a Fila de Pendentes que a spec define. Os três itens abaixo
estão todos em `android/protocol-client/` (dentro dos Primary files) e os três
precisam de teste novo — pela regra mecânica do fluxo ("precisa de teste novo ⇒
volta pro stage 2"), o revisor não conserta nenhum.

1. ❌ **A fila guarda o caminho do arquivo, e a spec proíbe isso por escrito.**
   `PendingItem.PendingFile(val path: String, …)` recebe `file.absolutePath`, e
   `retryPending()` reabre `File(next.path)` na hora da entrega. `spec.md`,
   "Fila de Pendentes": *"arquivo copiado para uma pasta interna do app, **nunca
   só o caminho**, porque PNG temporário, arquivo movido e URI de Compartilhar
   do Android não sobrevivem até a entrega"*. É exatamente o caso deste ticket
   (Compartilhar), então a persistência entregue é nominal: sobrevive a recriar
   o cliente, não sobrevive ao que a spec diz que acontece de verdade. O
   `PendingQueueStore` precisa copiar os bytes para uma pasta interna quando o
   Item entra na fila e apagar a cópia quando ele sai. Teste que falta: enfileira
   um arquivo, **apaga o original**, recria o cliente, sobe o servidor, drena —
   o PC recebe os bytes certos com o nome certo.
2. ❌ **Um arquivo Pendente que sumiu trava a fila para sempre.**
   `File(next.path).inputStream()` lança `FileNotFoundException`, que é
   `IOException`, capturada como `SendOutcome.Unreachable`: `retryPending()`
   volta com o Item ainda na cabeça, e daí em diante todo `sendText`/`sendFile`
   vê `pending.isNotEmpty()` e enfileira atrás dele. Nada mais é entregue, e o
   disco preserva o travamento entre reinícios. A cópia do item 1 resolve o caso
   comum, mas o payload ausente ainda precisa de política (descartar o Item e
   seguir a fila, não travar). Teste que falta: Pendente cujo payload não existe
   mais não impede a entrega do Item seguinte.
3. ❌ **Fila corrompida derruba o cliente no construtor, e um `\r` corrompe a
   fila.** `decodeLine` chama `error(...)` e indexa `fields[1]`/`fields[2]` sem
   checar aridade, e isso roda dentro do inicializador de `pending` — logo
   `ProtocolClient(...)` lança, e o app não abre. Pior: `escape()` cobre `\`,
   `\t` e `\n` mas **não** `\r`, enquanto `readLines()` quebra linha em `\r`
   solto e `\r\n`; um texto Pendente com CR vira duas linhas, a segunda cai no
   `else -> error(...)` e a fila inteira se perde — o único defeito que a
   própria KDoc do `FilePendingQueueStore` promete não existir. Testes que
   faltam: round-trip de um texto Pendente com `\`, TAB, `\n` e `\r` (o
   escaping não tem teste nenhum hoje: o teste de disco enfileira só `"A"`), e
   arquivo de fila corrompido não impede construir o cliente.

Não bloqueiam a volta, ficam a critério de quem mexer no código (Standards):

- `sendText` e `sendFile` repetem a mesma forma de quatro passos (drena,
  enfileira-se-pendente, `when(outcome)`); um `private fun send(item: PendingItem)`
  com os dois chamando-o mata a duplicação e o `PendingItem.PendingFile(...)`
  construído duas vezes dentro de `sendFile`.
- `PendingItem.PendingText`/`PendingFile` gagueja; `Item.kt` já nomeia as
  variantes `Item.Text`/`Item.FileMeta`.
- `save()` reescreve a fila inteira com `writeText` a cada enfileirar/desenfileirar:
  crash no meio trunca. Arquivo temporário + rename combina melhor com "sobrevive
  ao app ser fechado".
- `onProgress` dispara a cada 8 KB — ~64 mil chamadas quase idênticas no vídeo de
  500 MB do critério 4 (adiado). Emitir só quando o percentual muda.

Fica como comentário, **fora** do escopo reduzido deste ticket (não é critério
numerado, não volta como ❌):

- A decisão sobre Item recusado que o comentário de SYN-007 pedia "aqui" não foi
  tomada, e a resposta do humano que reduziu o escopo não a incluiu. Segue
  valendo o diagnóstico daquele comentário, agora pior: com a fila em disco, um
  401 na cabeça da fila trava a entrega **entre reinícios**. Junto com ela: o
  `503` do PC sem Pasta escolhida (`server.rs`) hoje vira `Rejected` e o Item é
  perdido sem entrar na fila, embora seja condição recuperável. As duas querem a
  mesma decisão de política e um critério próprio — stage 1.
- `retryPending()` drena arquivo com `onProgress` vazio (`{}`): a barra da bolha
  não anda quando um Pendente é entregue. Só importa quando existir UI — vai
  junto com o ticket da casca do app.
- A nota de thread-safety de SYN-007 continua aberta e continua sem chamador
  concorrente, porque o serviço em primeiro plano segue adiado.

- 2026-09-20 (stage 2, resolve o reopen): os três ❌ acima, cada um com teste
  novo em `ProtocolClientTest.kt`, commit de teste vermelho (`6042500`) antes
  do de código (`0b19266`) — separação confirmada, `-TestsOnly` falhou nos 4
  testes novos antes do código (3 por `AssertionFailedError`, 1 por
  `IllegalStateException` no construtor por causa da linha corrompida) e
  passou depois.
  1. `PendingQueueStore` ganhou `enqueue(item)`/`dequeueFirst()` no lugar de
     `save(items)` genérico: `FilePendingQueueStore.enqueue()` copia o arquivo
     para `<fila>.payloads/` e devolve o Item com o caminho da cópia (é esse
     Item que `ProtocolClient` passa a guardar em memória); `dequeueFirst()`
     apaga a cópia. `InMemoryPendingQueueStore` vira passthrough puro (não
     precisa copiar, nunca sobrevive a reinício mesmo).
  2. `retryPending()` descarta (via `dequeueFirst()`) um `PendingFile` cujo
     `File(path).exists()` é falso, antes de tentar enviar, e segue o loop —
     não trava mais nos itens depois dele.
  3. `escape()`/`unescape()` cobrem `\r` agora (por isso `readLines()` não
     quebra mais um texto Pendente com CR em duas linhas). `decodeLine` virou
     `decodeLineOrNull`: linha com tipo desconhecido ou aridade errada volta
     `null` e é filtrada, em vez de `error(...)` dentro do inicializador de
     `ProtocolClient` — uma fila corrompida não impede mais construir o
     cliente, só perde a(s) linha(s) ilegível(is).

  Não toquei nos itens de Standards do review (duplicação `sendText`/`sendFile`,
  nome de `PendingItem.PendingText`/`PendingFile`, rewrite atômico do arquivo
  de fila, throttling de `onProgress`) nem nos itens "fora do escopo reduzido"
  (política de Item recusado, `503` sem Pasta, progresso do `retryPending`,
  thread-safety) — nenhum deles precisava de teste novo nem está nos Primary
  files além do que os 3 ❌ já cobrem.

  Gate completo verde nesta branch: `cargo test` 24/24 + doc-tests,
  `tsc --noEmit` limpo, `gradle check` BUILD SUCCESSFUL, `protocol-client`
  14/14 (10 antigos + 4 novos). Nada tocado fora de
  `android/protocol-client/` e deste ticket.

#### Resolution (2026-09-20)

Verdict: Approve

Critério 7 passa, e os três ❌ do reopen estão resolvidos com teste de verdade,
não com retoque. Verifiquei o que a revisão anterior derrubou, item por item,
lendo o código e não o comentário de stage 2:

1. **Cópia em vez de caminho.** `PendingQueueStore` trocou `save(items)` por
   `enqueue(item): PendingItem` / `dequeueFirst()`, e é a implementação de disco
   que decide o que guardar: `FilePendingQueueStore.enqueue` copia os bytes para
   `<fila>.payloads/<uuid>-<nome>` e devolve o Item já apontando para a cópia —
   é esse Item que `ProtocolClient` mantém em memória e grava na linha. A cópia
   morre em `dequeueFirst()`. O teste apaga o original antes de drenar e confere
   nome e bytes do lado do servidor (`4`,`5`,`6`): a persistência deixou de ser
   nominal, que era o defeito.
2. **Payload ausente não trava mais.** `retryPending()` checa
   `File(next.path).exists()` antes de tentar enviar e descarta via
   `dequeueFirst()`, continuando o laço — o `FileNotFoundException` virando
   `Unreachable` não acontece mais. O teste prova o que importa: o Item seguinte
   é entregue.
3. **`\r` e fila corrompida.** `escape`/`unescape` cobrem `\r` (o caso que
   `readLines()` transformava em duas linhas), e `decodeLine` virou
   `decodeLineOrNull` + `mapNotNull`, fora do caminho de `error(...)` — construir
   o cliente com lixo no arquivo não lança mais. O round-trip testa `\`, TAB,
   `\n` e `\r` no mesmo texto.

Separação teste/código: `6042500` toca só `ProtocolClientTest.kt` (+108),
`0b19266` só `PendingQueueStore.kt` e `ProtocolClient.kt`. Mesmo padrão nos
commits da primeira rodada (`4566dd2`/`793999e` teste, `7543830` código).

Prova de vermelho refeita por mim, não aceita de segunda mão: worktree
descartável em `6042500` (commit só-de-teste), `gate.ps1 -TestsOnly` →
`14 tests completed, 4 failed`, e são exatamente os 4 novos, cada um pela razão
certa — `AssertionFailedError` nos dois de fila de arquivo (linhas 301 e 332),
`IllegalStateException` nos dois que o `error(...)` do decode derrubava (356,
376); Rust intacto (18/18 em `server.rs`). Com `0b19266` em cima, verde.

Gate completo na branch de sessão após o merge: `cargo fmt --check`,
`cargo clippy`, `cargo test` 24/24 (4 config + 2 drag + 18 server),
`tsc --noEmit` limpo, `gradle check` BUILD SUCCESSFUL,
`TEST-synchronice.protocolclient.ProtocolClientTest.xml`:
`tests="14" skipped="0" failures="0" errors="0"`.

**Uma rodada de gate em quatro falhou, e não é deste ticket.** A primeira
rodada depois do merge terminou em `gate: FALHOU` com `gradle check` BUILD
SUCCESSFUL logo acima — ou seja, a falha foi antes do Android, no lado Rust ou
no `tsc`; as três rodadas seguintes, sem nenhuma mudança, passaram. É a terceira
observação do mesmo intermitente que `CLEAN-001` já descreve (`--test server` do
`pc/src-tauri`, que este ticket não toca), e anotei lá. Registro o que aconteceu
de fato: não capturei o nome do teste dessa rodada, porque só olhei o fim da
saída — erro meu, e é exatamente a informação que `CLEAN-001` precisa. Não
seguro SYN-008 por isso: o critério 7 é sobre os testes JVM do módulo cliente,
que passaram nas quatro rodadas.

Contrato HTTP conferido de novo contra `pc/src-tauri/src/server.rs`: `POST
/items/file`, `X-File-Name`, `Authorization: Bearer`, `Content-Length` por
`setFixedLengthStreamingMode`, 201. Bate.

Escopo: critérios 1–6 seguem no bloco "Adiado — precisa do Android SDK", pela
resposta do humano de 2026-09-20, e a linha de `Débito humano` acima continua
valendo. Nada fora de `android/protocol-client/` e deste ticket mudou.

Findings que não voltam como ❌ (nenhum é do escopo reduzido, e os de Standards
são refatoração — o fluxo manda parar em ticket próprio, não consertar no
review): os quatro itens de Standards da revisão anterior seguem abertos e
foram para `CLEAN-002`, junto com a decisão de política sobre Item recusado
(401) e `503` sem Pasta, que precisa de stage 1 e de critério próprio.

Dois detalhes novos que vi lendo o código e que também estão em `CLEAN-002`,
nenhum dos dois em critério nem em caminho exercitado hoje:

- `FilePendingQueueStore.enqueue` chama `copyTo` sem rede de proteção: mandar um
  arquivo que sumiu entre a chamada e a falha de envio propaga `IOException` do
  `sendFile`, em vez de deixar Pendente ou descartar.
- Uma cópia em `<fila>.payloads/` fica órfã se o processo morrer entre `copyTo` e
  `persist()` — vazamento de disco, nada que quebre a fila.

Arquivos: `android/protocol-client/src/main/kotlin/synchronice/protocolclient/PendingItem.kt`
(novo), `PendingQueueStore.kt` (novo), `ProtocolClient.kt`,
`src/test/kotlin/synchronice/protocolclient/ProtocolClientTest.kt`.
