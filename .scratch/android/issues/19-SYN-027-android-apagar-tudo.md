# SYN-027: Apagar tudo no celular, cliente do protocolo e fila
Stage: reviewing
Status: ready-for-agent
Blocked by: SYN-019
Review: agent
Difficulty: normal

- Primary files:
  - android/protocol-client/src/main/kotlin/synchronice/protocolclient/ProtocolClient.kt (linha `delete` do `/listen`, `POST /items/delete`, tirar Pendentes listados, id do Item devolvido ao app)
  - android/protocol-client/src/main/kotlin/synchronice/protocolclient/PendingItem.kt
  - android/protocol-client/src/main/kotlin/synchronice/protocolclient/PendingQueueStore.kt (linha nova da exclusão)
  - android/protocol-client/src/main/kotlin/synchronice/protocolclient/Item.kt
  - android/protocol-client/src/main/kotlin/synchronice/protocolclient/FlatJson.kt (as listas `do_pc` e `do_celular`; hoje o parser recusa listas)
  - android/app/src/main/java/synchronice/android/MainActivity.kt (só o necessário para compilar com os tipos novos: a exclusão na fila não vira bolha ao carregar a fila, e a vinda do `/listen` é ignorada, sem ack; o resto do app é do SYN-029)
  - android/protocol-client/src/test/kotlin/synchronice/protocolclient/ProtocolClientTest.kt
  - android/protocol-client/src/test/kotlin/synchronice/protocolclient/PendingQueueStoreTest.kt

#### What to build

O cliente do protocolo e a fila do Apagar tudo no celular, com o protocolo
definido no SYN-019. Leia as decisões de lá, principalmente o formato
`{"do_pc":[...],"do_celular":[...]}`, a rota `POST /items/delete` e a linha
`kind:"delete"` do `/listen`. O app (🗑, Desfazer, apagar os arquivos, aplicar
a exclusão vinda do PC) é o SYN-029, que usa o que este ticket expõe.

- **Exclusão vinda do PC.** O `/listen` entrega ao app uma exclusão com
  `do_pc` e `do_celular`, ao lado dos Itens de sempre, e o cliente sabe mandar
  o ack dela com o `POST /outbox/{id}/ack` de sempre. O app só manda o ack
  depois de aplicá-la (SYN-029).
- **Exclusão do celular, pela fila.** O app enfileira uma exclusão com os ids
  de todos os Itens apagados. Ela entra na fila de Pendentes e sai como
  `POST /items/delete` quando o PC estiver na rede, em ordem com o resto da
  fila. 204 a tira da fila; falha de rede ou 503 a mantém, como os outros
  Itens.
- **Pendentes listados saem da fila.** Enfileirar a exclusão tira da fila os
  Pendentes cujos ids ela lista e apaga a cópia interna deles. O cliente
  também sabe tirar assim, sem enfileirar nada, os Pendentes listados numa
  exclusão vinda do PC.
- **Id do Item para o app.** Enviar ou enfileirar um texto ou arquivo devolve
  o UUID que sai no `X-Item-Id`, para o app guardar o id de protocolo de cada
  bolha enviada. Hoje ele nasce dentro do cliente e o app não o vê.
- **A exclusão na fila nunca vira bolha.** Até o SYN-029, nada no app a
  enfileira, e a exclusão vinda do PC é ignorada sem ack: ela volta no próximo
  `/listen`, até o SYN-029 aplicá-la.

#### Acceptance criteria

1. O `/listen` com uma linha `kind:"delete"` entrega ao app uma exclusão com
   `do_pc` e `do_celular`; as linhas `text` e `file` seguem como hoje.
2. O ack de uma exclusão recebida é `POST /outbox/{id}/ack`.
3. Uma exclusão enfileirada sobrevive a recriar o `PendingQueueStore` e sai
   como `POST /items/delete` com o JSON, na ordem da fila.
4. Enfileirar a exclusão tira da fila os Pendentes cujos ids ela lista e apaga
   a cópia interna deles. Tirar da fila os Pendentes listados numa exclusão
   vinda do PC faz o mesmo, sem enfileirar nada.
5. Um 204 tira a exclusão da fila; falha de rede ou 503 a mantém, como os
   outros Itens.
6. Enviar ou enfileirar um texto ou um arquivo devolve o id que o servidor
   falso recebe no `X-Item-Id`, também quando o Item fica Pendente e sai depois.
