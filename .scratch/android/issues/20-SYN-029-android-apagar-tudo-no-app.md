# SYN-029: Apagar tudo no app do celular
Stage: implementing
Status: ready-for-agent
Blocked by: SYN-027, SYN-028
Review: agent
Difficulty: hard

- Primary files:
  - android/app/src/main/java/synchronice/android/MainActivity.kt (🗑, aviso com Desfazer, Apagar tudo do `AppState`, aplicar a exclusão vinda do PC, id do Item no `ChatMessage`, fechar o visualizador)
  - android/app/src/main/java/synchronice/android/ReceivedFiles.kt (apagar no MediaStore e no SAF)
  - New: android/app/src/test/java/synchronice/android/ApagarTudoTest.kt

#### What to build

O lado do app do Apagar tudo no celular, com o mesmo comportamento e o
protocolo definidos no SYN-019. Leia as decisões de lá, e as do SYN-027, que
já entrega a exclusão do `/listen`, a exclusão na fila, o tirar Pendentes
listados e o id de protocolo de cada Item enviado. Este ticket não mexe no
`protocol-client`.

- **Botão.** Um 🗑 no topo do chat, entre "Na rede" e a ⚙, só visível com o
  chat não vazio.
- **Desfazer.** O toque esvazia o chat na hora e mostra um aviso embaixo:
  "Conversa apagada · N Itens e M arquivos", com "Desfazer" e uma barra que
  esvazia em 5 s. Nada é apagado nem sai pela rede durante a janela.
- **Fim da janela** (o Apagar tudo do `AppState`, que o fim da janela chama):
  - os Pendentes do chat saem da fila e têm a cópia interna apagada. Isso vale
    também para os vindos de um Compartilhar com o app fechado, que o chat
    sempre mostra;
  - os arquivos que o celular gravou ao receber aqueles Itens são apagados de
    vez, no MediaStore ou no SAF. A lixeira do MediaStore exige API 30 e um
    diálogo, e o minSdk é 29;
  - os Itens saem da Sessão;
  - uma exclusão com os ids de todos os Itens apagados entra na fila de
    Pendentes, pelo SYN-027.
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
- A exclusão na fila nunca vira bolha. Ela ocupa uma posição da fila sem
  bolha: a marcação de Entregue por posição (`submitted`,
  `reconcilePendingDelivery`) tem de contar com isso.
- O `ChatMessage` passa a saber o id de protocolo de cada Item: o UUID dos
  enviados, devolvido pelo cliente (SYN-027), e o `u64` do PC nos recebidos.

#### Acceptance criteria

1. Uma exclusão vinda do `/listen` tira do chat as mensagens com aqueles ids e
   tira da fila os Pendentes listados, e só então o app manda o ack; id
   desconhecido não muda nada e também recebe o ack.
2. O Apagar tudo do `AppState` (a operação que o fim da janela chama)
   esvazia o chat, tira da fila os Pendentes do chat e enfileira uma exclusão
   com os ids de protocolo de todas as mensagens apagadas: o UUID das enviadas
   em `do_celular`, o `u64` das recebidas em `do_pc`.
3. No aparelho: o 🗑 só aparece com o chat não vazio; o toque esvazia o chat e
   mostra o aviso com as contagens; "Desfazer" em até 5 s devolve as bolhas e
   nada sai de `Download/SynchroNice`; sem desfazer, os arquivos recebidos
   saem de lá e o PC perde as bolhas.
4. Um Apagar tudo feito no PC tira as bolhas do celular e os arquivos dele de
   `Download/SynchroNice`.
5. Os testes existentes seguem verdes.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

QA no stage 3: kit no emulador, critérios 3 e 4, conferindo os arquivos com
`phone.ps1 list-files` e o PC pelo CDP; o critério 4 usa o 🗑 do SYN-028.

## Tests stage 2 writes (own commit, red)

- `ApagarTudoTest.kt` (novo), JVM com Robolectric, `AppState` real e servidor
  falso, como `ListenLoopTest` e `PendingDeliveryTest`, critérios 1 e 2.
  Vermelho porque o app ignora a exclusão vinda do PC e o `AppState` não tem
  Apagar tudo.

## Comments

- 2026-10-01 Proxy decided: nasceu do fatiamento do SYN-027 a pedido do dono. Veio para cá o app (🗑, Desfazer, apagar no MediaStore/SAF, exclusão vinda do PC; critérios 6 e 7 do original, aqui 3 e 4), com as decisões de app do original; os critérios 1 e 2 são novos, na costura Robolectric do app que já existe, para este ticket não ficar só com QA. Ficaram no SYN-027 o cliente do protocolo e a fila. Blocked by: SYN-027 (exclusão do `/listen`, fila, id devolvido) e SYN-028, porque o critério 4 precisa do 🗑 do PC e o 3 precisa da janela do PC tirando as bolhas. Difficulty: hard: arquivo ainda baixando, MediaStore e SAF com checagens de segurança, a fila sem bolha mexendo na marcação de Entregue, tudo no `MainActivity.kt`, de que o app inteiro depende. Review: agent, porque as docs ficaram no SYN-019.
- 2026-10-02 Stage 2, commit de testes (`ApagarTudoTest.kt`, 9 testes). A operação do `AppState` que o fim da janela chama chama-se `suspend fun deleteAll(localIds: Set<Long> = todas as bolhas do chat)`: o parâmetro é o conjunto de `localId` da janela, para uma mensagem enviada durante os 5 s ficar de fora. Prova vermelha, duas camadas: (1) sem o método, `gradle test` não compila (`Unresolved reference 'deleteAll'`, 5 usos), o mesmo vermelho do SYN-027 (`29e76b5`); (2) com um stub vazio e temporário de `deleteAll` (não commitado), 81 testes do app rodam e os 9 novos falham, 8 pelo motivo certo (a exclusão do PC nunca recebe ack, o chat não esvazia, o Pendente restaurado ainda é entregue) e `deleteAllOnAnEmptyChatQueuesNothing` passa de propósito: é a guarda do chat vazio, que o código novo não pode quebrar. Lido em volta antes dos testes: `retryPending` conta `queueStore.load().size` como posições de bolha, e uma exclusão na fila (sem bolha, depois de um Pendente enviado na janela) deixa esse Pendente em PENDING (teste `aQueuedDeleteHoldsAQueuePosition...`); o `init` restaura Pendentes como bolhas, que precisam do id de protocolo (teste `deleteAllCoversPendingItemsRestoredAfterTheAppRestarted`).
