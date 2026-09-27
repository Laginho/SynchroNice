# CLEAN-017: A entrega espera a prévia dos Pendentes de imagem
Stage: to-implement
Status: ready-for-agent
Blocked by: none
Review: agent

- Primary files:
  - android/app/src/main/java/synchronice/android/MainActivity.kt (`AppState`: `init`, `schedulePreviewDecode`, `retryPending`; nada mais)
  - android/app/src/test/java/synchronice/android/SentImageTest.kt (dois testes novos)

#### What to build

Achado da revisão do CLEAN-014 (2026-09-27, "possível regressão").
Prioridade média: é só visual, mas aparece justamente no caso que o CLEAN-014
trata (muitas fotos Pendentes, rede rápida), e antes dele não acontecia.

O `Application.onCreate` constrói o `AppState` e chama `scheduleDelivery` em
seguida. O `init` manda a prévia de cada Pendente de imagem para o
`previewExecutor` (uma thread, uma foto por vez); o `DeliveryWorker` chama
`retryPending`, e a cada envio confirmado o `PendingQueueStore.dequeueFirst()`
apaga o arquivo da fila (`PendingQueueStore.kt:79`). Se o worker envia a foto
k antes de o executor chegar nela, `decodePreview` pega `IOException` e
devolve `null`: a bolha fica `DELIVERED` sem prévia até o fim da sessão. Numa
LAN rápida o envio de uma foto (~100 ms) é mais rápido que a decodificação
(~100–300 ms no celular), então com 20 fotos o worker passa o executor logo
nas primeiras.

Correção: **a entrega espera a prévia.** O `init` guarda, para cada Pendente
de imagem que agendou, um `CompletableDeferred<Unit>` (lista `val` preenchida
só no `init`, lida depois), e `schedulePreviewDecode` o completa num
`finally` — completa também quando a decodificação falha e devolve `null`,
senão uma imagem corrompida travaria a entrega. `retryPending` faz
`previewsPending.forEach { it.await() }` **antes** de tomar o
`outboundMutex` (para não segurar `sendText`/`queueFile` durante a
decodificação) e só então chama `currentClient.retryPending(...)`. Suspensão
cancelável: um worker parado no meio sai limpo.

Por que este desenho e não o outro que a revisão citou (abrir o descritor do
arquivo no construtor e decodificar dele): esse depende de `unlink` com FD
aberto manter o inode — vale no Android, mas o gate roda no Windows, onde o
`File.delete()` de um arquivo aberto pela JVM falha em silêncio (sem
`FILE_SHARE_DELETE`), e o teste ficaria verde pelo motivo errado. Esperar a
prévia é testável igual nos dois sistemas com a costura que o CLEAN-014 já
abriu (`previewExecutor` injetado).

Custo aceito: a entrega começa depois de todas as prévias (N × ~150 ms; 20
fotos ≈ 3 s). O worker já é segundo plano e nasce dezenas a centenas de ms
depois do `onCreate`. `queueFile` não muda: decodifica síncrono em IO antes de
enfileirar, sem corrida. ponytail: espera todas; por item se a demora
incomodar.

Dos "Standards" da revisão do CLEAN-014 nada entra aqui: a duplicação
`decodeNow`/`startsWith("image/")` a própria revisão deixou ficar; o executor
nunca desligado é uma thread por processo; o helper de preparação do teste é
refatoração do arquivo, não deste ticket. Se os testes novos repetirem a
preparação, é o preço; um helper entra se couber e o stage 3 não reabre por
ele.

#### Acceptance criteria

1. Com um Pendente de imagem cuja prévia ainda não decodificou,
   `retryPending` não manda nada ao PC: nenhum `POST /items/file` chega ao PC
   falso enquanto a fila do executor não é drenada (observado por 500 ms, com
   o endereço do PC já resolvido).
2. Drenada a fila, a entrega segue: o `POST /items/file` chega, a mensagem
   fica `DELIVERED`, o payload some do disco, e a bolha tem a prévia com o
   conteúdo da imagem (vermelho em (0,0)).
