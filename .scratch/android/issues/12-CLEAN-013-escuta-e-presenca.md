# CLEAN-013: Escuta e presença do Android
Stage: implementing
Status: ready-for-agent
Blocked by: none
Review: agent

- Primary files:
  - android/app/src/main/java/synchronice/android/MainActivity.kt (`AppState`: `listen`, `retryReceivedReceipts`, `receiveFile`; `ConnectionLifecycle`)
  - android/app/src/main/java/synchronice/android/ReceivedFiles.kt (`publish`: sinalizar arquivo sumido)
  - android/app/src/test/java/synchronice/android/ListenLoopTest.kt (novo; vem de `7c5ccbf`, ver abaixo)
  - android/app/src/test/java/synchronice/android/ReceivedFileRestartTest.kt (só o sinal de espera de
    `realListenLoopCleansReceiptAfterAckWithoutAnotherAnnouncement`, ver "Retomada" abaixo; nenhuma asserção e
    nenhum outro teste do arquivo muda)

#### What to build

Achados na revisão do PR #7 (sessão `sweatshop/2026-09-25-2127`). São três
defeitos no laço de escuta do `AppState`, por isso vêm num ticket só. O quarto
achado original (trabalho pesado na main thread ao iniciar) saiu para o
CLEAN-014: é outra costura (`init`/`outgoingFile`), e junto os dois não cabem
numa passada de stage 2.

1. **Presença falsa durante o download.** A spec diz "A presença é essa
   conexão" (a escuta). O laço de escuta baixa cada arquivo recebido antes de
   reabrir o `GET /listen`, então durante um download grande o `HEAD /listen`
   do PC responde que não há escuta, e o chat mostra "Fora da rede" com o PC
   na rede. Além disso, um texto do PC fica esperando o download acabar.
   O download de cada arquivo passa a correr ao lado do laço, e o laço reabre
   o `GET /listen` logo depois de despachar o lote. Como o PC devolve em cada
   `GET /listen` todos os Pendentes de agora (`server.rs`, `get_listen`), um
   arquivo ainda sem ACK volta anunciado enquanto seu download corre: o laço
   guarda os ids em voo e ignora esses anúncios, e um lote sem nada novo conta
   como lote vazio (a mesma espera de 1,5 s), para não virar laço quente.
   Um download que falha não derruba a escuta: o erro aparece em
   `connectionError` como hoje, e o Item volta a ser baixado quando o PC o
   devolver.
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

#### Acceptance criteria

1. Durante o download de um arquivo recebido, a escuta continua aberta: o
   `HEAD /listen` do PC responde que há escuta, e um texto mandado pelo PC no
   meio do download aparece no chat antes de o download terminar.
2. Receber um lote de Itens não muda a presença mostrada.
3. Com um recibo cuja confirmação falha, textos novos do PC continuam chegando;
   o recibo é tentado de novo depois.
4. Um recibo cujo arquivo não existe mais é descartado, e o Item é baixado de
   novo quando o PC o devolve na escuta.
5. Um arquivo anunciado de novo enquanto seu download corre não inicia um
   segundo download, e um lote só com Itens em voo não reabre a escuta sem a
   espera de um lote vazio.
6. `ReceivedFileRestartTest.realListenLoopCleansReceiptAfterAckWithoutAnotherAnnouncement`
   continua provando o que provava — o recibo que sobrou de um `remove` falho
   é limpo pela repetição antes do primeiro `/listen` do laço reaberto, sem
   novo anúncio e sem segundo download (`gets == 1`, `copies.size == 1`) —
   com o laço agora abrindo mais de um `/listen` por download.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

## Tests stage 2 writes (own commit, red)

Os testes já existem: a tentativa 2 os commitou em `7c5ccbf` na branch
`clean-013`, que o driver apagou. O commit ainda existe como objeto. Não faça
cherry-pick (ele também traz um trecho de `SentImageTest.kt` que é do
CLEAN-014); recupere só o arquivo deste ticket:

    git show 7c5ccbf:android/app/src/test/java/synchronice/android/ListenLoopTest.kt > android/app/src/test/java/synchronice/android/ListenLoopTest.kt

Robolectric contra um PC falso, no estilo de `ReceivedFileRestartTest`. Os
quatro testes de `7c5ccbf` cobrem os critérios 1–4; dois ajustes antes de
commitar, e um deles cobre o critério 5:

