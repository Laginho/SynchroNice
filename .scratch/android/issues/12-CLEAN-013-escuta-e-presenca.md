# CLEAN-013: Escuta e presença do Android
Stage: to-review
Status: ready-for-agent
Blocked by: none
Review: agent

- Primary files:
  - android/app/src/main/java/synchronice/android/MainActivity.kt (`AppState`: `listen`, `retryReceivedReceipts`, `receiveFile`, `dropStaleReceipt`, `inFlightFiles`; em `acceptQr` e `unpair` só a limpeza de `inFlightFiles`; `ConnectionLifecycle`)
  - android/app/src/main/java/synchronice/android/ReceivedFiles.kt (`publish`: sinalizar arquivo sumido — já feito em `1208d55`)
  - android/app/src/test/java/synchronice/android/ListenLoopTest.kt
  - android/app/src/test/java/synchronice/android/ReceivedFileRestartTest.kt (só o sinal de espera de
    `realListenLoopCleansReceiptAfterAckWithoutAnotherAnnouncement`, já feito em `980148e`; nenhuma asserção e
    nenhum outro teste do arquivo muda)

#### What to build

Achados na revisão do PR #7 (sessão `sweatshop/2026-09-25-2127`). São três
defeitos no laço de escuta do `AppState`, por isso vêm num ticket só. O quarto
achado original (trabalho pesado na main thread ao iniciar) saiu para o
CLEAN-014: é outra costura (`init`/`outgoingFile`), e junto os dois não cabem
numa passada de stage 2.

