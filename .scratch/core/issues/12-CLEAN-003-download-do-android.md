# CLEAN-003: Download do Android — streaming duplicado e restos de um download que falhou
Stage: to-review
Status: ready-for-agent
Blocked by: none
Review: agent

- Primary files:
  - android/protocol-client/src/main/kotlin/synchronice/protocolclient/ProtocolClient.kt
  - android/protocol-client/src/test/kotlin/synchronice/protocolclient/ProtocolClientTest.kt

#### What to build

SYN-009 fechou com o download de Item de arquivo funcionando e testado
(`downloadFile`: stream, progresso, `ack` só depois dos bytes íntegros, recusa
do PC propagada). Este ticket é o que a revisão listou como "não segura o
ticket" nas duas rodadas e o stage 2 deixou de propósito: duplicação entre
subir e baixar, e o que sobra no disco quando um download falha.

Nada aqui muda o protocolo nem o que a casca do app vai ver de fora, com uma
exceção anotada no critério 3.

#### Acceptance criteria

1. `trySendFile` e `downloadFile` param de repetir o mesmo laço de streaming
   com progresso (buffer de 8 KB, acumulador, `if (total > 0) … else 100`): um
   `private fun pump(input, output, total, onProgress): Long` serve aos dois e
   dá uma casa só para a conferência de tamanho. Os testes de progresso que já
   existem (`envio de arquivo em stream emite progresso crescente ate 100`,
   `baixa Item de arquivo com progresso crescente, bytes identicos, e confirma
   o recebimento`) continuam verdes sem ser editados.
2. Um download interrompido não deixa bytes parciais parecendo arquivo
   completo em `destination`. Hoje a próxima tentativa trunca, então é
   inofensivo para o cliente — mas quem abrir a pasta vê um arquivo que nunca
   chegou inteiro.
3. `404` em `GET /outbox/{id}/file` para de virar recusa indistinguível.
   `server.rs` documenta `404` como "id não existe **ou já foi confirmado**",
   e o segundo caso é benigno: é o Item que o PC reentregou porque o `ack`
   anterior se perdeu. Hoje vira `ProtocolRejectedException` igual a um token
   errado. É a mesma questão do dedupe de id já recebido que SYN-009 deixou
   para a casca do app — a parte que é deste módulo é o cliente conseguir
   distinguir os dois.
4. A KDoc de `downloadFile` para de explicar o servidor de teste ("o servidor
   de teste fecha a conexão sem erro nenhum…"). A justificativa é boa e já
   está no corpo de SYN-009; no teste ela é um comentário útil, na KDoc de
   produção destoa do resto do arquivo, que documenta só política de domínio.
5. No teste `download interrompido nao marca o Item como completo e nao envia
   o ack`, `val ackedIds = mutableListOf<String>()` recebe a string
   `"chamado"` — não guarda id nenhum. `ackCalls`, ou um booleano com
   `assertFalse(acked)`, diz a verdade.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

## Tests stage 2 writes (own commit, red)

- Critérios 1, 4 e 5: nenhum teste novo — refatoração, KDoc e nome de
  variável; a prova do 1 é a suíte de `ProtocolClientTest.kt` continuar verde
  sem os testes de streaming serem editados.
- `ProtocolClientTest.kt`, critério 2: depois de um download interrompido
  (servidor declara `Content-Length` maior do que escreve), `destination` não
  fica com os bytes parciais. Vermelho enquanto `downloadFile` não limpar.
- `ProtocolClientTest.kt`, critério 3: `404` em `/outbox/{id}/file` é
  distinguível de uma recusa de token pelo chamador. Vermelho enquanto os dois
  forem o mesmo `ProtocolRejectedException` sem nada que os separe.

## Comments

- 2026-09-20 (revisão de SYN-009): aberto pela revisão, como a revisão
  anterior do mesmo ticket já tinha previsto ("Se não for junto com o conserto
  acima, vira `CLEAN-*`"). Os critérios 1, 2, 4 e 5 são as "Observações que não
  seguram o ticket" da rodada de reopen; o 3 é a terceira delas, que o stage 2
  também deixou registrada.
- A cadência de reabrir o `/listen` (comentário da revisão de SYN-004 no corpo
  de SYN-009) e o dedupe de id já recebido **não** são deste ticket: os dois
  só existem quando houver o laço que chama `listen()` + `downloadFile()`
  repetidamente, que é casca do app Android e depende do Android SDK. O
  critério 3 aqui é só a metade que mora neste módulo.
