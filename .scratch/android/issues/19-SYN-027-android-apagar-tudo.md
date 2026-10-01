# SYN-027: Apagar tudo no celular
Stage: to-implement
Status: ready-for-agent
Blocked by: SYN-019
Review: agent
Difficulty: hard

- Primary files:
  - android/protocol-client/src/main/kotlin/synchronice/protocolclient/ProtocolClient.kt (linha `delete` do `/listen`, `POST /items/delete`)
  - android/protocol-client/src/main/kotlin/synchronice/protocolclient/PendingItem.kt
  - android/protocol-client/src/main/kotlin/synchronice/protocolclient/PendingQueueStore.kt (linha nova da exclusão)
  - android/protocol-client/src/main/kotlin/synchronice/protocolclient/Item.kt
  - android/app/src/main/java/synchronice/android/MainActivity.kt (🗑, aviso com Desfazer, aplicar a exclusão, id do Item no `ChatMessage`)
  - android/app/src/main/java/synchronice/android/ReceivedFiles.kt (apagar no MediaStore e no SAF)
  - android/protocol-client/src/test/kotlin/synchronice/protocolclient/ProtocolClientTest.kt
  - android/protocol-client/src/test/kotlin/synchronice/protocolclient/PendingQueueStoreTest.kt

#### What to build

O lado do celular do Apagar tudo, com o mesmo comportamento e o protocolo
definidos no SYN-019. Leia as decisões de lá, principalmente o formato
`{"do_pc":[...],"do_celular":[...]}`, a rota `POST /items/delete` e a linha
`kind:"delete"` do `/listen`.

- **Botão.** Um 🗑 no topo do chat, entre "Na rede" e a ⚙, só visível com o
  chat não vazio.
- **Desfazer.** O toque esvazia o chat na hora e mostra um aviso embaixo:
  "Conversa apagada · N Itens e M arquivos", com "Desfazer" e uma barra que
  esvazia em 5 s. Nada é apagado nem sai pela rede durante a janela.
- **Fim da janela:**
  - os Pendentes do chat saem da fila e têm a cópia interna apagada. Isso vale
    também para os vindos de um Compartilhar com o app fechado, que o chat
    sempre mostra;
  - os arquivos que o celular gravou ao receber aqueles Itens são apagados de
    vez, no MediaStore ou no SAF. A lixeira do MediaStore exige API 30 e um
    diálogo, e o minSdk é 29;
  - os Itens saem da Sessão;
  - uma exclusão com os ids de todos os Itens apagados entra na fila de
    Pendentes. Ela sai como `POST /items/delete` quando o PC estiver na rede,
    em ordem com o resto da fila.
- **A cópia de "Guardar fotos tiradas" fica.** Essa opção existe para guardar
  a foto, e o original de quem enviou nunca é tocado.
- **Exclusão vinda do PC** (linha `delete` no `/listen`). As bolhas somem sem
  aviso, os arquivos gravados para aqueles ids são apagados, e os Pendentes
  listados saem da fila. Depois vem o ack.
  - Id desconhecido é ignorado. Uma exclusão que chega depois que o Android
    matou o app não acha nada, e o arquivo fica.
  - Um arquivo ainda baixando é apagado quando o download termina ou falha.
  - Se a imagem aberta no visualizador for apagada, ele fecha.
- **Segurança antes de apagar.** Só a URI que o app gravou para aquele Item, e
  só se o tamanho ainda é o do Item. Na dúvida, não apaga. Um
  `SecurityException` (MediaStore de uma instalação anterior) ou a permissão
  do SAF perdida são ignorados, e o Item sai da Sessão mesmo assim.
- A exclusão na fila nunca vira bolha.
- O `ChatMessage` passa a saber o id de protocolo de cada Item: o UUID dos
  enviados e o `u64` do PC nos recebidos.

#### Acceptance criteria

1. O `/listen` com uma linha `kind:"delete"` entrega ao app uma exclusão com
   `do_pc` e `do_celular`; as linhas `text` e `file` seguem como hoje.
2. O ack de uma exclusão recebida é `POST /outbox/{id}/ack`.
3. Uma exclusão enfileirada sobrevive a recriar o `PendingQueueStore` e sai
   como `POST /items/delete` com o JSON, na ordem da fila.
4. Enfileirar a exclusão tira da fila os Pendentes cujos ids ela lista e apaga
   a cópia interna deles.
5. Um 204 tira a exclusão da fila; falha de rede ou 503 a mantém, como os
   outros Itens.
6. No aparelho: o 🗑 só aparece com o chat não vazio; o toque esvazia o chat e
   mostra o aviso com as contagens; "Desfazer" em até 5 s devolve as bolhas e
   nada sai de `Download/SynchroNice`; sem desfazer, os arquivos recebidos
   saem de lá e o PC perde as bolhas.
7. Um Apagar tudo feito no PC tira as bolhas do celular e os arquivos dele de
   `Download/SynchroNice`.
8. Os testes existentes seguem verdes.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

QA no stage 3: kit no emulador, critérios 6 e 7, conferindo os arquivos com
`phone.ps1 list-files` e o PC pelo CDP.

## Tests stage 2 writes (own commit, red)

- `ProtocolClientTest.kt` e `PendingQueueStoreTest.kt`, contra o servidor
  falso, critérios 1 a 5. Vermelho porque o `/listen` rejeita o kind `delete`,
  e a fila só conhece `T` e `F`.

## Comments

- Nasceu do stage 1 do SYN-019 (2026-10-01), que fatiou o pedido em uma costura
  por ticket. Decisões em `.scratch/core/issues/22-SYN-019-apagar-tudo-no-pc.md`.