1. **Presença falsa durante o download.** A spec diz "A presença é essa
   conexão" (a escuta). O laço de escuta baixava cada arquivo recebido antes de
   reabrir o `GET /listen`, então durante um download grande um texto do PC
   ficava esperando o download acabar, e o `HEAD /listen` do PC respondia que
   não há escuta (chat "Fora da rede" com o PC na rede).
   O download de cada arquivo passa a correr ao lado do laço, e o laço reabre
   o `GET /listen` logo depois de despachar o lote. Como o PC devolve em cada
   `GET /listen` todos os Pendentes de agora (`server.rs`, `get_listen`), um
   arquivo ainda sem ACK volta anunciado enquanto seu download corre: o laço
   guarda os ids em voo e ignora esses anúncios, e um lote sem nada novo conta
   como lote vazio (a mesma espera de 1,5 s), para não virar laço quente.
   Um download que falha não derruba a escuta: o erro aparece em
   `connectionError` como hoje, e o Item volta a ser baixado quando o PC o
   devolver.
   **A metade da presença não é deste ticket.** O PC responde o `GET /listen`
   na hora enquanto houver qualquer Pendente (`server.rs:618`; o manifesto
   inteiro, `outbox.rs:233`), e o arquivo baixando é Pendente até o ACK, então
   durante um download nenhuma escuta fica aberta, faça o Android o que fizer.
   Quem conserta o `HEAD /listen` (e o piscar da janela do PC) é o **CLEAN-015**:
   um `GET /outbox/{id}/file` em andamento passa a contar como presença no PC.
   Com ele, o sondador de presença do Android (`isListening()`, 1 s) vê 200
   durante o download sem mudar nada aqui. Este ticket cobre só o que o laço do
   Android controla: reabrir o `/listen` durante o download e o que vem abaixo.
   Fica aceito, nos dois tickets, um vão de até 1,5 s entre o fim do download e
   o `/listen` seguinte (o laço está na espera de lote sem nada novo); se
   incomodar, vira um `CLEAN-*` próprio ("acordar o laço quando um download
   termina"), não entra aqui.
2. **Pisca "Fora da rede" a cada lote.** O laço marca `online = false` logo
   depois de cada lote que a escuta devolve. Quem decide a presença é o
   `HEAD /listen`; o laço não deve mexer nela ao receber Itens.
3. **Um recibo que falha para a escuta.** Antes de cada `listen()`, o laço
   repete a confirmação dos arquivos já gravados e relança qualquer erro. Se um
   recibo falha sempre (o usuário apagou o arquivo antes do ACK), o app não
   escuta mais nada até desparear. Recibo que falha não pode parar a escuta:
   fica para a próxima tentativa, e os outros recibos do mesmo lote seguem.
   Recibo cujo arquivo não existe mais é descartado, sem ACK, e o Item volta a
   ser baixado quando o PC o devolver. "Não existe mais" é o que o `publish`
   consegue ver: o `update` de `IS_PENDING` no MediaStore devolve 0 linhas. Isso
   vale tanto na repetição dos recibos antes do `listen()` quanto quando o
   Item chega anunciado de novo com recibo `content:` gravado (o ramo
   `savedUri` do `receiveFile`): nos dois casos o recibo sai, nada é
   confirmado, e o Item é baixado de novo.

##### Passada 2 (2026-09-27): o que sobrou da revisão

A passada 1 está na branch `clean-013` e fez os três itens acima (ver "Review
stage 3"). Faltam os dois ❌ de regressão que ela introduziu e as lacunas de
teste, todos dentro do Primary files:

4. **Cancelar no meio do download prende o Item.** O `finally` do download
   (`MainActivity.kt:457-458`) tira o id de `inFlightFiles` por
   `withContext(Dispatchers.Main.immediate)`, que lança `CancellationException`
   antes do bloco quando o job já foi cancelado — e o `repeatOnLifecycle(RESUMED)`
   cancela a escuta a cada ON_PAUSE. O `AppState` sobrevive, o id fica no
   conjunto, e na volta o reanúncio conta como "nada novo" para sempre. A
   remoção do id não pode ser cancelável: `inFlightFiles` vira um conjunto
   concorrente (`ConcurrentHashMap.newKeySet<Long>()`, sem `withContext` para
   `add`/`remove`) ou o `finally` usa `NonCancellable`; o que der o diff menor.
   `acceptQr` e `unpair` limpam `inFlightFiles` junto de `receivedFiles`.
5. **Download que falha rápido vira laço quente.** O filho engole o erro e
   solta o id na hora; se a falha é local ou HTTP 5xx e mais rápida que uma
   volta de `/listen`, a escuta seguinte devolve o mesmo Pendente, o id já não
   está em voo, `startedDownload = true` e o laço reabre sem espera, em loop
   apertado. Um download que falha mantém o id em voo por 1 s antes de
   soltá-lo (`delay(1_000)` no `catch` do filho, antes do `finally`): o
   reanúncio seguinte conta como "nada novo" e o laço faz a espera de 1,5 s.
   Cancelamento durante esse `delay` continua caindo no `finally` do item 4.
6. **Menores, sem critério e sem teste** (uma linha cada; entram se couberem,
   e o stage 3 não reabre por eles): `retryReceivedReceipts` pula ids que
   estão em `inFlightFiles`; um `removeReceipt` que lança dentro do
   `catch (FileGoneException)` da repetição não pula os outros recibos (cai no
   `catch (Exception)` do mesmo laço); mensagem de asserção trocada em
   `ListenLoopTest.kt:227` (o `acks == 1` é o ACK do download novo — a mensagem
   deve dizer isso, não "must never be acknowledged"). O `dropStaleReceipt` sem
   o guarda de troca de Par fica como está: `unpair` limpa `receivedFiles` e as
   preferências, então não há o que ele corromper.

#### Acceptance criteria

1. Durante o download de um arquivo recebido, o laço não espera o download
   acabar: reabre o `GET /listen` logo depois de despachar o lote, e um texto
   mandado pelo PC no meio do download aparece no chat antes de o download
   terminar. (Contra o PC real o texto chega em até uma volta do laço, ≤ 1,5 s,
   porque o PC responde o `/listen` na hora enquanto o arquivo é Pendente.) O
   que o `HEAD /listen` do PC responde durante o download é o CLEAN-015, não
   este critério.
2. Receber um lote de Itens não muda a presença mostrada: o laço nunca escreve
   `online = true`, e só escreve `false` em erro de rede, como antes.
3. Com um recibo cuja confirmação falha, textos novos do PC continuam chegando;
   o recibo é tentado de novo antes de cada `/listen` seguinte (a segunda
   tentativa é observável no provider falso), e outro recibo do mesmo lote de
   repetição é confirmado e apagado mesmo com o primeiro falhando.
4. Um recibo cujo arquivo não existe mais é descartado, sem ACK, e o Item é
   baixado de novo — nos dois caminhos: na repetição antes do `/listen`, e no
   ramo `savedUri` do `receiveFile` (Item anunciado de novo com recibo
   `content:` gravado).
5. Um arquivo anunciado de novo enquanto seu download corre não inicia um
   segundo download, e um lote só com Itens em voo não reabre a escuta antes da
   espera de lote vazio: nenhum `/listen` novo em até 1 s depois desse lote.
6. `ReceivedFileRestartTest.realListenLoopCleansReceiptAfterAckWithoutAnotherAnnouncement`
   continua provando o que provava — o recibo que sobrou de um `remove` falho
   é limpo pela repetição antes do primeiro `/listen` do laço reaberto, sem
   novo anúncio e sem segundo download (`gets == 1`, `copies.size == 1`) —
   com o laço agora abrindo mais de um `/listen` por download.
7. Cancelar a escuta no meio de um download e relançá-la no mesmo `AppState`
   baixa o arquivo de novo quando o PC o devolve: o id sai de `inFlightFiles`
   no cancelamento. `unpair` e `acceptQr` deixam `inFlightFiles` vazio.
8. Um download que falha rápido não vira laço quente: com o PC devolvendo o
   mesmo Pendente a cada `/listen` e o download falhando na hora, em 2 s
   acontecem no máximo 3 `/listen` e no máximo 3 tentativas de download.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

## Tests stage 2 writes (own commit, red)

**Continue na branch `clean-013`.** Ela já tem a passada 1: `5405b33`
(vermelho, `ListenLoopTest.kt` com os quatro testes dos critérios 1–5),
`1208d55` (fix em `MainActivity.kt` e `ReceivedFiles.kt`), `980148e` (só o
sinal de espera do `ReceivedFileRestartTest`, critério 6), `75e13ae`
(`to-review`) e `0fb880b` (revisão). Não refaça nada disso: o vermelho→verde
dos critérios 1–6 está provado nesses commits, e o gate passou na tentativa 5.
Se a ficha com esta resposta estiver só na base da sessão, traga-a para a
branch antes de começar (`git checkout <base> -- .scratch/android/issues/12-CLEAN-013-escuta-e-presenca.md`,
commit próprio). A seção "Retomada (tentativa 4)" das versões anteriores desta
ficha já foi cumprida e saiu daqui.

Um commit só de teste (`test: ...`, com `Stage: implementing`), tudo em
`ListenLoopTest.kt`, no estilo dos quatro testes que já existem (PC falso
`HttpServer`, `Dispatchers.setMain(Dispatchers.Unconfined)`,
`withTimeout(5_000)`, contadores `AtomicInteger`). Estender teste existente
vale mais que escrever outro. Os dois primeiros são vermelhos hoje; os três
seguintes cobrem lacunas de código que já está certo, então são verdes — entram
no mesmo commit, e o corpo do commit nomeia, para cada um, a mutação de uma
linha que o avermelha (rode a mutação localmente para conferir; não a commite).

- `aDownloadCancelledMidwayIsResumedByTheRelaunchedLoop` (critério 7).
  `/listen` devolve `{"kind":"file","id":1,...}` enquanto `gets < 2`, depois
  `""`. `/outbox/1/file` no primeiro `GET` manda metade do corpo e segura num
  latch que o teste nunca solta; no segundo, o corpo inteiro. Lança
  `state.listen(...)`, espera `gets == 1`, `listening.cancelAndJoin()`, lança
  `state.listen(...)` de novo no mesmo `state`, espera `gets == 2` e a bolha
  `DELIVERED`. **Vermelho hoje:** timeout — o `finally` não roda no
  cancelamento, o id 1 fica em `inFlightFiles`, o reanúncio é ignorado.
- `aDownloadThatFailsFastDoesNotTurnIntoAHotLoop` (critério 8). `/listen`
  devolve sempre `{"kind":"file","id":5,...}`; `/outbox/5/file` responde 500
  (se isso não lançar rápido de dentro de `receiveFile`, faça o `insert` do
  provider falso lançar). Lança o laço, `delay(2_000)`, afirma
  `listens.get() <= 3` e `gets.get() <= 3`. **Vermelho hoje:** dezenas de
  `/listen` em 2 s.
- Critério 5, segunda metade: estender
  `listenReopensWhileALargeDownloadIsStillInProgress`. O terceiro `/listen`
  devolve o arquivo 1 de novo (ainda em voo, o latch segue fechado) em vez de
  `""`; depois de ver "meio", `delay(1_000)` e afirmar `listens.get() == 3`
  (o quarto só vem depois dos 1,5 s). Só então soltar o latch e seguir com as
  asserções que já existem. Verde hoje; mutação: tirar `!startedDownload &&`
  de `MainActivity.kt:462` não basta — a mutação é trocar o `delay(1_500)`
  dessa linha por nada (o lote em voo reabre na hora e `listens` passa de 3).
- Critério 3, segunda metade: estender
  `aReceiptThatKeepsFailingToPublishDoesNotBlockNewTextFromArriving`. Dois
  recibos `content:` gravados, 98 e 99; o provider falso lança no `update` do
  98 (contando as chamadas) e devolve 1 no do 99; `/outbox/99/ack` conta.
  `/listen` devolve o texto na primeira chamada e `""` depois. Afirmar, depois
  de `listens >= 2`: recibo 98 ainda gravado e `update` do 98 chamado ao
  menos 2 vezes (tentado de novo), recibo 99 apagado e `acks99 == 1` (os
  outros do lote seguem). Verde hoje; mutação: trocar o `catch (error:
  Exception)` de `retryReceivedReceipts` (`:525`) por `throw error`.
- Critério 4, ramo `savedUri`: novo
  `aReAnnouncedItemWhoseSavedFileIsGoneIsDownloadedAgain`, sem laço, chamando
  `state.receiveFile(item42, state.client()!!)` direto (como o
  `ReceivedFileRestartTest` faz). Recibo `content://media/42` gravado antes;
  o provider devolve 0 na primeira chamada de `update` e 1 depois;
  `/outbox/42/file` com `"payload"` e `/outbox/42/ack` 204, ambos contando.
  Primeira chamada: `assertFailsWith<FileGoneException>`, recibo apagado,
  bolha `FAILED` com `uri == null`, `gets == 0`, `acks == 0`. Segunda chamada
  (o reanúncio): `gets == 1`, `acks == 1`, bolha `DELIVERED`, recibo apagado.
  Verde hoje; mutação: tirar o `try/catch (FileGoneException)` de
  `MainActivity.kt:565-570` (a primeira chamada então deixa o recibo).
- No mesmo commit, a mensagem da asserção de `ListenLoopTest.kt:227`.

Vermelho esperado (prova via `gate.ps1 -TestsOnly`, primeiro plano): os dois
primeiros falham como descrito; os outros três passam; os quatro testes
antigos do arquivo e o `ReceivedFileRestartTest` inteiro continuam verdes.
Commite o vermelho antes de rodar qualquer gate; o gate nunca em segundo
plano (AGENTS.md).

Depois, o fix em commit(s) só de código em `MainActivity.kt`: itens 4 e 5 do
"What to build" (e os menores do item 6 que couberem). Em seguida `gate.ps1`
completo em primeiro plano, verde, `Stage: to-review`. Se o gate completo
(fmt/lint) reclamar de algo em `MainActivity.kt`, é fix pequeno dentro do
Primary files; qualquer outra falha volta como `blocked` com o nome do teste.

## Review stage 3 (2026-09-27)

Verdict: Needs your call: o critério 1 não é atendido contra o PC real, e
nenhuma mudança dentro do Primary files o atende — o stage 1 precisa escolher
a costura (ou reescrever o critério) antes de o ticket voltar ao stage 2.

Revisado `sweatshop/2026-09-25-2127...75e13ae` (`5405b33`, `1208d55`,
`980148e`). Separação teste-primeiro correta: `5405b33` só `ListenLoopTest.kt`
e a ficha, `1208d55` só `MainActivity.kt`/`ReceivedFiles.kt`, `980148e` só o
sinal de espera permitido em `ReceivedFileRestartTest.kt`. Gate verde conforme
a tentativa 5 (não rodado de novo: não há merge).

- ❌ **Critério 1, metade da presença.** Contra o PC real a escuta não fica
  aberta durante o download. `get_listen` responde na hora quando há Pendentes
  (`pc/src-tauri/src/server.rs:618`), e o arquivo baixando é Pendente até o ACK
  (`outbox.rs:233` devolve o manifesto inteiro). O laço novo faz então:
  `/listen` → [42] → dispara o download → reabre → [42] na hora (em voo,
  ignorado) → `delay(1_500)` sem conexão aberta → repete. A `Presence` do PC é
  um contador de conexões vivas (`server.rs:166`), então fica em 1 só pelos
  poucos ms de cada volta; o `isListening()` que o Android sonda a cada 1 s
  (`MainActivity.kt:985`) vê 204 quase sempre e o chat segue "Fora da rede" — o
  sintoma do item 1 do "What to build". E o `on_change` do PC (`lib.rs:254`)
  agora pisca presente/ausente a cada ~1,5 s durante o download, onde antes
  ficava ausente estável. A metade do texto está atendida (chega em até 1,5 s,
  antes de o download acabar). O teste não pega: o PC falso responde `""` na
  hora da 3ª escuta em diante e nada consulta presença. O `Débito humano:` do
  critério 1 não é débito — o código responde, e a resposta é não. O próprio
  ticket se contradiz: "um lote sem nada novo conta como lote vazio (a mesma
  espera de 1,5 s)" contra um PC que nunca bloqueia enquanto há Pendente.
- ❌ **Regressão: pausar no meio do download trava o arquivo para sempre.** O
  `finally { withContext(Dispatchers.Main.immediate) { inFlightFiles.remove(file.id) } }`
  (`MainActivity.kt:457-458`) não roda no cancelamento: `withContext` faz
  `ensureActive()` antes do bloco e lança. `repeatOnLifecycle(RESUMED)`
  (`:964`) cancela a escuta a cada ON_PAUSE, e o `AppState` sobrevive
  (`:103`), então na volta `inFlightFiles.add` devolve `false` para esse id, o
  lote conta como "nada novo" e o Item nunca mais é baixado até a Activity ser
  recriada. Antes, a próxima escuta retomava o download pelo recibo
  `writing:`. Fere "o Item volta a ser baixado quando o PC o devolver".
  `inFlightFiles` também não é limpo em `unpair`/`acceptQr` (`:231`, `:274`),
  ao contrário de `receivedIds`/`receivedFiles`. Precisa de teste que cancele e
  relance o laço.
- ❌ **Regressão: download que falha rápido vira laço quente.** Antes, um
  `receiveFile` que lançava caía no `catch` externo e esperava 1 s ou 1,5 s.
  Agora o filho engole o erro e solta o id; se a falha é local e mais rápida
  que uma volta de `/listen` (Pasta revogada → `createDocument` lança; `insert`
  do MediaStore devolve `null`), a escuta seguinte devolve o mesmo Pendente, o
  id já não está em voo, `startedDownload = true` e o laço reabre sem espera:
  `/listen` + tentativa de download em loop apertado, cada volta piscando a
  presença do PC. O "What to build" pede o contrário ("para não virar laço
  quente"). Precisa de teste.
- Lacunas de teste (nenhuma reprova sozinha; o código está certo): critério 5,
  segunda metade (tirar a condição de em voo em `:462` não quebra teste
  nenhum); critério 4, ramo `savedUri` do `receiveFile` (`:565-570`) nunca é
  exercitado, porque a repetição apaga o recibo antes do primeiro `/listen`;
  critério 3, "tentado de novo depois" e "os outros recibos do mesmo lote
  seguem" (um recibo só, sem contar tentativas).
- Menores, dentro do Primary files: `retryReceivedReceipts` não pula ids em
  `inFlightFiles`, então entre o `onSaved` e o ACK do download uma volta pode
  publicar, confirmar e apagar o mesmo recibo (inofensivo hoje: ACK é
  idempotente, `server.rs:45`); se `removeReceipt` lança dentro do
  `catch (FileGoneException)` (`:523-524`), o erro escapa, pula os outros
  recibos e cai no `IOException` do laço, que não chega ao `listen()` (item 3);
  `dropStaleReceipt` em `:568` contorna o guarda de troca de Par de
  `update`/`saveReceipt`; mensagem de asserção invertida em
  `ListenLoopTest.kt:227` (afirma `acks == 1`, que é o ACK do download novo,
  dizendo "must never be acknowledged"); um 401 durante o download agora é
  engolido pelo filho (`:455`) — a próxima escuta ainda despareia.
- ✅ Critérios 2 e 6. Critérios 3, 4 e 5 atendidos no código.
- Decisões do proxy nesta ficha: "dividir" (achado 4 virou CLEAN-014) e "(a)"
  (`ReceivedFileRestartTest.kt` no Primary files só para o sinal de espera).

## Comments

- 2026-09-27 Criado a partir da revisão do PR #7 (Spec (c) e Standards, bug 3).
  O stage 1 foi feito na sessão interativa.

- 2026-09-27 Attempt 1 failed: exit 0. Log tail: Waiting for the gate run to finish — will resume automatically when it completes or at the next scheduled check.

- 2026-09-27 Attempt 2 failed: timeout; blocked after two attempts. Log tail:

- 2026-09-27 Proxy decided: dividir; este ticket fica com a escuta (critérios 1–4, mais o 5 novo de anúncio repetido em voo) e reaproveita `ListenLoopTest.kt` de `7c5ccbf`; o achado 4 (prévia/init na main) vira CLEAN-014 — duas costuras num ticket é o que o `ticket-flow` manda devolver ao stage 1, e a tentativa 2 gastou os 45 min escrevendo 264 linhas de teste e provando o vermelho, sem chegar ao fix; com os testes prontos e a forma do laço (download ao lado, ids em voo, lote sem nada novo = vazio) já descrita, a próxima passada cabe.
- 2026-09-27 Nota para o stage 2: `ReceivedFileRestartTest` (`update` do provider falso sempre devolve 1; `/listen` devolve o 42 enquanto `acks == 0`) continua valendo com o laço concorrente só se o guarda de ids em voo existir — sem ele, o teste `realListenLoopCleansReceiptAfterAckWithoutAnotherAnnouncement` vê dois downloads.
- Débito humano: critério 1 contra o PC real — com um arquivo grande chegando, o chat no celular segue "Na rede" e um texto do PC aparece no meio do download. O teste prova o mesmo contra o PC falso (a escuta reabre durante o download), não contra o `HEAD /listen` do PC.

- 2026-09-27 Attempt 3 stopped to ask: Blocked. Fix is implemented and committed on `clean-013`, but it can't reach `to-review` cleanly. /  / **What's done:** `AppState.listen()` now runs file downloads concurrently, reopens `/listen` immediately instead of blocking, ignores in-flight files re-announced mid-download, stops flipping `online` on item receipt, and no longer lets one failing receipt block the whole listen loop. `ReceivedFiles.publish()` distinguishes "file gone" (0 rows) via a new `FileGoneException`, dropping stale receipts without an ACK. All four `ListenLoopTest` cases (criteria 1–5) proved red→green. /  / **Why blocked:** `gate.ps1 -TestsOnly` has exactly one remaining failure — `ReceivedFileRestartTest.realListenLoopCleansReceiptAfterAckWithoutAnotherAnnouncement`, a file **not** in this ticket's Primary files. I verified with debug output that there's no double-download bug (`gets=1, acks=1, copies=1`) — the test's own `listens.get() < 2` gate is just a stale assumption from the old blocking design; criterion 1 explicitly requires a second `/listen` call while a download is still in flight, so `listens` reaches 2 before the test's second phase even starts. /  / No `.claude/agents/proxy.md` exists in this repo to decide for me, so per `ticket-flow` I set `Stage: blocked` and stopped rather than editing a file outside Primary files. The question and options (widen Primary files vs. spin off a `CLEAN-*` ticket vs. other) are recorded under `## Comments` in the ticket.
- 2026-09-27 Attempt 3 (pergunta, como registrada em `f4ec047`): esse teste espera no máximo uma chamada a `/listen` por ciclo de download (`while (listens.get() < 2) delay(10)` usado como proxy de "a escuta reaberta processou o recibo pendente"). A nota desta ficha ("continua valendo... só se o guarda de ids em voo existir") está incorreta: medido com prints de debug (revertidos, não committed), mesmo com o guarda funcionando (`gets=1, acks=1, copies=1`, nenhum segundo download), a primeira metade do teste sozinha já deixa `listens=2` antes de `reopened.listen()` sequer começar — o laço reabre o `GET /listen` imediatamente ao despachar um download novo (critério 1), então dois `/listen` acontecem enquanto o primeiro download ainda está em voo. Opções: (a) ampliar o Primary files para incluir a correção do sinal `listens.get() < 2` em `ReceivedFileRestartTest.kt` (só o sinal de sincronização, não o comportamento); (b) abrir a correção como ticket `CLEAN-*` à parte, deixando este com o gate vermelho; (c) outra leitura.
- 2026-09-27 Proxy decided: (a), `ReceivedFileRestartTest.kt` entra no Primary files só para trocar o sinal de espera desse teste por uma foto de `listens` tirada antes de lançar o laço reaberto (critério 6, commit `test:` próprio, asserções intactas), e a tentativa 4 retoma `eadc104` + `f4ec047` por cherry-pick sobre a sessão em vez de refazer — conferi o teste antigo contra o diff de `f4ec047`: a suposição é obsoleta mesmo (`listens < 2` codificava "um `/listen` por download", que o critério 1 proíbe; a fase 1 do teste já chega a 2 com o reanúncio ignorado pelo guarda, e nenhum segundo download ocorre), então não há bug no fix, um ticket à parte deixaria este com gate vermelho pelo que é uma mudança de duas linhas num teste, e a nota anterior do proxy estava pela metade: o guarda é necessário (sem ele o teste veria dois downloads) mas não suficiente.

- 2026-09-27 Attempt 3 failed: exit 0; blocked after two attempts. Log tail: The gate command (`powershell -File .\gate.ps1`) needs your approval to run — it keeps coming back "requires approval" rather than prompting through. Recovery is otherwise complete on branch `clean-013`: /  / - `61342b4` test: red (`ListenLoopTest.kt`, 4 cases) / - `859e8d7` fix: `MainActivity.kt` / `ReceivedFiles.kt` / - `5a522d7` test: `ReceivedFileRestartTest.kt` signal fix (proxy-decided, own commit) /  / Can you approve the gate run, or run `powershell -ExecutionPolicy Bypass -File .\gate.ps1` yourself and paste the result? Once it's green I'll set `Stage: to-review` and commit.

- 2026-09-27 Foreman: a tentativa 4 falhou pelo ambiente, não pelo ticket. A recuperação e o fix ficaram prontos (`61342b4` vermelho, `859e8d7` fix, `5a522d7` o ajuste do `ReceivedFileRestartTest`, pinados em `refs/keep/clean-013-a4`), mas o gate foi negado: a sessão o chamou pela ferramenta PowerShell e por `powershell.exe` no Bash, e nenhum dos dois está no allowlist do driver. Só passa a ferramenta **Bash** com o comando exato `powershell -ExecutionPolicy Bypass -File .\gate.ps1` (ou `... -TestsOnly`), primeiro token `powershell`, sem `.exe`, sem `cd ... &&` na frente. Retomada: `git checkout -b clean-013 5a522d7`, `git rebase sweatshop/2026-09-25-2127` (o CLEAN-014 mergeou mudanças no `AppState` depois; resolva conflito, se houver, mantendo os dois), rode o gate completo e siga para `to-review`. Não refaça o vermelho: a prova vermelho→verde já está nos commits da tentativa 3/4.

- 2026-09-27 Tentativa 5: recuperado `refs/keep/clean-013-a4` (`5a522d7`) em `clean-013`, rebase limpo sobre `sweatshop/2026-09-25-2127` sem conflito (CLEAN-014 não toca as mesmas linhas). Gate completo (`powershell -ExecutionPolicy Bypass -File .\gate.ps1`, ferramenta Bash, primeiro token `powershell`) rodou em primeiro plano e passou: cargo fmt/clippy/test (pc) verde, tsc + node --test (pc/ui) 32/32, `gradle check` (android) BUILD SUCCESSFUL incluindo `testDebugUnitTest` e `lintDebug`. `Stage: to-review`.

- 2026-09-27 Stage 3: `blocked`, pergunta para o stage 1. O critério 1 pede
  que o `HEAD /listen` do PC responda que há escuta durante o download, mas o
  PC responde o `GET /listen` na hora enquanto houver Pendente, então nada no
  Android mantém uma escuta aberta (ver "Review stage 3"). Qual caminho?
  (a) No PC, um `GET /outbox/{id}/file` em andamento também conta como
  presença (`server.rs`, `get_outbox_file` entra na `Presence` como o
  `get_listen`): o critério 1 fica como está, `server.rs` entra no Primary
  files com teste do lado do PC, e a espera de 1,5 s fica só para o texto.
  (b) No Android, o sondador de presença trata download em voo como presença
  (`online = active || inFlightFiles.isNotEmpty()`): cabe no Primary files,
  mas conserta só o chat do celular — o `HEAD` e o piscar do PC continuam, e o
  critério 1 tem de ser reescrito para dizer isso. (c) Outra leitura.
  Qualquer que seja a escolha, os dois ❌ de regressão (id preso em
  `inFlightFiles` no cancelamento; laço quente em download que falha rápido) e
  as lacunas de teste listadas cabem no Primary files atual e devem entrar em
  "Tests stage 2 writes" na mesma passada.

- 2026-09-27 Proxy decided: (a), mas como ticket próprio — **CLEAN-015** (`14-CLEAN-015-download-conta-como-presenca-no-pc.md`): um `GET /outbox/{id}/file` em andamento entra na `Presence` do PC, com o guarda vivo pelo corpo da resposta (o handler devolve antes de o hyper escrever os bytes, então entrar só no handler não cobriria o download). Este ticket fica só com o Android: critério 1 reescrito para a metade que o laço controla (reabrir o `/listen` e entregar o texto durante o download), critérios 7 e 8 para as duas regressões, critérios 3, 4 e 5 ampliados para as lacunas, tudo em `ListenLoopTest.kt`; sem a opção (b). Por quê: conferi o código em `0fb880b` e a revisão está certa — `get_listen` devolve o manifesto inteiro na hora enquanto houver Pendente (`server.rs:618`, `outbox.rs:237`) e a `Presence` conta só guardas de `get_listen`, então nenhuma mudança no Android mantém escuta aberta durante um download; (a) é a correção na raiz (uma conexão viva do celular com a fila de saída é presença, seja `/listen` ou o próprio arquivo), conserta com uma mudança os dois sintomas (janela do PC piscando e chat do celular "Fora da rede", porque o sondador do Android só lê o `HEAD`), enquanto (b) criaria uma segunda fonte de presença no celular, contra o item 2 deste mesmo ticket, e deixaria o PC piscando; e `server.rs` é a costura 1 da spec, outra costura que o `ticket-flow` manda separar — este ticket já custou sete etapas, então a passada de stage 2 aqui é só o que sobrou da revisão (duas linhas de fix e cinco testes, três deles verdes por mutação), e o CLEAN-015 é umas dez linhas de Rust com um teste de integração, independente deste. O vão de até 1,5 s entre o fim de um download e o `/listen` seguinte fica aceito nos dois tickets (era ausente estável o download inteiro antes); se incomodar, é um `CLEAN-*` novo, não uma reabertura.
- 2026-09-27 Passada 2: `19d85a7` vermelho (`aDownloadCancelledMidwayIsResumedByTheRelaunchedLoop`
  critério 7, `aDownloadThatFailsFastDoesNotTurnIntoAHotLoop` critério 8 — este só ficou
  vermelho de forma confiável depois de o /listen falso passar a esperar um pouco após o
  primeiro download chegar ao servidor, senão a espera de até 2 s do resolvedor de mDNS
  falso mascarava o laço quente; medido: 9 `/listen` em 2 s sem o fix — mais as extensões
  dos critérios 3 e 5 e o novo teste do critério 4, todos verdes hoje) e `1fc2f4e` fix
  (`inFlightFiles` sobrevive ao cancelamento via `NonCancellable` no `finally`; download que
  falha rápido segura o id em voo por 1 s; `acceptQr`/`unpair` limpam `inFlightFiles`;
  `retryReceivedReceipts` pula ids em voo). Gate completo verde (`cargo fmt`/`clippy`/`test`,
  `tsc`, `node --test`, `gradle check` incluindo `lintDebug`). Não fiz o menor de
  `removeReceipt` lançando dentro do `catch (FileGoneException)` (reestruturação de
  try/catch sem teste cobrindo, não cabia na mesma passada). `Stage: to-review`.

- Débito humano: celular real contra PC real, depois de CLEAN-013 e CLEAN-015 mergeados — mandar um arquivo grande do PC, conferir que a janela do PC fica "presente" e o chat do celular "Na rede" durante o download inteiro (um piscar de ≤ 1,5 s no fim é o aceito), e que um texto mandado do PC no meio aparece no celular antes de a barra de progresso terminar; pausar o app no meio do download (Home) e voltar, conferir que o arquivo termina de chegar. Os testes provam cada metade contra um PC falso ou um celular falso, nunca os dois reais juntos.

- 2026-09-27 Review ended at reviewing (timeout); branch clean-013 holds the review; left for a human

- 2026-09-27 Foreman: a revisão das 15:31 estourou os 20 min, mas antes disso já tinha feito o conserto pequeno `92c4d62` (id de download que falhou fica em voo 2 s) e mergeado `clean-013` na sessão (`16c90be`, 15:51:29), sem chegar a gravar o veredito nem `Stage: done`. O log dela está vazio, então não se sabe se o gate rodou depois de `92c4d62`. `Stage: to-review` de novo: a próxima revisão confere o código já mergeado na sessão, roda o gate completo em primeiro plano (ferramenta Bash, comando exato `powershell -ExecutionPolicy Bypass -File .\gate.ps1`) e fecha. Não reabra pela passada 1 ou 2 já aprovada; o que faltar é conserto pequeno ou `CLEAN-*` novo.
