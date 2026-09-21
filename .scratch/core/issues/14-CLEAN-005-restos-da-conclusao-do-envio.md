# CLEAN-005: Restos do caminho de conclusão do envio de arquivo
Stage: to-implement
Status: needs-triage
Blocked by: none
Review: agent

- Primary files:
  - pc/src-tauri/src/server.rs (só `post_file` — o laço que grava o corpo e a
    tarefa de conclusão que faz o rename)
  - pc/src-tauri/tests/server.rs (só os testes novos dos três casos abaixo)

#### What to build

CLEAN-001 fechou o buraco grande na conclusão de `POST /items/file`: o aviso
de fim não se perde mais quando a conexão cai logo depois do último byte.
Sobraram três restos no mesmo trecho, todos anteriores a CLEAN-001 e nenhum
coberto pelos critérios de lá. Nenhum derruba o gate hoje; os três aparecem
com o celular numa rede ruim, que é o cenário normal do app.

1. **Corpo chunked ainda pode perder a conclusão.** A correção de CLEAN-001
   põe rename + `file_done` numa `tokio::spawn`, e nada suspende entre o fim
   do corpo e esse `spawn` — mas só porque, com `Content-Length`, o
   `stream.next()` final devolve `None` sem ir à rede. Num corpo chunked (o
   caminho do cabeçalho `X-File-Size`, documentado no topo de `server.rs`), o
   `stream.next()` final precisa ler o chunk terminador: aí ele suspende, a
   future pode ser cancelada antes do `spawn`, e nem o rename nem o
   `file_done` acontecem.

2. **O `.part` fica no disco para sempre.** Se o rename falha, ou se a
   conexão cai no meio do corpo (o `return StatusCode::BAD_REQUEST` do laço),
   o temporário nunca é apagado e o Item nunca sai de "em andamento" — a
   bolha fica com a barra de progresso parada, sem jeito de saber que morreu.

3. **`drop(file)` sem `flush` antes do rename.** `tokio::fs::File` é
   bufferizado e a escrita vai para o pool de bloqueio; `write_all().await`
   devolve antes de os bytes chegarem ao disco. O `drop` não é ponto de
   sincronização — o rename é disparado sem garantia de que o último pedaço
   já foi gravado.

#### Acceptance criteria

1. Um envio com corpo chunked (sem `Content-Length`, com `X-File-Size`) cuja
   conexão cai logo depois do último byte termina com o arquivo no nome
   final e o `file_done` disparado — igual ao caso com `Content-Length`.
2. Um envio cortado no meio do corpo não deixa `.part` na Pasta.
3. Um rename que falha não deixa `.part` na Pasta.
4. Um Item cujo envio morreu (corte no meio, ou rename que falhou) sai de
   "em andamento": a UI consegue distinguir "ainda chegando" de "não vai
   chegar". Como marcar isso é decisão do stage 1 — `ItemBody::File` hoje só
   tem `done: bool`.
5. Os bytes do corpo estão garantidamente no disco antes do rename.

#### Verification

    powershell -ExecutionPolicy Bypass -File .\gate.ps1

## Tests stage 2 writes (own commit, red)

- `pc/src-tauri/tests/server.rs`, na costura HTTP (o teste age como o
  celular, igual aos vizinhos): um envio chunked com a conexão cortada logo
  após o último byte — vermelho hoje porque o `file_done` não chega.
- `pc/src-tauri/tests/server.rs`: um envio cortado no meio — vermelho hoje
  porque sobra um `.part` na Pasta.
- `pc/src-tauri/tests/server.rs`: o mesmo envio cortado — vermelho hoje
  porque o Item continua `done: false` sem nada indicando que morreu.

## Comments

- 2026-09-20 (revisão de CLEAN-001): aberto pela revisão. Os três achados
  são do trecho que CLEAN-001 tinha nos Primary files, mas nenhum cabia nos
  critérios de lá (cada um precisa de teste novo), então viraram ticket em
  vez de correção pequena. O `envio_interrompido_nao_deixa_arquivo_com_nome_final`
  que já existe cobre só o nome *final*, não o `.part`.
- 2026-09-20: `Status: needs-triage` de propósito — o critério 4 mexe no
  formato de `ItemBody::File`, que é contrato com a UI, e isso é decisão de
  stage 1 (manual neste repo), não de stage 2.
