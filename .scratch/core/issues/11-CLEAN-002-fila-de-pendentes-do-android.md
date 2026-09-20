# CLEAN-002: Fila de Pendentes do Android — limpeza e política de recusa
Stage: to-review
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
4. ❌ `FilePendingQueueStore.enqueue` não propaga `IOException` quando o arquivo
   original sumiu entre o envio e o enfileiramento (hoje o `copyTo` estoura
   para fora de `sendFile`), e uma cópia em `<fila>.payloads/` não fica órfã
   quando o processo morre entre `copyTo` e `persist()` — varrer o que a fila
   não referencia ao construir o store resolve o segundo.
   **Só o arquivo sumido é engolido.** `copyIntoPayloadDir` captura
   `IOException` inteira, então disco cheio ou permissão negada também
   descartam o Item em silêncio, e `sendFile` volta como se tivesse dado
   certo — perda de dado que a spec não autoriza ("Só a confirmação remove o
   Item da fila de Pendentes"). Estreitar a captura para o caso do arquivo
   que sumiu (`kotlin.io.NoSuchFileException`) e deixar o resto subir, com
   teste que prove que um `IOException` que não seja arquivo-sumido não vira
   descarte silencioso. Se a decisão for engolir mesmo assim, ela precisa
   estar no corpo do ticket e o kdoc de `sendFile` ("deixa o arquivo Pendente
   em vez de perder o Item") precisa parar de dizer o contrário.
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
- `ProtocolClientTest.kt`, critério 4 (reaberto): um `IOException` que não
  seja arquivo-sumido durante o `copyTo` de `enqueue` sobe para quem chamou
  em vez de virar descarte silencioso. Vermelho enquanto
  `copyIntoPayloadDir` capturar `IOException` inteira.

#### Revisão (2026-09-20, stage 3)

Verdict: Needs your call: o critério 4 foi implementado com uma captura larga
demais, que troca uma exceção incômoda por perda silenciosa de arquivo; volta
para o stage 2 com um critério explícito, mas a alternativa (engolir de
propósito e corrigir o kdoc) é uma decisão de comportamento, não de código.

Gate verde nos dois pontos (antes e depois da correção pequena): 24 testes
Rust, 21 Kotlin, 0 falhas. Separação `diff --stat` teste-antes-de-código
correta nos quatro commits do stage 2 (`4a9d089`/`99aec28` e
`e56a150`/`efb6a8d`). Nenhum arquivo fora dos Primary files foi tocado, e o
critério 5 ficou de fora como o próprio ticket manda.

O que fica para o stage 2:

- ❌ **Critério 4** — a captura larga, descrita no critério acima. É o único
  bloqueio.

Corrigido nesta revisão (correção pequena, `d6aea50`):

- **Critério 3** — `Files.move` sem `ATOMIC_MOVE` tem licença para cair para
  copiar-e-apagar, o que reabriria a janela de fila truncada que o critério
  fecha. Passou a pedir `ATOMIC_MOVE` explicitamente.

Notas que não bloqueiam (não viram ❌; se alguém quiser agir, viram ticket
próprio):

- **O teste do critério 3 prova outra coisa.** `fila truncada no meio da
  ultima linha…` fica verde por causa da guarda de `decodeLines` (descartar a
  última linha sem `\n`), não do temporário-mais-rename: trocar `persist()` de
  volta para `writeText` direto mantém o teste verde. A guarda vale por si, e
  o ticket é que previu o vermelho pelo motivo errado. A escrita atômica em si
  continua sem teste — não dá para observá-la pela suíte de forma portátil
  (um teste de arquivo somente-leitura separaria os dois casos no POSIX e
  quebraria no Windows, que é onde o gate roda).
- **`persist()` não faz fsync** antes do rename, então uma queda de energia
  pode pousar o rename sobre dados não descarregados. Morte do processo (o
  caso que o critério 3 cita) está coberta.
- **Um `<fila>.txt.tmp` sobrevivente** a um crash entre `writeText` e o
  `move` não é varrido por ninguém — a varredura cobre só `payloadDir`.
  Inofensivo (é sobrescrito no `persist()` seguinte), mas fica registrado.
- **`PendingItem.File` ao lado de `java.io.File`** rende linhas como
  `if (removed is PendingItem.File) File(removed.path).delete()`. Não é
  sombreamento de verdade (o aninhado é sempre qualificado) e o critério 2
  pedia só o fim do gaguejo, que aconteceu; mas `Item.kt` evitou exatamente
  esse nome (`Item.FileMeta`). Renomear é barato e mexe em arquivo de teste,
  então fica como escolha, não como falha.
- **Dois `FilePendingQueueStore` sobre o mesmo arquivo de fila** usam o mesmo
  `.tmp` e a varredura de órfãos de um apaga os payloads do outro. Não há
  chamador assim hoje; entra junto com a nota de thread-safety de SYN-007, no
  ticket da casca do app Android.

Débito humano: a escrita atômica da fila (`persist()` com temporário +
`ATOMIC_MOVE`) não tem teste que a observe — conferir à mão que matar o app no
meio de um `enqueue` nunca deixa `pendentes.txt` truncado.

## Comments

- 2026-09-20 (stage 2, reaberto): critério 4 fechado — `copyIntoPayloadDir`
  passou a capturar só `NoSuchFileException`; o resto sobe para quem chamou.
  Teste novo (`falha de IO que nao seja arquivo sumido...`) vermelho antes
  (`c265d52`), verde depois (`faca895`). Gate verde: 24 Rust, 22 Kotlin, 0
  falhas. Nenhum arquivo fora dos Primary files tocado.
- 2026-09-20 (stage 3): reaberto. Critérios 1, 2 e 3 de pé; o 4 caiu pela
  captura larga de `IOException`. Detalhes no bloco de revisão acima.
- 2026-09-20 (stage 2): critérios 1 a 4 implementados e com gate verde.
  Critério 5 continua fora de escopo desta passada — precisa de stage 1
  para decidir a política de recusa, como o próprio ticket já previa.
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
