# CLEAN-003: Download do Android — streaming duplicado e restos de um download que falhou
Stage: done
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

#### Resolution (2026-09-20)

Verdict: Approve.

Os cinco critérios fecharam, o gate está verde e o merge foi feito em
`sweatshop/2026-09-19-2324` (`b3e2869`). Nenhuma correção pequena foi
necessária nesta revisão.

Arquivos: `ProtocolClient.kt`, `ProtocolClientTest.kt` — nada fora dos Primary
files (o terceiro arquivo do diff é este ticket, só a linha `Stage:`).

Critério a critério:

1. **Verde.** `pump(input, output, total, onProgress): Long` existe e os dois
   chamadores usam; o laço de 8 KB com acumulador e `if (total > 0) … else
   100` some das duas cópias. Os dois testes de progresso que o critério manda
   preservar continuam intactos — os únicos hunks do arquivo de teste são
   `@@ -576,17 @@` e `@@ -596,7 @@`, e os dois testes estão fora deles.
   Nuance registrada: só `downloadFile` compara o retorno de `pump`;
   `trySendFile` descarta, porque quem confere o tamanho no envio é o
   `setFixedLengthStreamingMode`. A "casa só" é o valor de retorno, não uma
   checagem compartilhada — aceito.
2. **Verde.** `downloadFile` grava em `<destination>.part` ao lado do destino e
   só faz `Files.move(…, REPLACE_EXISTING)` depois de conferir o tamanho.
3. **Verde.** `404` vira `ItemGoneException`, e bate com o servidor:
   `server.rs` devolve `401` para token errado e `404` só quando
   `outbox.file_bytes(id)` é `None`, então a nova exceção não engole recusa
   nenhuma.
4. **Verde.** A explicação do servidor de teste saiu da KDoc de `downloadFile`
   e virou comentário sobre o teste de download interrompido.
5. **Verde.** `ackedIds`/`"chamado"` deram lugar a `var acked = false` +
   `assertFalse(acked)`.

Prova vermelho-verde, refeita nesta revisão. O commit de teste (`7b50423`)
sozinho só falha na compilação (`ItemGoneException` ainda não existe), o que
não prova nada sobre o critério 2. Então, numa árvore de trabalho separada
sobre `b79b3a3`, revertendo só as duas mudanças de comportamento de
`downloadFile` (o `.part` + `Files.move`, e o ramo do `404`) e deixando o
resto do commit de pé: `24 tests completed, 2 failed`, e as duas que caem são
exatamente as duas novas —
`404 no download do outbox e id inexistente ou ja confirmado, distinto de
recusa()` (`ProtocolClientTest.kt:639`) e
`download interrompido nao deixa bytes parciais em destination()`
(`ProtocolClientTest.kt:623`). Restaurado o arquivo, 24/24 verdes. Separação
`diff --stat` teste-antes-de-código correta: `7b50423` toca só o teste,
`b79b3a3` toca só a produção.

Gate verde (`gate.ps1`): Rust 4 + 2 + 18 = 24 passaram, 0 falharam; Kotlin 24
passaram, 0 falharam; `cargo fmt --check`, `cargo clippy`, `tsc --noEmit` e
`gradle check` limpos.

Notas que não bloqueiam (viraram CLEAN-004):

- **A limpeza do `.part` não tem teste.** O teste do critério 2 afirma
  `assertEquals(0L, destination.length())`, e `destination` já nasce com 0
  bytes do `File.createTempFile` — a asserção não distingue "gravou e limpou"
  de "nunca tocou", e nada afirma que o `<nome>.part` sumiu. O `.part` é
  exatamente o "arquivo que nunca chegou inteiro" que o critério 2 quer fora
  da Pasta, só com outro nome. Conferido à mão nesta revisão (uma asserção
  temporária `assertFalse(…".part".exists())` na árvore separada passa), então
  o comportamento está certo hoje; o que falta é a trava contra regressão.
- **O nome do `.part` é fixo.** Dois downloads para o mesmo destino se
  atropelam. Sem risco real enquanto não existir o laço de download da casca
  do app.
- **`file_bytes` em `server.rs` é `fs::read(path).ok()`**, então um erro de
  I/O transitório também vira `404`. Antes do critério 3 isso era um
  `ProtocolRejectedException` barulhento; agora é "o Item já era", e um Item
  pode sumir em silêncio por erro de disco no PC. Fora dos Primary files.
- **Andaime duplicado nos testes** (`sendResponseHeaders(200, 10)` + 3 bytes +
  temp file + `finally`) nos dois testes de download interrompido.
- **`pump` e `ItemGoneException` são nomes genéricos** — "Gone" não é
  vocabulário do `CONTEXT.md`. A KDoc carrega o sentido; o nome de `pump` veio
  do próprio critério 1. Não vale mexer.

## Comments

- 2026-09-20 (stage 3): critérios 1 a 5 aprovados, sem correções pequenas,
  merge em `sweatshop/2026-09-19-2324` (`b3e2869`). As notas que não seguram o
  ticket viraram CLEAN-004. Detalhes no bloco de Resolution acima.
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