3. Um Pendente de imagem cujo arquivo não decodifica (nome `.png`, conteúdo
   lixo) não segura a entrega: `retryPending` termina, a mensagem fica
   `DELIVERED` com `preview == null`.
4. `sentImageSurvivesDeliveryOnlyInTheSessionIncludingRestoredPending` e
   `previewForAPendingImageIsDecodedAfterConstructionNotDuringIt` seguem
   verdes sem mudar.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

## Tests stage 2 writes (own commit, red)

Dois testes novos em `SentImageTest.kt`, no estilo de
`previewForAPendingImageIsDecodedAfterConstructionNotDuringIt` (mesma
preparação: `WorkManagerTestInitHelper`, `HttpServer`,
`Dispatchers.setMain(Dispatchers.Unconfined)`, PNG vermelho 64x64 enfileirado
por um `seed` e `original.delete()`, depois
`restored = AppState(context, previewExecutor = Executor { queuedPreviewWork.addLast(it) })`).
O `/items/file` conta num `AtomicInteger uploads` e responde 201, como no
primeiro teste do arquivo.

**Armadilha de tempo:** o `NsdInstanceResolver` espera até 2 s pelo mDNS
falso na primeira resolução e guarda o resultado por 3 s. Sem esquentar esse
cache, o `uploads == 0` depois de 500 ms fica verde sem o fix (o worker ainda
está resolvendo). Antes de lançar `retryPending`, chame
`restored.resolver.resolve("synchronice-1234abcd")` uma vez (pode levar 2 s;
devolve `null` e o cliente cai no `knownAddress`, na hora, pelos 3 s
seguintes).

- `deliveryWaitsForThePreviewOfAPendingImage` (critérios 1 e 2). Esquenta o
  cache; `val delivery = launch(Dispatchers.IO) { restored.retryPending() }`;
  `delay(500)`; `assertEquals(0, uploads.get(), "delivery must wait for the preview")`;
  `assertTrue(delivery.isActive)`; drena `queuedPreviewWork` na thread do
  teste; `withTimeout(5_000) { delivery.join() }`; `assertEquals(1, uploads.get())`;
  `DELIVERED`; `assertFalse(restored.hasPending())`; prévia não nula e
  vermelha em (0,0); nenhum arquivo além de `pending-items.txt` sobrou em
  `filesDir` (a mesma asserção do teste existente).
- `anUndecodablePendingImageDoesNotHoldDelivery` (critério 3). O `seed`
  enfileira `broken.png` escrito com `"nao e png".toByteArray()`; `restored`
  com o executor que enfileira; esquenta o cache;
  `val delivery = launch(Dispatchers.IO) { restored.retryPending() }`; drena
  a fila; `withTimeout(5_000) { delivery.join() }`; `DELIVERED`,
  `assertNull(preview)`, `uploads == 1`.

**Vermelho hoje** (`gate.ps1 -TestsOnly`, primeiro plano): o primeiro falha
na `assertEquals(0, uploads.get())` — o worker envia sem esperar — e, se por
acaso passasse dali, na prévia nula depois de drenar (o `dequeueFirst` já
apagou o arquivo). O segundo é verde hoje (não há espera para travar) e entra
no mesmo commit como guarda contra um fix que espere sem `finally`: o corpo
do commit nomeia a mutação que o avermelha (completar o deferred só quando
`preview != null`; rode local, não commite). Commite o vermelho antes de rodar
qualquer gate; o gate nunca em segundo plano (AGENTS.md).

Depois, o fix em commit só de código em `MainActivity.kt`. Gate completo em
primeiro plano, verde, `Stage: to-review`. Qualquer falha fora do Primary
files volta como `blocked` com o nome do teste.

## Comments

- 2026-09-27 Criado pelo proxy ao responder o `Needs your call` da revisão do
  CLEAN-014 (achado "possível regressão"). Independente do CLEAN-014 no
  código, que já está `done`; o ANR com muitas fotos num celular real
  continua sendo o `Débito humano:` registrado lá.