7. Os testes existentes seguem verdes.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

QA no stage 3: nenhum passo visível; os critérios são da costura do cliente.

## Tests stage 2 writes (own commit, red)

- `ProtocolClientTest.kt` e `PendingQueueStoreTest.kt`, contra o servidor
  falso, critérios 1 a 6. Vermelho porque o `/listen` rejeita o kind `delete`,
  a fila só conhece `T` e `F`, e o envio não devolve o id.

## Comments

- Nasceu do stage 1 do SYN-019 (2026-10-01), que fatiou o pedido em uma costura
  por ticket. Decisões em `.scratch/core/issues/22-SYN-019-apagar-tudo-no-pc.md`.
- 2026-10-01 Proxy decided: fatiado a pedido do dono, na costura de teste. Ficam aqui o cliente do protocolo e a fila (critérios 1 a 5 do original, contra o servidor falso); o app (🗑, Desfazer, MediaStore/SAF, exclusão vinda do PC, critérios 6 e 7 do original) foi para o SYN-029 (`20-SYN-029-android-apagar-tudo-no-app.md`). Para o corte fechar, entram aqui o `FlatJson.kt` (o parser recusa listas), um toque mínimo no `MainActivity.kt` (o `when` sobre `PendingItem` do `init` é exaustivo e quebraria a compilação), o tirar Pendentes sem enfileirar (critério 4) e o id devolvido ao app (critério 6, novo: sem ele o SYN-029 não acha o UUID das bolhas enviadas). Blocked by: SYN-019, sem mudança: o cliente só precisa do protocolo, e com o contrato já no cabeçalho do `server.rs` o review confere contra o PC real; não precisa da janela do PC (SYN-028). Difficulty: normal (era hard): um módulo, testes JVM contra o servidor falso, e a linha nova segue o padrão de `T` e `F`. Review: agent, sem mudança.
- 2026-10-02 Stage 2: nomes da API escolhidos aqui (o ticket não os fixa). `Item.Delete`, `PendingItem.Delete`, `ProtocolClient.enqueueDelete(doPc, doCelular)`, `discardPending(ids)` (exclusão vinda do PC), `acknowledgeDelete(item)`, `PendingQueueStore.remove(ids)`. Critério 6: `sendText`/`sendFile` passam a devolver `String?` e entram `enqueueTextItem`/`enqueueFileItem` (`String?`; `null` só quando o arquivo original já sumiu). `enqueueText`/`enqueueFile` seguem devolvendo `Boolean`, porque `DeliveryWorkerTest.kt` (fora dos Primary files, e o critério 7 manda os testes existentes seguirem verdes) faz `assertTrue(client.enqueueFile(...))`; mudar o tipo quebraria a compilação dele. SYN-029 usa os `*Item`.
- 2026-10-02 Stage 2: a prova vermelha do commit só-teste é de compilação (`Unresolved reference` em `Item.Delete`, `enqueueDelete`, `discardPending`, `enqueueTextItem`, `enqueueFileItem`, `PendingItem.Delete`, `remove`): os tipos novos não existem, então não há como o teste compilar e falhar por comportamento sem pôr código de produção no commit de teste. Verde: `protocol-client` 72 testes, 0 falhas (ProtocolClientTest 54, PendingQueueStoreTest 5, PairingInfoTest 13), 9 deles novos; `gate.ps1` completo passou (fmt, clippy, cargo test, tsc, node --test, gradle check).
- 2026-10-02 Stage 2: `enqueueDelete` enfileira a exclusão antes de tirar os Pendentes listados. Um crash no meio deixa Pendentes que o PC apaga logo em seguida, nunca uma exclusão perdida.
- 2026-10-02 Stage 3: corrigido somente o comentário de `parseFlatJsonObject`, que ainda descrevia o `/listen` sem listas; a escuta usa agora `parseListenObject`. Nenhum comportamento ou teste alterado. Prova vermelha repetida na worktree isolada do commit `29e76b5`: `gate.ps1 -TestsOnly` falhou em `:protocol-client:compileTestKotlin` pelas APIs ausentes, incluindo `Delete`, `enqueueDelete`, `discardPending`, `enqueueTextItem`, `enqueueFileItem`, `acknowledgeDelete` e `remove`.
