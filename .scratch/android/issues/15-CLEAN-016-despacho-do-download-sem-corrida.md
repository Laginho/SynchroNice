# CLEAN-016: Despacho do download sem corrida com o cancelamento
Stage: implementing
Status: ready-for-agent
Blocked by: none
Review: agent

- Primary files:
  - android/app/src/main/java/synchronice/android/MainActivity.kt (`AppState`: `inFlightFiles` e o despacho do filho em `listen`, `:448-468`; a assinatura de `listen` ganha `dispatcher: CoroutineDispatcher = Dispatchers.IO`; nada mais)
  - android/app/src/test/java/synchronice/android/ListenLoopTest.kt (um teste novo)

#### What to build

Achado da revisão final do CLEAN-013 (2026-09-27, "corrida no despacho").
Prioridade baixa: a janela vai de microssegundos a poucos ms, mas o efeito é
silencioso e só passa matando o app.

O laço de escuta põe o id em `inFlightFiles` antes de o filho que baixa o
arquivo começar (`MainActivity.kt:449-452`), e há duas janelas em que o
cancelamento do ON_PAUSE (`repeatOnLifecycle(RESUMED)`, `:971`) cai entre o
`add` e o `try` do filho:

1. `withContext(Dispatchers.Main.immediate) { inFlightFiles.add(file.id) }`
   tem garantia de cancelamento imediato na volta: se o job foi cancelado
   enquanto o bloco rodava na main, o `add` fica feito e o `withContext` lança
   `CancellationException` — o `launch` nunca acontece.
2. Um `launch` com start padrão cancelado antes de ser despachado não executa
   o corpo; o `finally` com `NonCancellable` do CLEAN-013 nunca roda.

Nos dois casos o id fica preso enquanto o processo viver: na volta, o
reanúncio do PC conta como "nada novo" para sempre, o arquivo não chega (nem
bolha há, porque o `receiveFile` nunca rodou) e o PC o mantém Pendente. Sem
perda; só some até o app ser morto.

A correção, sem ponto de suspensão entre o `add` e o registro da limpeza:

- `inFlightFiles` vira `ConcurrentHashMap.newKeySet<Long>()`. `add` e
  `remove` saem do `withContext(Dispatchers.Main.immediate)`; o
  `retryReceivedReceipts` (`:517`) e as limpezas em `acceptQr`/`unpair`
  continuam como estão (o conjunto concorrente aguenta leitura na main e
  escrita no IO).
- A limpeza sai do `finally` e vai para
  `launch { ... }.invokeOnCompletion { inFlightFiles.remove(file.id) }`: o
  handler roda quando o job completa por qualquer caminho, inclusive
  cancelado antes de começar, então a janela 2 fecha sem
  `CoroutineStart.ATOMIC` (que é `@DelicateCoroutinesApi`) e sem
  `NonCancellable`. O `catch (Exception) { delay(2_000) }` do filho fica: o
  handler só corre depois dele, então o id continua em voo os 2 s (critério 8
  do CLEAN-013). Se preferir `launch(start = CoroutineStart.ATOMIC)` com o
  `finally` de hoje, vale, com `@OptIn`; o que der o diff menor.
- `listen` ganha `dispatcher: CoroutineDispatcher = Dispatchers.IO` e usa
  `withContext(dispatcher)` na primeira linha. **É costura nova, só para o
  teste:** o `ConnectionLifecycle` e os testes existentes não passam nada.
  Sem ela a corrida não se força — a janela é o despacho do filho no pool de
  IO, e o teste precisa segurar esse despacho enquanto cancela.

Menor, sem critério e sem teste (entra se couber; o stage 3 não reabre por
ele): o `2_000` do `catch` do filho e o `1_500` da espera de lote vazio
(`:469`) estão acoplados só por comentário e já derivaram uma vez (1 s → 2 s);
duas constantes nomeadas no `companion`, a primeira definida como a segunda
mais 500 ms, resolvem. Os outros "Standards" da revisão do CLEAN-013 ficam
como estão: o `stallFirstDownload` nunca solto é o desenho daquele teste
(soltá-lo antes do `cancelAndJoin` termina o primeiro download e muda o que
ele prova); a mensagem de commit não se reescreve; o helper de preparação de
`ListenLoopTest.kt` é refatoração do arquivo, não deste ticket.

#### Acceptance criteria

1. Um laço de escuta cancelado depois de pôr um arquivo em voo e antes de o
   filho do download começar não deixa o id em `inFlightFiles`: relançar
   `listen` no mesmo `AppState` baixa o arquivo quando o PC o devolve
   (`GET /outbox/{id}/file` acontece e a bolha chega a `DELIVERED`).
