# CLEAN-013: Escuta e presença do Android
Stage: to-implement
Status: ready-for-agent
Blocked by: none
Review: agent

- Primary files:
  - android/app/src/main/java/synchronice/android/MainActivity.kt (`AppState`: `listen`, `retryReceivedReceipts`, `receiveFile`; `ConnectionLifecycle`)
  - android/app/src/main/java/synchronice/android/ReceivedFiles.kt (`publish`: sinalizar arquivo sumido)
  - android/app/src/test/java/synchronice/android/ListenLoopTest.kt (novo; vem de `7c5ccbf`, ver abaixo)

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

## Comments

- 2026-09-27 Criado a partir da revisão do PR #7 (Spec (c) e Standards, bug 3).
  O stage 1 foi feito na sessão interativa.

- 2026-09-27 Attempt 1 failed: exit 0. Log tail: Waiting for the gate run to finish — will resume automatically when it completes or at the next scheduled check.

- 2026-09-27 Attempt 2 failed: timeout; blocked after two attempts. Log tail:

- 2026-09-27 Proxy decided: dividir; este ticket fica com a escuta (critérios 1–4, mais o 5 novo de anúncio repetido em voo) e reaproveita `ListenLoopTest.kt` de `7c5ccbf`; o achado 4 (prévia/init na main) vira CLEAN-014 — duas costuras num ticket é o que o `ticket-flow` manda devolver ao stage 1, e a tentativa 2 gastou os 45 min escrevendo 264 linhas de teste e provando o vermelho, sem chegar ao fix; com os testes prontos e a forma do laço (download ao lado, ids em voo, lote sem nada novo = vazio) já descrita, a próxima passada cabe.
- 2026-09-27 Nota para o stage 2: `ReceivedFileRestartTest` (`update` do provider falso sempre devolve 1; `/listen` devolve o 42 enquanto `acks == 0`) continua valendo com o laço concorrente só se o guarda de ids em voo existir — sem ele, o teste `realListenLoopCleansReceiptAfterAckWithoutAnotherAnnouncement` vê dois downloads.
- Débito humano: critério 1 contra o PC real — com um arquivo grande chegando, o chat no celular segue "Na rede" e um texto do PC aparece no meio do download. O teste prova o mesmo contra o PC falso (a escuta reabre durante o download), não contra o `HEAD /listen` do PC.
