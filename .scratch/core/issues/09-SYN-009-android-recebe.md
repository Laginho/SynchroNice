# SYN-009: Android recebe arquivos do PC
Stage: to-implement
Status: ready-for-agent
Blocked by: SYN-007, SYN-004
Review: agent

- Primary files:
  - android/protocol-client/** (download de Item de arquivo pelo cliente do protocolo: bytes por `GET /outbox/{id}/file` com progresso, `ack` só depois de íntegro)

#### What to build

**Escopo desta entrega (decisão de 2026-09-20, ver `## Comments`):** só o
download de Item de arquivo no módulo cliente do protocolo
(`android/protocol-client`, Kotlin/JVM puro), sem
`com.android.application`. É a parte do ticket que compila e é testável com
o JDK 17 já instalado, é a única costura que "Tests stage 2 writes" nomeia,
e é o que o critério 7 cobre. Nada de bolha, miniatura, cache, salvar na
galeria ou em Downloads, nem abrir navegador nesta rodada.

O módulo cliente ganha o download: quando o `/listen` anuncia um Item de
arquivo, o cliente baixa os bytes por `GET /outbox/{id}/file` em stream,
sem carregar o arquivo inteiro na memória, emitindo progresso crescente que
termina em 100, e entrega bytes idênticos aos que saíram do PC. Download
interrompido não marca o Item como completo: o `POST /outbox/{id}/ack` só
sai depois dos bytes íntegros, para o PC reentregar o que não chegou (ver os
dois comentários da revisão de SYN-004 em `## Comments`). É essa costura que
a casca do app vai consumir para a bolha com progresso e a ação de salvar,
quando existir.

#### Adiado — precisa do Android SDK

O que segue é a recepção de arquivo no app de fato e sai do escopo desta
entrega. Vira ticket próprio quando o Android SDK existir numa máquina de
build.

Itens de arquivo mandados do PC aparecem no chat do celular como bolha à
esquerda. Imagem mostra miniatura carregada do cache do app, sem ir para a
galeria. Outro tipo mostra nome, extensão e tamanho. Durante o download a
bolha mostra progresso animado. Um toque na bolha salva: imagem vai para a
galeria, outro arquivo para Downloads, e a bolha indica que foi salvo. Um
toque em texto que é link abre o navegador.

Critérios adiados:

1. Uma imagem enviada do PC aparece como miniatura no celular sem aparecer na galeria.
2. Um toque na miniatura salva a imagem na galeria e a bolha indica "salvo".
3. Um PDF enviado do PC aparece com nome, `PDF` e tamanho; um toque o salva em Downloads.
4. Durante o download de um arquivo grande a bolha mostra progresso animado.
5. Um link enviado do PC abre no navegador ao tocar.
6. Fechar o app descarta as bolhas; o que foi salvo permanece na galeria ou em Downloads.

#### Acceptance criteria

7. ❌ (revisão de 2026-09-20) `gate.ps1` roda os testes JVM do módulo cliente para o download de Item de arquivo (anúncio recebido pelo `/listen` baixa pela rota de download com progresso crescente até 100 e entrega bytes idênticos; download interrompido não marca o Item como completo nem envia o `ack`), e falha se eles falharem. — **Caiu por duas metades:** nenhum teste parte de um anúncio do `/listen` (os dois novos constroem `Item.FileMeta` à mão), e um `ack` recusado pelo PC é tratado como sucesso, então "confirma o recebimento" não é entregue de fato. Ver o bloco de revisão no fim do arquivo.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

Critérios 1 a 6: adiados (bloco acima), sem verificação manual nesta entrega.

## Tests stage 2 writes (own commit, red)

- `android/.../test/ProtocolClientTest.kt`: ao receber anúncio de Item de arquivo, o cliente baixa pela rota de download com progresso crescente e entrega bytes idênticos; download interrompido não marca o Item como completo. Vermelho porque o download não existe. ✅ feito em `15350e5`/`d3bcbef`, mas só a partir de um `Item.FileMeta` construído à mão — ver os dois de baixo.
- (reaberto 2026-09-20) `android/.../test/ProtocolClientTest.kt`: o anúncio vem do `/listen` de verdade — servidor falso serve a linha NDJSON do Item de arquivo **e** `/outbox/{id}/file`, o teste chama `listen()` e passa o `Item.FileMeta` que voltou para `downloadFile`. Vermelho hoje? Não: passa, e é justamente por isso que falta — nada prova que a costura anúncio→download existe. Escreva-o mesmo assim, no commit de teste, e diga no commit que ele nasce verde por ser cobertura de uma costura já implementada.
- (reaberto 2026-09-20) `android/.../test/ProtocolClientTest.kt`: `ack` recusado pelo PC (servidor falso responde 401 em `/outbox/{id}/ack`) faz `downloadFile` lançar `ProtocolRejectedException`, em vez de retornar como se tivesse confirmado. Vermelho porque `ack()` hoje lê `responseCode` e joga fora.

## Comments

- 2026-09-20 (revisão de SYN-004): **a cadência de reconexão do celular é o
  que decide o indicador de presença do PC, e ela é deste ticket.** O
  `GET /listen` do PC (`pc/src-tauri/src/server.rs`) é long-poll que fecha
  depois de entregar: bloqueia até haver ao menos um Pendente, devolve todos
  os de agora (um JSON por linha) e encerra. A presença do PC é a contagem de
  conexões `/listen` vivas, então **cada entrega derruba a presença até o
  celular reabrir** — o ponto verde do PC pisca a cada envio. Se este cliente
  reabrir o `/listen` imediatamente depois de ler a resposta, o piscar fica
  imperceptível; se esperar, o critério 8 do SYN-004 fica feio na prática
  mesmo estando satisfeito na letra. O simétrico também é deste ticket: um
  celular que some sem fechar o TCP deixa o contador do PC em 1 e a presença
  mentindo "na rede" até o socket estourar, porque o handler está só
  aguardando o próximo Pendente. Vale um keep-alive ou um timeout curto no
  lado do cliente.
- 2026-09-20 (revisão de SYN-004): o protocolo que este cliente consome é
  `GET /listen` (anúncio), `GET /outbox/{id}/file` (bytes) e
  `POST /outbox/{id}/ack` (confirmação) — **só o `ack` remove o Item da fila
  de Pendentes do PC**, nunca a entrega em si, e o `ack` é idempotente (204
  mesmo para id que já saiu). Reenviar a confirmação é inofensivo; deixar de
  enviá-la faz o PC reentregar o mesmo Item, então o cliente precisa ignorar
  id já recebido (spec, "Protocolo de Itens").

- 2026-09-20 Resposta (humano), antes do stage 2: **opção 2, a mesma de
  SYN-007 e SYN-008.** Este ticket bate na mesma parede antes de começar, então
  decido agora em vez de deixar o stage 2 parar em `blocked` pela terceira vez
  com a mesma pergunta. Tudo que o corpo descreve — bolha de arquivo à
  esquerda, miniatura do cache, progresso animado, toque para salvar na
  galeria ou em Downloads, toque em link abrindo o navegador — é app Android
  de verdade: exige `com.android.application`, que exige o Android SDK, e
  esta máquina continua sem ele (sem `ANDROID_HOME`/`ANDROID_SDK_ROOT`, sem
  `android/local.properties`). Não instale o Android SDK: a decisão
  registrada em SYN-007 e repetida em SYN-008 continua valendo — 1–2 GB de
  SDK é mudança na máquina, não no repo, e fica com o humano de verdade. O
  que este ticket tem de testável sem SDK é o módulo cliente: receber pelo
  `/listen` o anúncio de um Item de arquivo, baixar os bytes por
  `GET /outbox/{id}/file` com progresso crescente e entregá-los íntegros, e
  não marcar como completo (não mandar o `ack`) um download interrompido —
  exatamente o que "Tests stage 2 writes" já nomeia. Escopo reduzido a isso +
  critério 7; critérios 1–6 (bolhas, miniaturas, salvar, abrir link) movidos
  para o bloco "Adiado — precisa do Android SDK" no corpo, numeração original
  mantida, igual a SYN-007 e SYN-008. Sem ticket novo por enquanto: o bloco
  Adiado é o registro, e o ticket próprio nasce quando houver SDK numa máquina
  de build, junto com a casca de SYN-007 e o Compartilhar de SYN-008. Os dois
  comentários da revisão de SYN-004 acima seguem valendo para quem
  implementar; o que deles é da costura desta entrega é o `ack` só depois dos
  bytes íntegros — a cadência de reabrir o `/listen` é casca do app (adiada,
  como a nota do stage 2 de SYN-007 já registrou). `Stage` segue
  `to-implement`: o ticket nunca bloqueou. Quem pegar o stage 2: não toque em
  `android/settings.gradle.kts` nem declare `com.android.application` sem
  SDK, pelo motivo já registrado em SYN-008; nada fora de
  `android/protocol-client/` e deste ticket.
- Débito humano: recepção de arquivo no app Android (critérios 1–6: bolha de arquivo à esquerda com progresso animado, miniatura de imagem do cache do app sem ir para a galeria, nome/extensão/tamanho para outros tipos, toque que salva na galeria ou em Downloads e indica "salvo", toque em link abrindo o navegador, bolhas descartadas ao fechar o app) não foi construída nem verificada à mão — depende do Android SDK numa máquina de build; decidir se instala e abrir ticket para ela, junto com a casca de SYN-007 e o Compartilhar de SYN-008.

- 2026-09-20 (stage 2, notas para o revisor): branch `syn-009`, três commits.
  `15350e5` (teste, vermelho — erro de compilação porque `downloadFile` não
  existia) + `d3bcbef` (teste, ajuste de tipo: `assertEquals<T>` não infere
  `T` entre `List<String>` e `MutableList<String?>` do header
  `Authorization`, mesmo ajuste que o teste de `sendText` já fazia) e
  `68ed222` (código). `ProtocolClient.downloadFile(item, destination,
  onProgress)` baixa `GET /outbox/{id}/file` em stream (sem bufferizar o
  arquivo inteiro), progresso 0–100 a cada chunk de 8 KB contra
  `Item.FileMeta.size`, e só manda `POST /outbox/{id}/ack` depois de conferir
  que os bytes recebidos batem com `size`.

  Uma descoberta no meio do ciclo: minha primeira tentativa de simular
  interrupção (servidor de teste declara `Content-Length: 10`, escreve só 3
  bytes e fecha) **não** faz o `HttpURLConnection` lançar nada — o teste
  vermelho por essa premissa passou "com sucesso" (`received` parava em 3
  sem exceção nenhuma), então rodei o `-TestsOnly` antes do código pra
  confirmar que o vermelho era pelo motivo certo (erro de compilação) e não
  por essa suposição errada, e ela mesma quebrou depois que `downloadFile`
  passou a existir mas antes da checagem de tamanho — 15/16 verde, só o teste
  de interrupção falhando com `AssertionFailedError` (nenhuma exceção
  lançada). Corrigi comparando `received != item.size` explicitamente e
  lançando `IOException` — não dá pra contar com o JDK detectar sozinho um
  corpo mais curto que o `Content-Length` quando quem fecha a conexão é
  `com.sun.net.httpserver` no mesmo processo. `ack` fica de fora do `try`
  que teria o `disconnect` do download, então uma falha aí não é engolida
  silenciosamente.

  Vermelho confirmado antes do código: `-TestsOnly` falha em
  `compileTestKotlin` (referência não resolvida) com `d3bcbef` em cima de
  `15350e5`; com `68ed222`, `protocol-client` 16/16. Gate completo verde
  nesta branch: `cargo fmt --check`, `cargo clippy`, `cargo test` 24/24 +
  doc-tests, `tsc --noEmit` limpo, `gradle check` BUILD SUCCESSFUL.

  Não toquei nos dois comentários de revisão de SYN-004 (cadência de
  `/listen` e ack idempotente) nem no dedupe de id já recebido que o segundo
  deles pede — nenhum dos dois está em "Tests stage 2 writes" nem nos
  Primary files reduzidos, e o dedupe em particular só faz sentido quando
  existir o laço que chama `listen()` + `downloadFile()` repetidamente, que
  é casca do app (adiada). Fica registrado aqui para quem escrever esse
  ticket.

  Nada tocado fora de `android/protocol-client/` e deste ticket.

#### Revisão (2026-09-20) — reaberto

Verdict: Reopen — critério 7 caiu, `Stage: to-implement`, segue na branch `syn-009`.

Não é "Needs your call": as duas coisas que faltam são testáveis e cabem nos
Primary files, e nenhuma depende de decisão humana. O que já está na branch é
bom e fica — `15350e5`+`d3bcbef` (teste) e `68ed222` (código), separação
`diff --stat` limpa, teste primeiro, código sem tocar em teste. O vermelho está
provado sem depender da palavra do stage 2: em `d3bcbef` o teste chama
`client.downloadFile(...)` e `git grep -l downloadFile d3bcbef -- src/main` não
acha nada, então `compileTestKotlin` não tinha como compilar. Gate completo
verde nesta branch: `cargo fmt --check`, `cargo clippy`, `cargo test` 18/18 +
doc-tests, `tsc --noEmit` limpo, `gradle check` BUILD SUCCESSFUL,
`protocol-client` 16/16. Protocolo conferido contra `pc/src-tauri/src/server.rs`:
rotas, método, `Authorization: Bearer`, 200 e 204 batem, e o `item.size` do
anúncio é o `bytes.len()` de quem enfileirou (`outbox.rs`), então a checagem
`received != item.size` não pode dar falso positivo. Zero vazamento de escopo:
só `android/protocol-client/**` e este arquivo.

**❌ O que falta (volta para o stage 2):**

1. **A metade `/listen` do critério 7 não é exercida.** O critério diz "anúncio
   recebido pelo `/listen` baixa pela rota de download", e "Tests stage 2
   writes" nomeia a mesma costura. Os dois testes novos constroem
   `Item.FileMeta(42, 100, "foto.png", …)` à mão: nenhum chama `listen()`, nenhum
   servidor falso serve `/listen`. O parse do anúncio de arquivo tem teste
   próprio (`listen entrega Itens em ordem`) e o download tem o dele, mas a junta
   entre os dois — que é a única costura que o escopo reduzido nomeia — não tem
   nada. Custa poucas linhas: servidor falso com `/listen` e `/outbox/{id}/file`,
   `listen()`, e o `Item.FileMeta` que voltou alimenta `downloadFile`.

2. **`ack()` engole a recusa do PC.** `ProtocolClient.kt:198-205` avalia
   `connection.responseCode` e descarta o valor. Um 401 (token trocado) volta
   normalmente, então `downloadFile` retorna como se tivesse confirmado enquanto
   o Item continua Pendente no PC — e o resto do arquivo faz o oposto: `listen` e
   o próprio `downloadFile` lançam `ProtocolRejectedException` em não-2xx,
   `trySendText`/`trySendFile` mapeiam para `SendOutcome.Rejected`. A nota do
   stage 2 ("o `ack` fica de fora do `try`... não é engolida silenciosamente")
   trata do lugar do `disconnect`, não disso: o que é engolido é o status. Sem
   isso, "confirma o recebimento" no critério 7 não é entregue de fato.

Os dois precisam de teste novo, então nenhum é conserto pequeno de stage 3 —
por isso reabre em vez de eu corrigir aqui.

**Observações que não seguram o ticket** (o stage 2 decide; nada é critério):

- `trySendFile` e `downloadFile` agora têm o mesmo laço de streaming com
  progresso (buffer de 8 KB, acumulador, `if (total > 0) … else 100`). Um
  `private fun pump(input, output, total, onProgress): Long` serviria aos dois e
  daria uma casa só para a conferência de tamanho. Se não for junto com o
  conserto acima, vira `CLEAN-*` — não é para refatorar fora do que o ticket toca.
- Download interrompido deixa os bytes parciais em `destination` e ninguém apaga.
  Hoje é inofensivo (a próxima tentativa trunca), mas o arquivo parece completo
  para quem olhar.
- 404 em `/outbox/{id}/file` é "id inexistente **ou já confirmado**" no
  `server.rs`; hoje vira `ProtocolRejectedException`, isto é, o caso benigno de
  id já recebido aparece como recusa. É a mesma questão do dedupe que o stage 2
  deixou para a casca do app — fica registrado, não é deste escopo.
- A KDoc de `downloadFile` explica o servidor de teste ("o servidor de teste
  fecha a conexão sem erro nenhum..."). A justificativa é ótima e já está no
  ticket e daria um bom comentário no teste; na KDoc de produção destoa do resto
  do arquivo, que documenta só política de domínio.
- No teste de interrupção, `val ackedIds = mutableListOf<String>()` recebe
  `"chamado"` — não guarda id nenhum. `ackCalls`, ou um booleano com
  `assertFalse(acked)`, seria honesto.
