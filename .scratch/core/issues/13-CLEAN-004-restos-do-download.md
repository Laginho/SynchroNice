# CLEAN-004: Restos do download — o `.part` sem teste, e o 404 que engole erro de disco
Stage: done
Status: ready-for-agent
Blocked by: none
Review: agent

- Primary files:
  - android/protocol-client/src/test/kotlin/synchronice/protocolclient/ProtocolClientTest.kt
  - pc/src-tauri/src/server.rs (só `file_bytes` / o ramo do `404` de
    `GET /outbox/{id}/file`)
  - pc/src-tauri/src/outbox.rs (só `file_bytes`)

#### What to build

CLEAN-003 fechou o download do Android: `downloadFile` grava num
`<destino>.part` e só move para o destino depois de conferir o tamanho, e um
`404` virou `ItemGoneException` em vez de recusa. As duas mudanças estão
certas e o gate está verde — este ticket é o que a revisão delas listou como
"não segura o ticket".

Duas coisas, e as duas são sobre o que sobra quando um download dá errado:
o arquivo temporário que ninguém observa, e um erro de disco no PC que hoje
o celular lê como "o Item já era".

Nada aqui muda o protocolo.

#### Acceptance criteria

1. A limpeza do `<destino>.part` tem teste. Hoje
   `download interrompido nao deixa bytes parciais em destination` afirma só
   `assertEquals(0L, destination.length())`, e `destination` já nasce com 0
   bytes do `File.createTempFile` — a asserção não distingue "gravou e limpou"
   de "nunca tocou", e nada afirma que o `.part` sumiu. O `.part` deixado para
   trás é o mesmo "arquivo que nunca chegou inteiro" na Pasta que CLEAN-003
   queria eliminar, só com outro nome. O comportamento está certo hoje
   (conferido à mão na revisão de CLEAN-003), então a asserção nasce verde: é
   trava contra regressão, e vai junto com o commit que ela protege, sem
   fingir um vermelho que não existe.
2. Um erro de I/O transitório ao ler o arquivo de saída no PC para de virar
   `404`. `outbox.file_bytes` é `fs::read(path).ok()`, então disco com
   problema, permissão negada e "o id não existe" saem todos como `None` e o
   servidor responde `404`. Desde CLEAN-003 o celular lê `404` como
   `ItemGoneException` — "não há nada para baixar, siga" — então um Item pode
   sumir em silêncio por erro de disco. Um erro real de leitura tem que virar
   `500`, que o cliente já trata como recusa e não como Item resolvido.
3. O andaime duplicado dos dois testes de download interrompido
   (`sendResponseHeaders(200, 10)` + 3 bytes escritos + temp file +
   `finally { server.stop(0); destination.delete() }`) vira um helper só. Os
   dois testes continuam com a mesma asserção que têm hoje.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

## Tests stage 2 writes (own commit, red)

- Critério 1: `ProtocolClientTest.kt` — depois de um download interrompido,
  `File(destination.absoluteFile.parentFile, "${destination.name}.part")` não
  existe. **Nasce verde**, porque `downloadFile` já apaga o `.part` no
  `catch`. É o caso excepcional que o critério 1 autoriza: sem vermelho para
  provar, a asserção vai no mesmo commit que a produção que ela trava.
- Critério 2: `pc/src-tauri` — `GET /outbox/{id}/file` com o arquivo de saída
  ilegível responde `500`, não `404`. Vermelho enquanto `file_bytes` engolir o
  erro com `.ok()`.
- Critério 3: nenhum teste novo — extração de helper; a prova é a suíte de
  `ProtocolClientTest.kt` continuar verde com as mesmas asserções.

#### Resolution (2026-09-20)

Verdict: Approve

Os três critérios estão atendidos, e o eixo de Spec confere um a um:

1. ✅ `download interrompido apaga o arquivo parcial ponto part` afirma
   `assertFalse(partial.exists())` com o **mesmo** caminho que a produção
   monta (`File(destination.absoluteFile.parentFile, "${destination.name}.part")`,
   `ProtocolClient.kt:186`) — não uma cópia da regra, a mesma expressão. E a
   asserção tem conteúdo de verdade, não é tautologia: no cenário do helper o
   handler escreve 3 bytes antes de o `pump` estourar na checagem de tamanho,
   então o `.part` **é** criado e depois apagado no `catch`. Nasce verde, como o
   ticket autorizou por escrito, e vai no commit de teste — que é onde a regra
   do fluxo quer a trava.
