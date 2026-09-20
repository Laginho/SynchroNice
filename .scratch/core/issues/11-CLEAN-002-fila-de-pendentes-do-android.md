# CLEAN-002: Fila de Pendentes do Android — limpeza e política de recusa
Stage: to-implement
Status: ready-for-agent
Blocked by: none
Review: agent

- Primary files:
  - android/protocol-client/src/main/kotlin/synchronice/protocolclient/ProtocolClient.kt
  - android/protocol-client/src/main/kotlin/synchronice/protocolclient/PendingItem.kt
  - android/protocol-client/src/main/kotlin/synchronice/protocolclient/PendingQueueStore.kt
  - android/protocol-client/src/test/kotlin/synchronice/protocolclient/ProtocolClientTest.kt

#### What to build

O módulo cliente do Android saiu de SYN-008 com a fila de Pendentes em disco
funcionando e testada. Este ticket é o que sobrou em volta dela: duplicação e
nomes que a revisão de SYN-008 apontou e não bloqueavam, mais dois buracos de
robustez que ninguém exercita hoje.

**A parte de política (critério 5) não é limpeza e precisa de stage 1**: é uma
decisão de comportamento, com critério próprio, e está aqui só para não se
perder num ticket fechado. Quem pegar este ticket sem essa decisão tomada faz
os critérios 1 a 4 e deixa o 5.

#### Acceptance criteria

1. `sendText` e `sendFile` param de repetir a forma de quatro passos (drena,
   enfileira-se-Pendente, `when(outcome)`); um caminho único de envio, com o
   `PendingItem.PendingFile(...)` construído uma vez só dentro de `sendFile`.
   Os testes de ordem de fila que já existem continuam verdes sem mudança.
2. `PendingItem.PendingText`/`PendingFile` deixam de gaguejar, seguindo o que
   `Item.kt` já faz (`Item.Text`/`Item.FileMeta`).
3. `FilePendingQueueStore.persist()` para de reescrever a fila inteira com
   `writeText` direto no arquivo final: arquivo temporário + rename, para um
   crash no meio não truncar a fila que existe justamente para sobreviver ao
   app ser fechado.
4. `FilePendingQueueStore.enqueue` não propaga `IOException` quando o arquivo
   original sumiu entre o envio e o enfileiramento (hoje o `copyTo` estoura
   para fora de `sendFile`), e uma cópia em `<fila>.payloads/` não fica órfã
   quando o processo morre entre `copyTo` e `persist()` — varrer o que a fila
   não referencia ao construir o store resolve o segundo.
5. **(precisa de stage 1 antes)** Um Item recusado pelo PC tem política
   definida e testada. Hoje: um `401` (o Par rotacionou o token, SYN-005) na
   cabeça da fila faz `retryPending()` lançar com o Item ainda na cabeça, e
   daí em diante nada mais é entregue — com a fila em disco, o travamento
   sobrevive a reiniciar o app. E o `503` do PC sem Pasta escolhida
   (`pc/src-tauri/src/server.rs`) vira `Rejected`, então o Item é perdido sem
   nem entrar na fila, embora seja condição recuperável. Descartar, marcar
   como falho na bolha, pedir re-pareamento, tratar `503` como
   indisponibilidade — é a decisão que falta.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

## Tests stage 2 writes (own commit, red)

- Critérios 1 e 2: nenhum teste novo — são refatoração, e a prova é a suíte
  de `ProtocolClientTest.kt` continuar verde sem ser editada.
- `ProtocolClientTest.kt`, critério 3: fila cujo arquivo foi truncado no meio
  de uma linha ainda dá um cliente construível com os Itens íntegros.
  Vermelho enquanto `persist()` escrever direto no arquivo final.
- `ProtocolClientTest.kt`, critério 4: `sendFile` de um arquivo apagado logo
  depois não lança (hoje o `copyTo` de `enqueue` lança); construir o store
  sobre um `payloads/` com arquivo não referenciado o deixa vazio.
- `ProtocolClientTest.kt`, critério 5: só depois do stage 1 — o teste
  depende da política escolhida.

## Comments

- 2026-09-20 (revisão de SYN-008): aberto pela revisão. Os critérios 1 a 3
  são os itens de Standards que a revisão anterior de SYN-008 listou como
  "não bloqueiam a volta"; o 4 saiu da leitura do código desta rodada; o 5 é
  a pergunta que o comentário de SYN-007 mandou decidir "em SYN-008" e que a
  resposta do humano que reduziu o escopo não incluiu — ela sobreviveu duas
  revisões sem dono, e este ticket é o dono agora.
- O `onProgress` a cada 8 KB (~64 mil chamadas no vídeo de 500 MB do critério
  4 adiado de SYN-008) e o `retryPending()` que drena arquivo com `onProgress`
  vazio (`{}`) ficaram de fora de propósito: os dois só importam quando
  existir UI, e vão junto com o ticket da casca do app Android.
- A nota de thread-safety de SYN-007 (`sendText` drena e envia sem exclusão)
  continua aberta e continua sem chamador concorrente, porque o serviço em
  primeiro plano segue adiado. Também é do ticket da casca.