- `listenReopensWhileALargeDownloadIsStillInProgress` (critérios 1 e 5): o
  handler de `/outbox/1/file` hoje solta a segunda metade do corpo quando o
  segundo `/listen` chega, e a asserção de `PENDING` corre contra o fim do
  download. Troque por um latch que o próprio teste solta depois de afirmar
  que o arquivo ainda está `PENDING`. O segundo `/listen` devolve o arquivo 1
  de novo (como o PC real faz) junto com o texto; depois de ver "meio",
  `delay(200)` e `assertEquals(1, gets)` num contador de `/outbox/1/file`.
- `receivingABatchOfItemsDoesNotChangeThePresence` (critério 2): como está.
- `aReceiptThatKeepsFailingToPublishDoesNotBlockNewTextFromArriving`
  (critério 3): como está.
- `aReceiptPointingAtADeletedFileIsDroppedAndTheItemIsDownloadedAgain`
  (critério 4): o `/listen` devolve o arquivo 99 só na primeira chamada
  (contador de chamadas), não enquanto `acks == 0`; com o laço reabrindo a
  escuta durante o download, a versão atual anuncia de novo e a asserção
  `acks == 1` fica sujeita a corrida.

Vermelho esperado (prova via `gate.ps1 -TestsOnly`): 1, 3 e 4 travam por
timeout (a escuta nunca reabre / o texto nunca chega), 2 falha a asserção de
presença. Commite o vermelho antes de rodar qualquer gate; o gate nunca em
segundo plano (AGENTS.md).

### Retomada (tentativa 4): o vermelho e o fix já existem

A tentativa 3 fez tudo acima e commitou: `eadc104` (só `ListenLoopTest.kt`,
vermelho, mais `Stage: implementing`) e `f4ec047` (o fix em `MainActivity.kt`
e `ReceivedFiles.kt`, verde nos quatro testes de `ListenLoopTest`). O driver
apagou a branch `clean-013`; os commits estão pinados como
`refs/keep/clean-013-fix` (= `f4ec047`). Não reescreva nada disso — recupere
sobre a ponta da sessão, mantendo a ficha desta versão (a de `f4ec047` tem
a pergunta antiga em `## Comments` e conflita):

    git checkout -b clean-013 sweatshop/2026-09-25-2127
    git cherry-pick eadc104
    git cherry-pick -n f4ec047
    git checkout HEAD -- .scratch/android/issues/12-CLEAN-013-escuta-e-presenca.md
    git commit -C f4ec047

Confira `git diff --stat sweatshop/2026-09-25-2127..HEAD`: dois commits,
`ListenLoopTest.kt` só no primeiro, `MainActivity.kt` e `ReceivedFiles.kt`
só no segundo, a ficha com `Stage: implementing`.

O que falta é um único teste fora deste ticket que ficou vermelho por um
sinal de espera obsoleto, não por defeito do fix:
`ReceivedFileRestartTest.realListenLoopCleansReceiptAfterAckWithoutAnotherAnnouncement`.
O ramo `listenRecovery` espera `while (listens.get() < 2)` antes de afirmar
que o recibo sumiu. No laço antigo isso significava "o laço reaberto já fez
seu primeiro `/listen`, logo `retryReceivedReceipts` já rodou". Com o laço
novo, a primeira fase do teste sozinha já deixa `listens == 2` (critério 1:
o laço reabre o `/listen` enquanto o 42 ainda baixa; o reanúncio é ignorado
pelo guarda de ids em voo), então a espera passa antes de `reopened.listen`
começar e a asserção corre contra um recibo ainda não repetido.

Mudança permitida em `ReceivedFileRestartTest.kt`, num commit próprio
(`test: ...`), sem tocar em código: tirar a foto do contador antes de
lançar o laço reaberto e esperar ele crescer —

    val listensBefore = listens.get()
    val listening = launch { reopened.listen(reopened.pairing!!, reopened.client()!!) }
    try {
        withTimeout(5_000) { while (listens.get() == listensBefore) delay(10) }

— e mais nada: as cinco asserções que seguem ficam como estão (elas são o
critério 6), a primeira fase do teste e os outros treze testes do arquivo
não mudam. O sinal preserva a prova original: `retryReceivedReceipts` corre
antes do `client.listen()` em cada volta, então o primeiro `/listen` novo
implica recibo já repetido, e o `/listen` falso devolve vazio com
`acks >= 1`, logo nenhum anúncio pode ter feito a limpeza. Sem corrida na
foto: quando a primeira fase é cancelada, o laço está parado no
`delay(1_500)` do lote sem nada novo, não no meio de um `/listen`.

Depois: `gate.ps1` completo em primeiro plano, verde, `Stage: to-review`.
Se o gate completo (fmt/lint) reclamar de algo em `MainActivity.kt` ou
`ReceivedFiles.kt`, é fix pequeno dentro do Primary files; qualquer outra
falha volta como `blocked` com o nome do teste.

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