2. Entre o `add` em `inFlightFiles` e o registro da limpeza
   (`invokeOnCompletion`, ou o `try` de um filho `ATOMIC`) não há chamada
   suspensa. É por construção: o critério 1 força a janela 2, e a janela 1
   fecha porque o `add` não passa mais pela main. Sem teste próprio — o
   `Dispatchers.Main` dos testes é `Unconfined` e não dispara a garantia de
   cancelamento imediato.
3. Os sete testes de `ListenLoopTest.kt` e o `ReceivedFileRestartTest` seguem
   verdes sem mudar: critérios 1–8 do CLEAN-013 mantidos (o id de um download
   que falha fica em voo 2 s; `acceptQr`/`unpair` deixam o conjunto vazio).

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

## Tests stage 2 writes (own commit, red)

Um teste novo em `ListenLoopTest.kt`,
`aLoopCancelledBeforeTheDownloadChildStartsFreesTheId`, no estilo de
`aDownloadCancelledMidwayIsResumedByTheRelaunchedLoop` (PC falso `HttpServer`
com `newCachedThreadPool`, `registerMediaProvider(app) { 1 }`,
`Dispatchers.setMain(Dispatchers.Unconfined)`, `withTimeout(5_000)`,
`AtomicInteger`). A corrida vira determinística com `StandardTestDispatcher`
(`kotlinx-coroutines-test` 1.9.0, já no classpath) como `dispatcher` do
primeiro laço: o corpo do laço roda na thread do teste dentro de
`runCurrent()` e não cede até um `delay`, então o filho fica na fila do
scheduler sem rodar enquanto o PC falso cancela o job.

- `val listens = AtomicInteger()`, `val gets = AtomicInteger()`,
  `val listening = AtomicReference<Job>()`.
- `/listen`: `n = listens.incrementAndGet()`. `n == 2` →
  `listening.get()!!.cancel()` e responde `""`. Qualquer outro →
  `{"kind":"file","id":1,"at_ms":0,"name":"a.bin","size":4}` enquanto
  `gets.get() == 0`, senão `""`.
- `/outbox/1/file`: `gets.incrementAndGet()`, 200 com `payl` (4 bytes).
  `/outbox/1/ack`: 204.
- `val loopDispatcher = StandardTestDispatcher()`; `state` pareado e com
  `knownAddress` como nos outros testes.
- `listening.set(launch(start = CoroutineStart.UNDISPATCHED) { state.listen(state.pairing!!, state.client()!!, dispatcher = loopDispatcher) })`
  — o `UNDISPATCHED` leva o laço até o `withContext(loopDispatcher)`, que
  enfileira o corpo e suspende.
- `loopDispatcher.scheduler.runCurrent()`: roda o corpo do laço na thread do
  teste: `/listen` #1 → [1] → `add` → filho enfileirado → sem `delay`
  (`startedDownload`) → `/listen` #2 → o PC falso cancela o job → o laço
  morre (no `runInterruptible`, no `withContext(Main)` seguinte ou no
  `delay`, tanto faz) → o scheduler chega ao filho, já cancelado. Depois
  `listening.get()!!.join()`.
- `assertEquals(0, gets.get())` — o filho não baixou nada; isto vale com e
  sem o fix (é o estado da corrida, não o critério).
- Relança com o dispatcher padrão:
  `val reopened = launch { state.listen(state.pairing!!, state.client()!!) }`;
  `withTimeout(5_000) { while (gets.get() < 1) delay(10) }`;
  `withTimeout(5_000) { while (state.messages.singleOrNull()?.delivery != Delivery.DELIVERED) delay(10) }`;
  `reopened.cancelAndJoin()` no `finally`.

**Vermelho hoje** (`gate.ps1 -TestsOnly`, primeiro plano):
`TimeoutCancellationException` no `gets < 1` — o filho cancelado antes de
começar não roda o `finally`, o id 1 fica em `inFlightFiles`, e o laço
relançado ignora o reanúncio a cada `/listen`. O commit vermelho já traz a
assinatura nova de `listen` (`dispatcher` com padrão, `withContext(dispatcher)`),
senão não compila — é a única mudança de `MainActivity.kt` permitida no commit
de teste. Commite o vermelho antes de rodar qualquer gate; o gate nunca em
segundo plano (AGENTS.md).

Depois, o fix em commit só de código em `MainActivity.kt`. Gate completo em
primeiro plano, verde, `Stage: to-review`. Se o `lint` reclamar do
`invokeOnCompletion` ou do `ConcurrentHashMap`, é fix pequeno dentro do
Primary files; qualquer outra falha volta como `blocked` com o nome do teste.

## Comments

- 2026-09-27 Criado pelo proxy ao responder o `Needs your call` da revisão
  final do CLEAN-013 (achado "corrida no despacho"). Independente do
  CLEAN-013 no código, que já está `done`; a verificação à mão do laço contra
  o PC real continua sendo o `Débito humano:` registrado lá.