2. ✅ `file_bytes` devolve `io::Result<Vec<u8>>` em vez de engolir o erro com
   `.ok()`, e a rota separa `ErrorKind::NotFound` → `404` de qualquer outro erro
   → `500`. Vermelho provado de verdade e pelo motivo certo (abaixo).
3. ✅ O andaime duplicado virou `downloadInterrompido(configureServer, assertions)`,
   e os dois testes antigos mantiveram exatamente a asserção que tinham
   (`assertFalse(acked)` e `assertEquals(0L, destination.length())`), como o
   critério exigia.

Arquivos:

- `pc/src-tauri/src/outbox.rs` — `file_bytes` → `io::Result`
- `pc/src-tauri/src/server.rs` — ramo `NotFound`/outros em `get_outbox_file`
- `pc/src-tauri/tests/server.rs` — teste novo do `500`
- `android/.../ProtocolClientTest.kt` — helper + teste do `.part`

Prova vermelho-verde (rodada na revisão, não herdada do stage 2):

    # worktree em 447708a (commit só de teste)
    ---- arquivo_de_saida_ilegivel_no_disco_vira_500_nao_404 stdout ----
    panicked at tests\server.rs:494:5:
    assertion `left == right` failed: erro de leitura que não é "arquivo sumiu"
    não pode virar 404 (o celular lê 404 como Item já resolvido)
      left: 404
     right: 500

    # HEAD da branch, gate completo
    test result: ok. 19 passed; 0 failed   (--test server)
    gradle check: 25 tests, 0 failures, 0 errors
    gate: passou

Total do `pc/`: 25 testes (0 + 0 + 4 + 2 + 19 + 0 doc-tests), 0 falhas.

Correção pequena feita pela revisão (dentro dos Primary files, sem teste novo):
o ramo do `500` descartava o erro com `Err(_)`, então o único caso em que um
Item some por culpa do disco era também o único sem rastro no PC. Agora faz
`eprintln!("falha ao ler o arquivo de saída do Item {id}: {e}")`, que é a
convenção do handler irmão de `POST /inbox` no mesmo arquivo (`server.rs:321`,
`341`, `350`).

Notas do eixo de Standards, nenhuma segurando o ticket:

- O commit de código `c3ee306` encosta em `tests/server.rs`, o que a regra do
  fluxo normalmente proíbe. Aqui é inevitável e correto: a mudança é
  `file_bytes(...)` → `file_bytes(...).ok()` numa asserção que já existia, e
  no commit de teste ela não compilaria, porque `file_bytes` ainda devolvia
  `Option`. Mesmo valor comparado, nenhuma cobertura perdida, e o commit
  declara isso no corpo.
- `tests/server.rs` não está nos Primary files, mas a seção "Tests stage 2
  escreve" manda o teste do critério 2 para `pc/src-tauri` sem nomear arquivo —
  é o único lugar onde ele cabe. Fica registrado, não é violação de fronteira.
- A troca de um arquivo por um diretório para forçar erro de leitura que não é
  `NotFound` é portátil o bastante para este repo: Windows dá
  `PermissionDenied`, Linux dá `IsADirectory`, os dois fora do ramo do `404`.

## Comments

- 2026-09-20 (revisão de CLEAN-004): o gate falhou uma vez no meio desta
  revisão, em `dois_envios_concorrentes_do_mesmo_nome_preservam_ambos` —
  instabilidade velha, que é o objeto de CLEAN-001 e não tem nada a ver com
  este ticket (nada aqui toca `POST /inbox`). A revisão finalmente capturou o
  nome e o ponto exato da falha, que as três observações anteriores tinham
  perdido; está anotado em `## Comments` de CLEAN-001, corpo daquele ticket
  intocado.
- 2026-09-20 (revisão de CLEAN-003): aberto pela revisão. Os critérios 1 e 3
  são notas dos dois eixos; o 2 é a nuance que o eixo de Spec achou em
  `server.rs`, e que CLEAN-003 não podia tocar porque o arquivo está fora dos
  Primary files dele.
- O nome fixo `<destino>.part` (dois downloads para o mesmo destino se
  atropelam) **não** é deste ticket: só existe quando houver o laço que chama
  `listen()` + `downloadFile()` repetidamente, que é casca do app Android.
  Vai junto com o dedupe de id já recebido e a cadência do `/listen`, que
  SYN-009 e CLEAN-003 já deixaram para o mesmo lugar.
- `pump` e `ItemGoneException` ficam com os nomes que têm: "Gone" não é
  vocabulário do `CONTEXT.md`, mas a KDoc carrega o sentido e o nome de `pump`
  veio do critério 1 de CLEAN-003.
